package com.hypervolumenice;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置页 3.1：
 * 所有控件只改本地草稿，点「确认更改」后一次性提交，并通过回读 Settings.System 校验结果。
 */
public class SettingsActivity extends Activity {

    private static final int STATE_IDLE = 0;
    private static final int STATE_SAVING = 1;
    private static final int STATE_OK = 2;

    private final LinkedHashMap<String, Integer> mDraft = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> mSaved = new LinkedHashMap<>();
    private final Map<String, TextView> mChips = new LinkedHashMap<>();
    private final Map<String, SeekBar> mSliders = new LinkedHashMap<>();
    private final Map<String, TextView> mHints = new LinkedHashMap<>();
    private final Map<String, int[]> mSpecs = new LinkedHashMap<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private float mDensity;
    private int mColorOnSurface;
    private int mColorOnSurfaceVariant;
    private int mColorPrimary;
    private int mColorOnPrimaryContainer;
    private int mColorSuccess;
    private int mColorError;
    private int mColorSurfaceHigh;

    private TextView mStatusChip;
    private TextView mVolumeValue;
    private TextView mStepsHint;
    private TextView mSaveStatus;
    private TextView mSaveButton;
    private TextView mPermissionView;
    private LinearLayout mContentColumn;
    private M3Switch mEnableSwitch;
    private int mBottomInset;

    private int mState = STATE_IDLE;
    private String mStateText;
    private int mAppliedSteps = -1;
    private int mLastBarState = -1;
    private int mLastBarDirty = -1;
    private boolean mFinishOnSave;
    private boolean mPendingDefaultsVersion;
    private boolean mResumed;

    private Toast mToast;
    private String mLastToastText;
    private long mLastToastAt;

    private int mUiMode;
    private float mFontScale;
    private int mDensityDpi;
    private int mScreenWidthDp;

    // ------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        this.mDensity = getResources().getDisplayMetrics().density;
        this.mColorOnSurface = getColor(R.color.vf_on_surface);
        this.mColorOnSurfaceVariant = getColor(R.color.vf_on_surface_variant);
        this.mColorPrimary = getColor(R.color.vf_primary);
        this.mColorOnPrimaryContainer = getColor(R.color.vf_on_primary_container);
        this.mColorSuccess = getColor(R.color.vf_success);
        this.mColorError = getColor(R.color.vf_error);
        this.mColorSurfaceHigh = getColor(R.color.vf_surface_container_high);

        Configuration config = getResources().getConfiguration();
        this.mUiMode = config.uiMode;
        this.mFontScale = config.fontScale;
        this.mDensityDpi = config.densityDpi;
        this.mScreenWidthDp = config.screenWidthDp;

        seedValues();
        if (state == null) {
            migrateLegacyDefaults();
        } else {
            restoreDraft(state);
        }
        buildUi();
        refreshStatus();
        updateSaveBar();
        if (this.mPendingDefaultsVersion) {
            toast("已套用 3.1 新默认值，点「确认更改」后保存");
        }
    }

    /**
     * 2.x 的默认值是 100 / 30 / 4 / 2，3.1 改成 60 / 12 / 3 / 1。
     * 只有四个值都还是旧出厂值时才迁移，用户自己改过的一律不动。
     */
    private void migrateLegacyDefaults() {
        if (readInt(ConfigContract.KEY_DEFAULTS_VERSION, 0) >= ConfigContract.DEFAULTS_VERSION) {
            return;
        }
        boolean untouched = readInt(ConfigContract.KEY_STEPS, -1) == ConfigContract.LEGACY_DEF_STEPS
                && readInt(ConfigContract.KEY_THRESHOLD, -1) == ConfigContract.LEGACY_DEF_THRESHOLD
                && readInt(ConfigContract.KEY_COARSE, -1) == ConfigContract.LEGACY_DEF_COARSE
                && readInt(ConfigContract.KEY_SNAP, -1) == ConfigContract.LEGACY_DEF_SNAP;
        if (!untouched) {
            return;
        }
        this.mDraft.put(ConfigContract.KEY_STEPS, ConfigContract.DEF_STEPS);
        this.mDraft.put(ConfigContract.KEY_THRESHOLD, ConfigContract.DEF_THRESHOLD);
        this.mDraft.put(ConfigContract.KEY_COARSE, ConfigContract.DEF_COARSE);
        this.mDraft.put(ConfigContract.KEY_SNAP, ConfigContract.DEF_SNAP);
        this.mPendingDefaultsVersion = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        this.mResumed = true;
        refreshStatus();
        updatePermissionHint();
    }

    @Override
    protected void onPause() {
        super.onPause();
        this.mResumed = false;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        ArrayList<String> names = new ArrayList<>(mDraft.keySet());
        int[] values = new int[names.size()];
        for (int i = 0; i < names.size(); i++) {
            values[i] = mDraft.get(names.get(i));
        }
        out.putStringArrayList("draft_names", names);
        out.putIntArray("draft_values", values);
        out.putInt("state", mState);
        out.putString("state_text", mStateText);
    }

    /**
     * Android 17 起，CONFIG_COLOR_MODE 等变更默认不再重建 Activity。
     * 颜色/字号/密度变化后必须自己重建，否则界面会停在旧主题。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (newConfig.uiMode != mUiMode
                || newConfig.fontScale != mFontScale
                || newConfig.densityDpi != mDensityDpi) {
            recreate();
            return;
        }
        if (newConfig.screenWidthDp != mScreenWidthDp) {
            this.mScreenWidthDp = newConfig.screenWidthDp;
            applyContentPadding();
        }
    }

    @Override
    public void onBackPressed() {
        if (dirtyCount() > 0) {
            showExitDialog();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------ data

    private void seedValues() {
        for (String key : ConfigContract.WRITE_KEYS) {
            int value = ConfigContract.clamp(key, readInt(key, ConfigContract.def(key)));
            this.mSaved.put(key, value);
            this.mDraft.put(key, value);
        }
    }

    private void restoreDraft(Bundle state) {
        ArrayList<String> names = state.getStringArrayList("draft_names");
        int[] values = state.getIntArray("draft_values");
        if (names == null || values == null || names.size() != values.length) {
            return;
        }
        for (int i = 0; i < names.size(); i++) {
            String key = names.get(i);
            if (this.mDraft.containsKey(key)) {
                this.mDraft.put(key, ConfigContract.clamp(key, values[i]));
            }
        }
        this.mState = state.getInt("state", STATE_IDLE);
        if (this.mState == STATE_SAVING) {
            this.mState = STATE_IDLE;
        }
        this.mStateText = state.getString("state_text");
    }

    private int readInt(String key, int fallback) {
        try {
            return Settings.System.getInt(getContentResolver(), key, fallback);
        } catch (Throwable t) {
            return fallback;
        }
    }

    private String readString(String key, String fallback) {
        try {
            String value = Settings.System.getString(getContentResolver(), key);
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    private int value(String key) {
        Integer v = this.mDraft.get(key);
        return v == null ? ConfigContract.def(key) : v;
    }

    private void setDraft(String key, int raw) {
        int value = ConfigContract.clamp(key, raw);
        if (value == value(key)) {
            return;
        }
        boolean wasDirty = !this.mDraft.get(key).equals(this.mSaved.get(key));
        this.mDraft.put(key, value);
        if (this.mState == STATE_OK) {
            this.mState = STATE_IDLE;
            this.mStateText = null;
        }
        syncControl(key);
        if (wasDirty != !this.mDraft.get(key).equals(this.mSaved.get(key))) {
            updateDirtyHints();
        }
        updateSaveBar();
    }

    private int dirtyCount() {
        int count = 0;
        for (String key : this.mDraft.keySet()) {
            if (!this.mDraft.get(key).equals(this.mSaved.get(key))) {
                count++;
            }
        }
        return count;
    }

    private LinkedHashMap<String, Integer> dirtyValues() {
        LinkedHashMap<String, Integer> changes = new LinkedHashMap<>();
        for (String key : this.mDraft.keySet()) {
            int value = this.mDraft.get(key);
            if (value != this.mSaved.get(key)) {
                changes.put(key, value);
            }
        }
        if (this.mPendingDefaultsVersion
                && readInt(ConfigContract.KEY_DEFAULTS_VERSION, 0) < ConfigContract.DEFAULTS_VERSION) {
            changes.put(ConfigContract.KEY_DEFAULTS_VERSION, ConfigContract.DEFAULTS_VERSION);
        }
        return changes;
    }

    private void syncControl(String key) {
        TextView chip = this.mChips.get(key);
        if (chip != null) {
            String text = formatValue(key, value(key));
            if (!text.contentEquals(chip.getText())) {
                chip.setText(text);
            }
        }
        SeekBar slider = this.mSliders.get(key);
        if (slider != null) {
            int progress = toProgress(key, value(key));
            if (slider.getProgress() != progress) {
                slider.setProgress(progress);
            }
        }
        if (ConfigContract.KEY_STEPS.equals(key)) {
            updateStepsHint();
        }
    }

    private void syncAllControls() {
        for (String key : this.mDraft.keySet()) {
            syncControl(key);
        }
    }

    // ------------------------------------------------------------ ui

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(getColor(R.color.vf_surface));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        FrameLayout.LayoutParams scrollLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(scroll, scrollLp);

        FrameLayout inner = new FrameLayout(this);
        scroll.addView(inner, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        FrameLayout.LayoutParams columnLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        inner.addView(column, columnLp);
        this.mContentColumn = column;

        column.setPadding(contentSidePadding(), dp(12), contentSidePadding(), dp(132));

        addHeader(column);
        addSwitchCard(column);
        addSectionLabel(column, "精确调节");
        addVolumeCard(column);
        addSectionLabel(column, "参数调节");
        addSliderCard(column, "音量档位数", ConfigContract.KEY_STEPS, 20, 120, 1,
                "每格 1 档，−/+ 也是 1 档。保存后需重启手机生效。");
        addSliderCard(column, "细调范围", ConfigContract.KEY_THRESHOLD, 0, 100, 1,
                "低于该档位按「低音量步进」走，超过后按「高音量步进」走。");
        addSliderCard(column, "低音量步进", ConfigContract.KEY_FINE, 1, 6, 1,
                "低音量区每按一次走几档，1 = 最细。");
        addSliderCard(column, "高音量步进", ConfigContract.KEY_COARSE, 1, 8, 1,
                "超过细调范围后每次按键走的档数，调大音量更快。");
        addSliderCard(column, "滑条吸附", ConfigContract.KEY_SNAP, 1, 6, 1,
                "拖动音量滑条时按该档数对齐，1 = 关闭。");
        addInfoCard(column);
        addFooter(column);

        LinearLayout bar = buildSaveBar();
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.BOTTOM;
        root.addView(bar, barLp);

        applyInsets(scroll, column, bar);
        setContentView(root);
        syncAllControls();
        updateDirtyHints();
    }

    private void applyInsets(ScrollView scroll, final LinearLayout column, final LinearLayout bar) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            this.mBottomInset = bars.bottom;
            view.setPadding(0, bars.top, 0, 0);
            int side = contentSidePadding();
            column.setPadding(side, dp(12), side, dp(132) + bars.bottom);
            bar.setPadding(side, dp(10), side, dp(10) + bars.bottom);
            return insets;
        });
        scroll.requestApplyInsets();
    }

    /** 内容最大宽度 560dp，两侧留白；分屏/平板都居中且不会被裁切。 */
    private int contentSidePadding() {
        int widthDp = getResources().getConfiguration().screenWidthDp;
        return dp(Math.max(20, (widthDp - 560) / 2));
    }

    private void applyContentPadding() {
        if (this.mContentColumn == null) {
            return;
        }
        int side = contentSidePadding();
        this.mContentColumn.setPadding(side, dp(12), side, dp(132) + this.mBottomInset);
    }

    private void addHeader(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout badge = new FrameLayout(this);
        badge.setBackgroundResource(R.drawable.vf_icon_bg);
        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.vf_ic_wave);
        icon.setColorFilter(this.mColorOnPrimaryContainer);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(dp(26), dp(26));
        iconLp.gravity = Gravity.CENTER;
        badge.addView(icon, iconLp);
        row.addView(badge, new LinearLayout.LayoutParams(dp(56), dp(56)));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titlesLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titlesLp.leftMargin = dp(16);

        TextView title = new TextView(this);
        title.setText("澎湃音阶微调");
        title.setTextSize(26f);
        title.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        title.setTextColor(this.mColorOnSurface);
        title.setLetterSpacing(-0.02f);
        titles.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("LSPosed · 让每一格音量都调得准");
        subtitle.setTextSize(13f);
        subtitle.setTextColor(this.mColorOnSurfaceVariant);
        subtitle.setPadding(0, dp(3), 0, 0);
        titles.addView(subtitle);

        row.addView(titles, titlesLp);
        parent.addView(row);

        TextView chip = new TextView(this);
        this.mStatusChip = chip;
        chip.setTextSize(12f);
        chip.setTextColor(this.mColorOnPrimaryContainer);
        chip.setBackgroundResource(R.drawable.vf_chip);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.topMargin = dp(18);
        parent.addView(chip, chipLp);
    }

    private void addSwitchCard(LinearLayout parent) {
        LinearLayout card = newCard(parent);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);

        TextView title = new TextView(this);
        title.setText("启用音阶微调");
        title.setTextSize(16f);
        title.setTextColor(this.mColorOnSurface);
        texts.addView(title);

        TextView hint = new TextView(this);
        hint.setText("关闭后恢复系统原生档位，需重启手机");
        hint.setTextSize(12f);
        hint.setTextColor(this.mColorOnSurfaceVariant);
        hint.setPadding(0, dp(4), 0, 0);
        texts.addView(hint);

        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        this.mEnableSwitch = new M3Switch(this);
        this.mEnableSwitch.setChecked(value(ConfigContract.KEY_ENABLED) != 0, false);
        LinearLayout.LayoutParams switchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        switchLp.leftMargin = dp(16);
        row.addView(this.mEnableSwitch, switchLp);

        View.OnClickListener click = view -> {
            this.mEnableSwitch.toggle();
            setDraft(ConfigContract.KEY_ENABLED, this.mEnableSwitch.isChecked() ? 1 : 0);
        };
        row.setOnClickListener(click);
        this.mEnableSwitch.setOnClickListener(click);
        card.addView(row);
    }

    private void addSliderCard(LinearLayout parent, String title, final String key,
                               final int min, final int max, final int step, String hint) {
        LinearLayout card = newCard(parent);
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(15f);
        label.setTextColor(this.mColorOnSurface);
        header.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView chip = new TextView(this);
        chip.setTextSize(12f);
        chip.setTextColor(this.mColorOnPrimaryContainer);
        chip.setBackgroundResource(R.drawable.vf_chip);
        chip.setPadding(dp(10), dp(5), dp(10), dp(5));
        chip.setGravity(Gravity.CENTER);
        chip.setMinWidth(dp(58));
        this.mChips.put(key, chip);

        TextView minus = stepButton("−");
        TextView plus = stepButton("+");
        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.HORIZONTAL);
        group.setGravity(Gravity.CENTER_VERTICAL);
        group.addView(minus);
        LinearLayout.LayoutParams chipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipLp.leftMargin = dp(6);
        chipLp.rightMargin = dp(6);
        group.addView(chip, chipLp);
        group.addView(plus);
        header.addView(group);
        card.addView(header);

        final SeekBar seekBar = new SeekBar(this);
        int slots = (max - min) / step;
        this.mSpecs.put(key, new int[]{min, max, step});
        seekBar.setMax(slots);
        seekBar.setProgress(ConfigContract.clampInt(toProgress(key, value(key)), 0, slots));
        seekBar.setProgressDrawable(getDrawable(R.drawable.vf_slider_track));
        seekBar.setThumb(getDrawable(R.drawable.vf_slider_thumb));
        seekBar.setThumbOffset(0);
        seekBar.setSplitTrack(false);
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        sliderLp.topMargin = dp(12);
        card.addView(seekBar, sliderLp);
        this.mSliders.put(key, seekBar);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (!fromUser) {
                    return;
                }
                setDraft(key, min + progress * step);
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
            }
        });
        minus.setOnClickListener(view -> setDraft(key, value(key) - step));
        plus.setOnClickListener(view -> setDraft(key, value(key) + step));

        TextView tip = new TextView(this);
        tip.setText(hint);
        tip.setTextSize(12f);
        tip.setTextColor(this.mColorOnSurfaceVariant);
        tip.setLineSpacing(dp(3), 1f);
        card.addView(tip);
        this.mHints.put(key, tip);

        chip.setText(formatValue(key, value(key)));
        if (ConfigContract.KEY_STEPS.equals(key)) {
            this.mStepsHint = tip;
        }
    }

    private void addVolumeCard(LinearLayout parent) {
        LinearLayout card = newCard(parent);

        TextView valueView = new TextView(this);
        valueView.setTextSize(15f);
        valueView.setTextColor(this.mColorOnSurface);
        this.mVolumeValue = valueView;
        card.addView(valueView);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(14);

        TextView minus = roundButton("−");
        minus.setOnClickListener(view -> adjust(-1));
        row.addView(minus, new LinearLayout.LayoutParams(0, dp(44), 1f));
        row.addView(new View(this), new LinearLayout.LayoutParams(dp(12), dp(1)));
        TextView plus = roundButton("+");
        plus.setOnClickListener(view -> adjust(1));
        row.addView(plus, new LinearLayout.LayoutParams(0, dp(44), 1f));
        card.addView(row, rowLp);
    }

    private void addInfoCard(LinearLayout parent) {
        LinearLayout card = newCard(parent);

        TextView title = new TextView(this);
        title.setText("说明");
        title.setTextSize(15f);
        title.setTextColor(this.mColorOnSurface);
        card.addView(title);

        TextView body = new TextView(this);
        body.setTextSize(13f);
        body.setTextColor(this.mColorOnSurfaceVariant);
        body.setLineSpacing(dp(4), 1f);
        body.setPadding(0, dp(10), 0, 0);
        body.setText("作用于音乐音量（streamType " + readString(ConfigContract.KEY_STREAMS, "3")
                + "），铃声 / 闹钟 / 通知保持系统原生档位。\n"
                + "参数先攒在本地，点底部「确认更改」后一次性保存；「音量档位数」与开关状态需要重启手机。\n"
                + "停用模块前请先关闭开关并确认保存，否则开机音量会被截断成最大。");
        card.addView(body);

        final TextView permission = new TextView(this);
        permission.setTextSize(12f);
        permission.setTextColor(this.mColorPrimary);
        permission.setPadding(0, dp(14), 0, 0);
        permission.setOnClickListener(view -> openWriteSettings());
        this.mPermissionView = permission;
        card.addView(permission);
        updatePermissionHint();

        TextView adb = new TextView(this);
        adb.setTextSize(11f);
        adb.setTextColor(this.mColorOnSurfaceVariant);
        adb.setTypeface(Typeface.MONOSPACE);
        adb.setLineSpacing(dp(4), 1f);
        adb.setPadding(dp(12), dp(10), dp(12), dp(10));
        adb.setBackgroundResource(R.drawable.vf_chip);
        adb.setText("adb shell settings put system volume_fine_fine 2\n"
                + "adb shell settings put system volume_fine_snap 2");
        LinearLayout.LayoutParams adbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        adbLp.topMargin = dp(12);
        card.addView(adb, adbLp);
    }

    private void addSectionLabel(LinearLayout parent, String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12f);
        label.setLetterSpacing(0.08f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setTextColor(this.mColorOnSurfaceVariant);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(2);
        parent.addView(label, lp);
    }

    private void addFooter(LinearLayout parent) {
        TextView footer = new TextView(this);
        footer.setTextSize(11f);
        footer.setTextColor(this.mColorOnSurfaceVariant);
        footer.setGravity(Gravity.CENTER);
        footer.setText("v3.8 · API 102 · 作用域 system");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(20);
        parent.addView(footer, lp);
    }

    private LinearLayout newCard(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.vf_card);
        card.setPadding(dp(20), dp(18), dp(20), dp(18));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        parent.addView(card, lp);
        return card;
    }

    private LinearLayout buildSaveBar() {
        LinearLayout wrapper = new LinearLayout(this);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.setBackgroundColor(this.mColorSurfaceHigh);

        View divider = new View(this);
        divider.setBackgroundColor(getColor(R.color.vf_outline_variant));
        wrapper.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(20), dp(10), dp(20), dp(10));

        TextView status = new TextView(this);
        status.setTextSize(12f);
        status.setTextColor(this.mColorOnSurfaceVariant);
        this.mSaveStatus = status;
        bar.addView(status, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView reset = new TextView(this);
        reset.setText("恢复默认");
        reset.setTextSize(13f);
        reset.setGravity(Gravity.CENTER);
        reset.setTextColor(getColor(R.color.vf_on_secondary_container));
        reset.setBackgroundResource(R.drawable.vf_button_tonal);
        reset.setClickable(true);
        reset.setPadding(dp(14), 0, dp(14), 0);
        reset.setOnClickListener(view -> resetDraft());
        LinearLayout.LayoutParams resetLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
        resetLp.rightMargin = dp(10);
        bar.addView(reset, resetLp);

        TextView confirm = new TextView(this);
        confirm.setText("确认更改");
        confirm.setTextSize(13f);
        confirm.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        confirm.setTextColor(getColor(R.color.vf_on_primary));
        confirm.setBackgroundResource(R.drawable.vf_button);
        confirm.setGravity(Gravity.CENTER);
        confirm.setClickable(true);
        confirm.setPadding(dp(20), 0, dp(20), 0);
        confirm.setOnClickListener(view -> commit());
        bar.addView(confirm, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)));
        this.mSaveButton = confirm;

        wrapper.addView(bar);
        return wrapper;
    }

    private TextView stepButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(15f);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getColor(R.color.vf_on_secondary_container));
        view.setBackgroundResource(R.drawable.vf_step_button);
        view.setClickable(true);
        view.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        return view;
    }

    private TextView roundButton(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(20f);
        view.setGravity(Gravity.CENTER);
        view.setTextColor(getColor(R.color.vf_on_secondary_container));
        view.setBackgroundResource(R.drawable.vf_button_tonal);
        view.setClickable(true);
        return view;
    }

    // ------------------------------------------------------------ state

    private void updateSaveBar() {
        if (this.mSaveStatus == null || this.mSaveButton == null) {
            return;
        }
        int dirty = dirtyCount();
        if (this.mState == this.mLastBarState && dirty == this.mLastBarDirty) {
            return;
        }
        this.mLastBarState = this.mState;
        this.mLastBarDirty = dirty;
        if (this.mState == STATE_SAVING) {
            this.mSaveStatus.setText("正在保存…");
            this.mSaveStatus.setTextColor(this.mColorPrimary);
            this.mSaveButton.setText("保存中");
            this.mSaveButton.setEnabled(false);
            this.mSaveButton.setAlpha(0.6f);
            return;
        }
        if (dirty == 0) {
            if (this.mState == STATE_OK && this.mStateText != null) {
                this.mSaveStatus.setText(this.mStateText);
                this.mSaveStatus.setTextColor(this.mColorSuccess);
            } else {
                this.mSaveStatus.setText("没有待保存的更改");
                this.mSaveStatus.setTextColor(this.mColorOnSurfaceVariant);
            }
            this.mSaveButton.setText("确认更改");
            this.mSaveButton.setEnabled(false);
            this.mSaveButton.setAlpha(0.38f);
            return;
        }
        this.mSaveStatus.setText(dirty + " 项待保存");
        this.mSaveStatus.setTextColor(this.mColorPrimary);
        this.mSaveButton.setText("确认更改");
        this.mSaveButton.setEnabled(true);
        this.mSaveButton.setAlpha(1f);
    }

    private void updateDirtyHints() {
        for (Map.Entry<String, TextView> entry : this.mHints.entrySet()) {
            String key = entry.getKey();
            TextView hint = entry.getValue();
            boolean dirty = !this.mDraft.get(key).equals(this.mSaved.get(key));
            if (ConfigContract.KEY_STEPS.equals(key)) {
                updateStepsHint();
                continue;
            }
            hint.setTextColor(dirty ? this.mColorPrimary : this.mColorOnSurfaceVariant);
        }
    }

    private void refreshStatus() {
        AudioManager audio = getSystemService(AudioManager.class);
        int current = audio == null ? 0 : audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        int max = audio == null ? 0 : audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (this.mStatusChip != null) {
            this.mStatusChip.setText("当前 " + current + " / " + max + " 档");
        }
        if (this.mVolumeValue != null) {
            this.mVolumeValue.setText("当前音乐音量 " + current + " / " + max);
        }
        if (this.mEnableSwitch != null) {
            this.mEnableSwitch.setChecked(value(ConfigContract.KEY_ENABLED) != 0, false);
        }
        this.mAppliedSteps = max;
        updateStepsHint();
    }

    private void updateStepsHint() {
        if (this.mStepsHint == null) {
            return;
        }
        int applied = this.mAppliedSteps;
        int target = value(ConfigContract.KEY_STEPS);
        boolean dirty = !this.mDraft.get(ConfigContract.KEY_STEPS)
                .equals(this.mSaved.get(ConfigContract.KEY_STEPS));
        String text;
        int color;
        if (applied <= 0 || applied == target) {
            text = "每格 1 档，−/+ 也是 1 档。保存后需重启手机生效。";
            color = this.mColorOnSurfaceVariant;
        } else {
            text = "当前生效 " + applied + " 档，保存后重启将变成 " + target + " 档。";
            color = dirty ? this.mColorPrimary : this.mColorOnSurfaceVariant;
        }
        if (!text.contentEquals(this.mStepsHint.getText())) {
            this.mStepsHint.setText(text);
        }
        if (this.mStepsHint.getCurrentTextColor() != color) {
            this.mStepsHint.setTextColor(color);
        }
    }

    private void updatePermissionHint() {
        TextView permission = this.mPermissionView;
        if (permission == null) {
            return;
        }
        if (Settings.System.canWrite(this)) {
            permission.setText("已获得直接写入权限，保存不再依赖广播回执");
            permission.setTextColor(this.mColorSuccess);
            permission.setClickable(false);
        } else {
            permission.setText("可选：授予「修改系统设置」权限，保存更稳（点击授权）");
            permission.setTextColor(this.mColorPrimary);
            permission.setClickable(true);
        }
    }

    private void openWriteSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            toast("无法打开授权页面：" + t.getMessage());
        }
    }

    // ------------------------------------------------------------ actions

    private void resetDraft() {
        for (String key : ConfigContract.WRITE_KEYS) {
            this.mDraft.put(key, ConfigContract.clamp(key, ConfigContract.def(key)));
        }
        this.mEnableSwitch.setChecked(value(ConfigContract.KEY_ENABLED) != 0, true);
        syncAllControls();
        this.mState = STATE_IDLE;
        this.mStateText = null;
        this.mPendingDefaultsVersion = false;
        updateSaveBar();
        toast("已填入默认值，点「确认更改」后保存");
    }

    private void commit() {
        if (this.mState == STATE_SAVING) {
            return;
        }
        LinkedHashMap<String, Integer> changes = dirtyValues();
        if (changes.isEmpty()) {
            return;
        }
        this.mState = STATE_SAVING;
        this.mStateText = null;
        updateSaveBar();
        attemptSave(changes, 0);
    }

    private void attemptSave(final LinkedHashMap<String, Integer> changes, final int attempt) {
        boolean direct = Settings.System.canWrite(this) && writeDirect(changes);
        if (!direct) {
            sendConfig(changes);
        }
        this.mHandler.postDelayed(() -> {
            if (verify(changes)) {
                onSaveSuccess(changes);
                return;
            }
            if (attempt < 2) {
                this.mHandler.postDelayed(() -> attemptSave(changes, attempt + 1), 180L);
            } else {
                onSaveFailed(changes);
            }
        }, 280L);
    }

    private boolean writeDirect(Map<String, Integer> changes) {
        try {
            for (Map.Entry<String, Integer> entry : changes.entrySet()) {
                Settings.System.putInt(getContentResolver(), entry.getKey(), entry.getValue());
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void sendConfig(Map<String, Integer> changes) {
        Intent intent = new Intent(ConfigContract.ACTION_CONFIG);
        for (Map.Entry<String, Integer> entry : changes.entrySet()) {
            intent.putExtra(entry.getKey(), entry.getValue());
        }
        try {
            sendBroadcast(intent);
        } catch (Throwable t) {
            toast("发送失败：" + t.getMessage());
        }
    }

    private boolean verify(Map<String, Integer> changes) {
        for (Map.Entry<String, Integer> entry : changes.entrySet()) {
            int actual = readInt(entry.getKey(), Integer.MIN_VALUE);
            if (actual != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    private void onSaveSuccess(Map<String, Integer> changes) {
        this.mSaved.putAll(changes);
        this.mPendingDefaultsVersion = false;
        int total = 0;
        int reboot = 0;
        for (String key : changes.keySet()) {
            if (ConfigContract.KEY_DEFAULTS_VERSION.equals(key)) {
                continue;
            }
            total++;
            if (ConfigContract.needsReboot(key)) {
                reboot++;
            }
        }
        int immediate = total - reboot;
        StringBuilder message = new StringBuilder("已保存");
        if (immediate > 0) {
            message.append(" · ").append(immediate).append(" 项即时生效");
        }
        if (reboot > 0) {
            message.append(immediate > 0 ? "，" : " · ").append(reboot).append(" 项重启后生效");
        }
        this.mState = STATE_OK;
        this.mStateText = message.toString();
        updateSaveBar();
        toast(message.toString());
        refreshStatus();

        if (this.mFinishOnSave) {
            this.mFinishOnSave = false;
            this.mHandler.postDelayed(this::finish, 350L);
            return;
        }
        this.mHandler.postDelayed(() -> {
            if (this.mState == STATE_OK && dirtyCount() == 0) {
                this.mState = STATE_IDLE;
                this.mStateText = null;
                updateSaveBar();
            }
        }, 2600L);
    }

    private void onSaveFailed(Map<String, Integer> changes) {
        this.mState = STATE_IDLE;
        this.mStateText = null;
        this.mFinishOnSave = false;
        updateSaveBar();
        this.mSaveStatus.setText("保存失败，点右侧重试");
        this.mSaveStatus.setTextColor(this.mColorError);
        showFailureDialog(changes);
    }

    private void showFailureDialog(Map<String, Integer> changes) {
        StringBuilder adb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : changes.entrySet()) {
            if (ConfigContract.KEY_DEFAULTS_VERSION.equals(entry.getKey())) {
                continue;
            }
            adb.append(ConfigContract.adb(entry.getKey(), entry.getValue())).append('\n');
        }
        String message = "改动没有写进系统设置。常见原因：\n"
                + "· 模块还没加载（首次启用后需要重启手机）\n"
                + "· LSPosed 作用域没有勾选「系统」\n"
                + "· 系统拒绝了写入\n\n"
                + "可以点「重试」，或复制下面的命令用 adb 执行：\n\n"
                + adb;
        new AlertDialog.Builder(this)
                .setTitle("保存失败")
                .setMessage(message)
                .setPositiveButton("重试", (dialog, which) -> {
                    this.mState = STATE_SAVING;
                    updateSaveBar();
                    attemptSave(new LinkedHashMap<>(changes), 0);
                })
                .setNeutralButton("复制命令", (dialog, which) -> {
                    copy(adb.toString());
                    toast("命令已复制");
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void showExitDialog() {
        int dirty = dirtyCount();
        new AlertDialog.Builder(this)
                .setTitle("有 " + dirty + " 项改动未保存")
                .setMessage("先保存再退出，还是直接放弃？")
                .setPositiveButton("保存并退出", (dialog, which) -> {
                    this.mFinishOnSave = true;
                    commit();
                })
                .setNeutralButton("放弃", (dialog, which) -> finish())
                .setNegativeButton("继续编辑", null)
                .show();
    }

    private void adjust(int direction) {
        AudioManager audio = getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        // Android 17：后台调音量 API 会静默失败，这里先做可见性守卫
        if (!this.mResumed || !hasWindowFocus()) {
            toast("系统限制后台调节音量，请回到应用内再试");
            return;
        }
        final int before = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    direction > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI);
        } catch (Throwable t) {
            toast("调节失败：" + t.getMessage());
            return;
        }
        this.mHandler.postDelayed(() -> {
            int after = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            if (after == before) {
                toast("音量没有变化：可能被系统的后台音频限制拦下了");
            }
            refreshStatus();
        }, 220L);
    }

    // ------------------------------------------------------------ helpers

    private String formatValue(String key, int value) {
        switch (key) {
            case ConfigContract.KEY_THRESHOLD:
                return value + " 档";
            case ConfigContract.KEY_FINE:
            case ConfigContract.KEY_COARSE:
                return value + " 档/次";
            case ConfigContract.KEY_SNAP:
                return value <= 1 ? "关闭" : value + " 档";
            case ConfigContract.KEY_STEPS:
                return value + " 档";
            default:
                return String.valueOf(value);
        }
    }

    private int toProgress(String key, int value) {
        int[] spec = this.mSpecs.get(key);
        if (spec == null || spec[2] <= 0) {
            return value;
        }
        return Math.round((value - spec[0]) / (float) spec[2]);
    }

    private int dp(float value) {
        return Math.round(this.mDensity * value);
    }

    private void toast(String text) {
        try {
            long now = SystemClock.elapsedRealtime();
            if (text.equals(this.mLastToastText) && now - this.mLastToastAt < 1600L) {
                return;
            }
            this.mLastToastText = text;
            this.mLastToastAt = now;
            if (this.mToast != null) {
                this.mToast.cancel();
            }
            this.mToast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
            this.mToast.show();
        } catch (Throwable ignored) {
        }
    }

    private void copy(String text) {
        try {
            ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("volume_fine", text));
            }
        } catch (Throwable ignored) {
        }
    }
}
