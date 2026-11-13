package com.hypervolumenice;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.SparseIntArray;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * system_server 侧：把系统音量从「原生 N 档」拆成更细的步进。
 *
 * 注意（Android 17 / targetSdk 37 起）：static final 字段不可再被反射改写。
 * MAX_STREAM_VOLUME 是 static final int[]，因此只允许读字段引用后修改数组元素，
 * 绝对不要用 field.set(null, newArray) —— 那会直接抛 IllegalAccessException。
 */
public final class VolumeFineModule extends XposedModule {

    private static final String TAG = "VolumeFine";
    private static final String PROP_MEDIA_VOL_STEPS = "ro.config.media_vol_steps";
    private static final int DEVICE_OUT_DEFAULT = 0x40000000;
    private static final int STEP_UNIT = 10;
    private static final int MAX_DELTA = 120;
    /** 兜底 TTL：正常靠 ContentObserver 失效，这里只防观察者漏事件。 */
    private static final long CONFIG_TTL_MS = 5000L;

    private static final ThreadLocal<Boolean> IN_KEY_PATH = new ThreadLocal<>();

    private Field fIndexMap;
    private Field fIndexMax;
    private Field fStreamType;
    private Object mMaxStreamVolume;
    private int[] mStockSteps;
    private int mModuleUid = -1;
    private boolean mPatched;
    private volatile boolean mReceiverRegistered;
    private ContentResolver mResolver;
    private Set<Integer> mStreamsCache;
    private String mStreamsRaw;
    private volatile Config mConfig;
    private long mConfigAt;
    private ContentObserver mConfigObserver;
    private Method mSentFromUid;

    private static final class Config {
        boolean enabled;
        int steps;
        int threshold;
        int fine;
        int coarse;
        int snap;
        boolean debug;
        Set<Integer> streams;
    }

    // ---------------------------------------------------------------- config

    private Config config() {
        long now = System.nanoTime() / 1000000L;
        Config cached = this.mConfig;
        if (cached != null && now - this.mConfigAt < CONFIG_TTL_MS) {
            return cached;
        }
        Map<String, String> snapshot = snapshotSettings();
        Config c = new Config();
        c.enabled = intOf(snapshot, ConfigContract.KEY_ENABLED, ConfigContract.DEF_ENABLED) != 0;
        c.steps = ConfigContract.clamp(ConfigContract.KEY_STEPS,
                intOf(snapshot, ConfigContract.KEY_STEPS, ConfigContract.DEF_STEPS));
        c.threshold = ConfigContract.clamp(ConfigContract.KEY_THRESHOLD,
                intOf(snapshot, ConfigContract.KEY_THRESHOLD, ConfigContract.DEF_THRESHOLD));
        c.fine = ConfigContract.clamp(ConfigContract.KEY_FINE,
                intOf(snapshot, ConfigContract.KEY_FINE, ConfigContract.DEF_FINE));
        c.coarse = ConfigContract.clamp(ConfigContract.KEY_COARSE,
                intOf(snapshot, ConfigContract.KEY_COARSE, ConfigContract.DEF_COARSE));
        c.snap = ConfigContract.clamp(ConfigContract.KEY_SNAP,
                intOf(snapshot, ConfigContract.KEY_SNAP, ConfigContract.DEF_SNAP));
        c.debug = intOf(snapshot, ConfigContract.KEY_DEBUG, ConfigContract.DEF_DEBUG) != 0;
        String streams = snapshot.containsKey(ConfigContract.KEY_STREAMS)
                ? snapshot.get(ConfigContract.KEY_STREAMS) : ConfigContract.DEF_STREAMS;
        c.streams = parseStreams(streams);
        this.mConfig = c;
        this.mConfigAt = now;
        return c;
    }

    private Map<String, String> snapshotSettings() {
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return new HashMap<>();
        }
        Cursor cursor = null;
        try {
            cursor = resolver.query(Settings.System.CONTENT_URI, new String[]{"name", "value"},
                    "name LIKE ?", new String[]{"volume_fine_%"}, null);
            if (cursor != null) {
                HashMap<String, String> map = new HashMap<>();
                while (cursor.moveToNext()) {
                    map.put(cursor.getString(0), cursor.getString(1));
                }
                if (!map.isEmpty()) {
                    return map;
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "snapshotSettings failed, fallback: " + t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return readKeysOneByOne(resolver);
    }

    private Map<String, String> readKeysOneByOne(ContentResolver resolver) {
        HashMap<String, String> map = new HashMap<>();
        if (resolver == null) {
            return map;
        }
        for (String key : ConfigContract.READ_KEYS) {
            try {
                String value = Settings.System.getString(resolver, key);
                if (value != null) {
                    map.put(key, value);
                }
            } catch (Throwable ignored) {
            }
        }
        return map;
    }

    private static int intOf(Map<String, String> map, String key, int fallback) {
        String raw = map.get(key);
        if (raw != null) {
            try {
                return Integer.parseInt(raw.trim());
            } catch (Throwable ignored) {
            }
        }
        return fallback;
    }

    private Set<Integer> parseStreams(String raw) {
        if (raw != null && raw.equals(this.mStreamsRaw) && this.mStreamsCache != null) {
            return this.mStreamsCache;
        }
        HashSet<Integer> set = new HashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String trimmed = part.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                try {
                    set.add(Integer.parseInt(trimmed));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        this.mStreamsRaw = raw;
        this.mStreamsCache = set;
        return set;
    }

    // ---------------------------------------------------------------- hooks

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        log(4, TAG, "module loaded, process=" + param.getProcessName());
    }

    @Override
    public void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        ClassLoader cl = param.getClassLoader();
        log(4, TAG, "system_server starting, installing volume hooks");
        try {
            hookAudioServiceCtor(cl);
        } catch (Throwable t) {
            log(6, TAG, "hook AudioService ctor failed", t);
        }
        try {
            hookVolumeStepsProperty(cl);
        } catch (Throwable t) {
            log(6, TAG, "hook SystemProperties failed", t);
        }
        try {
            hookVolumeStreamState(cl);
        } catch (Throwable t) {
            log(6, TAG, "hook VolumeStreamState failed", t);
        }
        try {
            hookAbsoluteSet(cl);
        } catch (Throwable t) {
            log(5, TAG, "hook setIndex failed: " + t);
        }
        try {
            hookIndexFactors(cl);
        } catch (Throwable t) {
            log(5, TAG, "hook updateIndexFactors failed: " + t);
        }
        try {
            hookHalVolume(cl);
        } catch (Throwable t) {
            log(5, TAG, "hook AudioSystemAdapter failed: " + t);
        }
        ensureConfigReceiver();
        startReceiverRetry();
        log(4, TAG, "hooks installed, api=" + getApiVersion()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    private void hookAudioServiceCtor(ClassLoader cl) throws Throwable {
        Class<?> clazz = Class.forName("com.android.server.audio.AudioService", false, cl);
        Field arrayField = clazz.getDeclaredField("MAX_STREAM_VOLUME");
        arrayField.setAccessible(true);
        this.mMaxStreamVolume = arrayField.get(null);

        Constructor<?> target = null;
        for (Constructor<?> ctor : clazz.getDeclaredConstructors()) {
            if (ctor.getParameterCount() > 4) {
                target = ctor;
                break;
            }
        }
        if (target == null) {
            throw new NoSuchMethodException("AudioService constructor");
        }
        target.setAccessible(true);
        deoptimize(target);
        hook(target).setPriority(Integer.MAX_VALUE).intercept(chain -> {
            try {
                applyStepCount();
            } catch (Throwable t) {
                log(6, TAG, "applyStepCount failed", t);
            }
            ensureConfigReceiver();
            return chain.proceed();
        });
    }

    private void applyStepCount() {
        if (this.mPatched) {
            return;
        }
        this.mPatched = true;
        Config c = config();
        Object obj = this.mMaxStreamVolume;
        if (!(obj instanceof int[])) {
            return;
        }
        int[] limits = (int[]) obj;
        // 抓一份原生档位，供"关闭模块"时按比例回滚，避免写死 29 档
        this.mStockSteps = limits.clone();
        if (!c.enabled) {
            restoreStockVolumeSettings();
            return;
        }
        for (Integer stream : c.streams) {
            int idx = stream == null ? -1 : stream.intValue();
            if (idx >= 0 && idx < limits.length && c.steps > 0 && limits[idx] != c.steps) {
                log(4, TAG, "stream " + idx + " volume steps: " + limits[idx] + " -> " + c.steps);
                // 只改数组元素，不改 static final 字段的引用（Android 17 限制）
                limits[idx] = c.steps;
            }
        }
    }

    private void hookVolumeStepsProperty(ClassLoader cl) throws Throwable {
        Method getInt = Class.forName("android.os.SystemProperties", false, cl)
                .getDeclaredMethod("getInt", String.class, int.class);
        getInt.setAccessible(true);
        hook(getInt).setPriority(Integer.MAX_VALUE).intercept(chain -> {
            if (PROP_MEDIA_VOL_STEPS.equals(chain.getArg(0))) {
                Config c = config();
                if (c.enabled) {
                    return c.steps;
                }
            }
            return chain.proceed();
        });
    }

    private void hookVolumeStreamState(ClassLoader cl) throws Throwable {
        Class<?> clazz = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl);
        this.fStreamType = field(clazz, "mStreamType");
        this.fIndexMap = field(clazz, "mIndexMap");
        this.fIndexMax = field(clazz, "mIndexMax");

        Method adjustIndex = clazz.getDeclaredMethod("adjustIndex", int.class, int.class, String.class, boolean.class);
        adjustIndex.setAccessible(true);
        try {
            deoptimize(adjustIndex);
        } catch (Throwable t) {
            log(5, TAG, "deoptimize adjustIndex failed: " + t);
        }
        hook(adjustIndex).setPriority(Integer.MAX_VALUE).intercept(this::handleAdjustIndex);
    }

    private void hookAbsoluteSet(ClassLoader cl) throws Throwable {
        Method setIndex = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl)
                .getDeclaredMethod("setIndex", int.class, int.class, String.class, boolean.class);
        setIndex.setAccessible(true);
        hook(setIndex).setPriority(Integer.MAX_VALUE).intercept(chain -> {
            Object self = chain.getThisObject();
            int stream = -1;
            int index = 0;
            int max = 0;
            try {
                stream = this.fStreamType.getInt(self);
                index = (Integer) chain.getArg(0);
                max = this.fIndexMax.getInt(self);
            } catch (Throwable ignored) {
            }
            Config c = config();
            if (IN_KEY_PATH.get() == null && c.enabled && c.snap > 1 && c.streams.contains(stream)) {
                // 吸附配置按「档」计，索引空间是档位的 10 倍
                int snapped = snap(index, c.snap * STEP_UNIT, max);
                if (snapped != index) {
                    if (c.debug) {
                        log(3, TAG, "snap " + index + " -> " + snapped + " (grid=" + c.snap + ")");
                    }
                    return chain.proceed(new Object[]{snapped, chain.getArg(1), chain.getArg(2), chain.getArg(3)});
                }
            }
            return chain.proceed();
        });
    }

    private void hookIndexFactors(ClassLoader cl) throws Throwable {
        Method update = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl)
                .getDeclaredMethod("updateIndexFactors");
        update.setAccessible(true);
        final Field streamField = this.fStreamType;
        final Field maxField = this.fIndexMax;
        hook(update).setPriority(Integer.MIN_VALUE).intercept(chain -> {
            Object result = chain.proceed();
            if (config().debug) {
                try {
                    Object self = chain.getThisObject();
                    log(3, TAG, "updateIndexFactors stream=" + streamField.getInt(self)
                            + " max=" + maxField.getInt(self));
                } catch (Throwable ignored) {
                }
            }
            return result;
        });
    }

    private void hookHalVolume(ClassLoader cl) throws Throwable {
        for (Method method : Class.forName("com.android.server.audio.AudioSystemAdapter", false, cl).getDeclaredMethods()) {
            if ("setStreamVolumeIndexAS".equals(method.getName()) && method.getParameterCount() == 4) {
                method.setAccessible(true);
                hook(method).setPriority(Integer.MIN_VALUE).intercept(chain -> {
                    if (config().debug) {
                        try {
                            log(3, TAG, "HAL stream=" + chain.getArg(0) + " index=" + chain.getArg(1)
                                    + " muted=" + chain.getArg(2) + " device=" + chain.getArg(3));
                        } catch (Throwable ignored) {
                        }
                    }
                    return chain.proceed();
                });
                return;
            }
        }
    }

    /**
     * 把一次音量键的 ±10 换成 ±(fine|coarse * 10)，并按 threshold 决定用哪一档。
     */
    private Object handleAdjustIndex(XposedInterface.Chain chain) throws Throwable {
        Object rawDelta = chain.getArg(0);
        if (!(rawDelta instanceof Integer)) {
            return chain.proceed();
        }
        int delta = (Integer) rawDelta;
        if (delta == 0 || Math.abs(delta) > MAX_DELTA) {
            return chain.proceed();
        }
        Config c = config();
        if (!c.enabled) {
            return chain.proceed();
        }
        Object self = chain.getThisObject();
        int stream = this.fStreamType.getInt(self);
        if (!c.streams.contains(stream)) {
            return chain.proceed();
        }
        Object map = this.fIndexMap.get(self);
        if (!(map instanceof SparseIntArray)) {
            return chain.proceed();
        }
        SparseIntArray indexes = (SparseIntArray) map;
        Object rawDevice = chain.getArg(1);
        if (!(rawDevice instanceof Integer)) {
            return chain.proceed();
        }
        int device = (Integer) rawDevice;
        int current = indexes.get(device, Integer.MIN_VALUE);
        if (current == Integer.MIN_VALUE) {
            current = indexes.get(DEVICE_OUT_DEFAULT, 0);
        }
        int max = this.fIndexMax.getInt(self);
        if (max <= 0) {
            return chain.proceed();
        }
        int threshold = c.threshold * STEP_UNIT;
        boolean fine = delta > 0 ? current < threshold : current <= threshold;
        int step = fine ? c.fine : c.coarse;
        int adjusted = (delta > 0 ? step : -step) * STEP_UNIT;
        if (adjusted == delta) {
            return chain.proceed();
        }
        if (c.debug) {
            log(3, TAG, "step cur=" + current + " delta=" + delta + " -> " + adjusted
                    + " (" + (fine ? "fine" : "coarse") + ")");
        }
        // 按键路径上不要再去吸附，否则 1 档细调会被网格吃掉
        IN_KEY_PATH.set(Boolean.TRUE);
        try {
            return chain.proceed(new Object[]{adjusted, chain.getArg(1), chain.getArg(2), chain.getArg(3)});
        } finally {
            IN_KEY_PATH.remove();
        }
    }

    // ---------------------------------------------------------------- receiver

    private void ensureConfigReceiver() {
        ensureConfigObserver();
        if (this.mReceiverRegistered) {
            return;
        }
        synchronized (this) {
            if (this.mReceiverRegistered) {
                return;
            }
            try {
                registerConfigReceiver();
                this.mReceiverRegistered = true;
            } catch (Throwable t) {
                log(5, TAG, "register config receiver failed: " + t);
            }
        }
    }

    /** 配置变化时立刻失效缓存，替代原来每 800ms 一次的主动查询。 */
    private void ensureConfigObserver() {
        if (this.mConfigObserver != null) {
            return;
        }
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return;
        }
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                mConfig = null;
                mConfigAt = 0L;
            }
        };
        try {
            for (String key : ConfigContract.READ_KEYS) {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
            }
            this.mConfigObserver = observer;
            log(4, TAG, "config observer registered");
        } catch (Throwable t) {
            log(5, TAG, "register config observer failed: " + t);
        }
    }

    private void registerConfigReceiver() {
        Context context = systemContext();
        if (context == null) {
            throw new IllegalStateException("no system context");
        }
        try {
            this.mModuleUid = getModuleApplicationInfo().uid;
        } catch (Throwable ignored) {
        }
        IntentFilter filter = new IntentFilter(ConfigContract.ACTION_CONFIG);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                try {
                    if (isFromModule(this, intent)) {
                        persist(intent);
                    } else {
                        log(5, TAG, "ignored config broadcast from untrusted sender");
                    }
                } catch (Throwable t) {
                    log(5, TAG, "apply config broadcast failed: " + t);
                }
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
        log(4, TAG, "config receiver registered, moduleUid=" + this.mModuleUid);
    }

    private void startReceiverRetry() {
        Thread thread = new Thread(() -> {
            for (int i = 0; i < 90 && !this.mReceiverRegistered; i++) {
                try {
                    Thread.sleep(1000L);
                    ensureConfigReceiver();
                } catch (InterruptedException e) {
                    return;
                }
            }
            if (!this.mReceiverRegistered) {
                log(5, TAG, "config receiver still missing after retry window");
            }
        }, "VolumeFineCfg");
        thread.setDaemon(true);
        thread.start();
    }

    private boolean isFromModule(BroadcastReceiver receiver, Intent intent) {
        if (this.mModuleUid <= 0) {
            return true;
        }
        try {
            Method method = this.mSentFromUid;
            if (method == null) {
                method = BroadcastReceiver.class.getMethod("getSentFromUid");
                this.mSentFromUid = method;
            }
            Object uid = method.invoke(receiver);
            if (uid instanceof Integer) {
                int value = (Integer) uid;
                if (config().debug) {
                    log(3, TAG, "config broadcast from uid=" + value + " (module=" + this.mModuleUid + ")");
                }
                if (value > 0) {
                    return value == this.mModuleUid;
                }
            }
        } catch (Throwable ignored) {
        }
        return true;
    }

    private void persist(Intent intent) {
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return;
        }
        StringBuilder written = new StringBuilder();
        for (String key : ConfigContract.PERSIST_KEYS) {
            if (!intent.hasExtra(key)) {
                continue;
            }
            int value = ConfigContract.clamp(key, intent.getIntExtra(key, ConfigContract.def(key)));
            try {
                Settings.System.putInt(resolver, key, value);
                written.append(key).append('=').append(value).append(' ');
            } catch (Throwable t) {
                log(5, TAG, "write " + key + " failed: " + t);
            }
        }
        this.mConfig = null;
        log(4, TAG, "config saved: " + written);
    }

    // ---------------------------------------------------------------- restore

    /**
     * 关闭模块时把放大的音量值缩回原生档位。
     * 只处理本模块真正改过 max 的流（默认只有 music），且只处理明显超出原生上限的值，
     * 避免旧版本"扫全表 volume_*"的误伤。
     */
    private void restoreStockVolumeSettings() {
        Config c = config();
        int applied = c.steps;
        if (applied <= 0) {
            return;
        }
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return;
        }
        Map<String, Integer> prefixes = streamSettingPrefixes(c.streams);
        if (prefixes.isEmpty()) {
            return;
        }
        List<String> names = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        Cursor cursor = null;
        try {
            cursor = resolver.query(Settings.System.CONTENT_URI, new String[]{"name", "value"},
                    "name LIKE ?", new String[]{"volume_%"}, null);
            while (cursor != null && cursor.moveToNext()) {
                String name = cursor.getString(0);
                String raw = cursor.getString(1);
                if (name == null || raw == null) {
                    continue;
                }
                int stream = matchStream(prefixes, name);
                if (stream < 0) {
                    continue;
                }
                try {
                    int value = Integer.parseInt(raw.trim());
                    int stock = stockSteps(stream);
                    if (stock > 0 && stock != applied && value > stock) {
                        int scaled = Math.max(1, Math.round(value * (stock / (float) applied)));
                        names.add(name);
                        values.add(Math.min(scaled, stock));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "restore scan failed: " + t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        for (int i = 0; i < names.size(); i++) {
            try {
                Settings.System.putInt(resolver, names.get(i), values.get(i));
                log(4, TAG, "restore " + names.get(i) + " -> " + values.get(i));
            } catch (Throwable t) {
                log(5, TAG, "restore " + names.get(i) + " failed: " + t);
            }
        }
    }

    private Map<String, Integer> streamSettingPrefixes(Set<Integer> streams) {
        Map<String, Integer> map = new HashMap<>();
        map.put("volume_voice", AudioManager.STREAM_VOICE_CALL);
        map.put("volume_system", AudioManager.STREAM_SYSTEM);
        map.put("volume_ring", AudioManager.STREAM_RING);
        map.put("volume_music", AudioManager.STREAM_MUSIC);
        map.put("volume_alarm", AudioManager.STREAM_ALARM);
        map.put("volume_notification", AudioManager.STREAM_NOTIFICATION);
        map.put("volume_bluetooth_sco", 6 /* STREAM_BLUETOOTH_SCO，SDK 中为隐藏常量 */);
        map.put("volume_dtmf", AudioManager.STREAM_DTMF);
        map.put("volume_accessibility", AudioManager.STREAM_ACCESSIBILITY);
        Map<String, Integer> filtered = new HashMap<>();
        for (Map.Entry<String, Integer> entry : map.entrySet()) {
            if (streams.contains(entry.getValue())) {
                filtered.put(entry.getKey(), entry.getValue());
            }
        }
        return filtered;
    }

    private static int matchStream(Map<String, Integer> prefixes, String name) {
        String best = null;
        for (String prefix : prefixes.keySet()) {
            if (name.startsWith(prefix) && (best == null || prefix.length() > best.length())) {
                best = prefix;
            }
        }
        return best == null ? -1 : prefixes.get(best);
    }

    private int stockSteps(int stream) {
        if (stream == AudioManager.STREAM_MUSIC) {
            int prop = readPropertyInt(PROP_MEDIA_VOL_STEPS, 0);
            if (prop > 0) {
                return prop;
            }
        }
        int[] stock = this.mStockSteps;
        if (stock != null && stream >= 0 && stream < stock.length && stock[stream] > 0) {
            return stock[stream];
        }
        return stream == AudioManager.STREAM_MUSIC ? 29 : 0;
    }

    private int readPropertyInt(String key, int fallback) {
        try {
            Class<?> cls = Class.forName("android.os.SystemProperties");
            Method get = cls.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            Object raw = get.invoke(null, key);
            if (raw instanceof String) {
                return Integer.parseInt(((String) raw).trim());
            }
        } catch (Throwable ignored) {
        }
        return fallback;
    }

    // ---------------------------------------------------------------- utils

    private ContentResolver resolver() {
        ContentResolver cached = this.mResolver;
        if (cached != null) {
            return cached;
        }
        Context context = systemContext();
        if (context == null) {
            return null;
        }
        this.mResolver = context.getContentResolver();
        return this.mResolver;
    }

    private Context systemContext() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object thread = cls.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            return (Context) cls.getMethod("getSystemContext").invoke(thread);
        } catch (Throwable t) {
            log(5, TAG, "cannot get system context: " + t);
            return null;
        }
    }

    private static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static int snap(int index, int grid, int max) {
        if (grid <= 1) {
            return index;
        }
        int rounded = Math.round(index / (float) grid) * grid;
        if (rounded < 0) {
            rounded = 0;
        }
        return Math.min(rounded, max);
    }
}
