package com.hypervolumenice;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedInterface.HookHandle;
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

    private Field fIndexMap;
    private Field fIndexMax;
    private Field fStreamType;
    private Object mMaxStreamVolume;
    private int[] mStockSteps;
    /** 实际写入 MAX_STREAM_VOLUME 的档位值，可能与 c.steps 不同（如启动期用了默认兜底）。
     *  回滚必须用它而非配置值，否则缩放比例错误。-1 表示尚未成功应用。 */
    private volatile int mAppliedSteps = -1;
    private int mModuleUid = -1;
    private boolean mPatched;
    private volatile boolean mReceiverRegistered;
    private ContentResolver mResolver;
    /** 后台单线程：负责查库刷新配置，绝不占用 Hook 回调线程。 */
    private final ExecutorService mConfigExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VolumeFineCfgLoad");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean mRefreshQueued = new AtomicBoolean(false);
    /** 全局属性 Hook 的句柄；仅在启动窗口保留，构造完成后卸载。 */
    private volatile HookHandle mStepsHook;
    /** 唯一的可变引用：整体替换不可变 Config，读侧一次 volatile 读拿到一致快照。 */
    private volatile Config mConfig;
    private ContentObserver mConfigObserver;
    private Method mSentFromUid;

    /** 不可变配置快照；字段 final， streams 用不可变集合，发布后不再改动。 */
    private static final class Config {
        final boolean enabled;
        final int steps;
        final int threshold;
        final int fine;
        final int coarse;
        final boolean debug;
        final Set<Integer> streams;

        Config(boolean enabled, int steps, int threshold, int fine, int coarse,
               boolean debug, Set<Integer> streams) {
            this.enabled = enabled;
            this.steps = steps;
            this.threshold = threshold;
            this.fine = fine;
            this.coarse = coarse;
            this.debug = debug;
            this.streams = streams;
        }
    }

    // ---------------------------------------------------------------- config

    /**
     * Hook 热路径专用：优先一次 volatile 读；仅在缓存尚未成功加载时回源一次。
     * 配置在启动期加载，之后由 ContentObserver / 广播写入触发后台刷新。
     */
    private Config config() {
        Config c = this.mConfig;
        if (c != null) {
            return c;
        }
        Config loaded = loadConfigNow();
        // 启动早期可能拿不到 system context；此时返回不查库的默认值兜底，且缓存仍为 null，
        // 让后续 Hook 回调在 context 就绪后重试查库，避免把错误默认值固化一整个开机周期。
        return loaded != null ? loaded : DEFAULT_CONFIG;
    }

    /** 不查库的默认配置，仅用于启动早期 context 未就绪的极端兜底。 */
    private static final Config DEFAULT_CONFIG = new Config(
            ConfigContract.DEF_ENABLED != 0, ConfigContract.DEF_STEPS,
            ConfigContract.DEF_THRESHOLD, ConfigContract.DEF_FINE,
            ConfigContract.DEF_COARSE,
            ConfigContract.DEF_DEBUG != 0,
            Collections.singleton(android.media.AudioManager.STREAM_MUSIC));

    /** 从 Settings 读取快照并构造不可变 Config。只在启动线程 / 后台线程调用。 */
    private Config loadConfigNow() {
        // resolver 拿不到时（启动早期 context 未就绪）不要发布默认配置，
        // 否则 mConfig 一旦非 null 就再也不会回源，用户自定义配置整次开机失效。
        if (resolver() == null) {
            return null;
        }
        Map<String, String> snapshot = snapshotSettings();
        // Settings 未就绪（空读）时不要固化默认配置：保留 mConfig=null，
        // 让后续 Hook 回调 / 定时补偿在 provider 就绪后重试，避免整机停在默认行为。
        if (snapshot == null) {
            return null;
        }
        Config c = parseConfig(snapshot);
        this.mConfig = c;
        return c;
    }

    private Config parseConfig(Map<String, String> snapshot) {
        boolean enabled = intOf(snapshot, ConfigContract.KEY_ENABLED, ConfigContract.DEF_ENABLED) != 0;
        int steps = ConfigContract.clamp(ConfigContract.KEY_STEPS,
                intOf(snapshot, ConfigContract.KEY_STEPS, ConfigContract.DEF_STEPS));
        int threshold = ConfigContract.clamp(ConfigContract.KEY_THRESHOLD,
                intOf(snapshot, ConfigContract.KEY_THRESHOLD, ConfigContract.DEF_THRESHOLD));
        int fine = ConfigContract.clamp(ConfigContract.KEY_FINE,
                intOf(snapshot, ConfigContract.KEY_FINE, ConfigContract.DEF_FINE));
        int coarse = ConfigContract.clamp(ConfigContract.KEY_COARSE,
                intOf(snapshot, ConfigContract.KEY_COARSE, ConfigContract.DEF_COARSE));
        boolean debug = intOf(snapshot, ConfigContract.KEY_DEBUG, ConfigContract.DEF_DEBUG) != 0;
        String streams = snapshot.containsKey(ConfigContract.KEY_STREAMS)
                ? snapshot.get(ConfigContract.KEY_STREAMS) : ConfigContract.DEF_STREAMS;
        Set<Integer> parsed = parseStreams(streams);
        return new Config(enabled, steps, threshold, fine, coarse, debug,
                Collections.unmodifiableSet(parsed));
    }

    /**
     * 配置就绪后重算档位：AudioService 构造期若因配置未就绪而跳过应用，
     * 这里在真配置加载成功后补做，修复"档位数停留在默认值"的问题。
     * 仅在 mStockSteps 已抓取（即构造函数已 hook）后才有意义。
     */
    private void reapplyStepsIfNeeded() {
        // 以 mMaxStreamVolume 是否就绪判断构造函数是否已 hook；不能用 mStockSteps，
        // 因为构造期配置未就绪时 applyStepCount 提前返回，mStockSteps 恰好还是 null。
        if (this.mMaxStreamVolume == null) {
            return;
        }
        try {
            applyStepCount();
        } catch (Throwable t) {
            log(5, TAG, "reapply steps failed: " + t);
        }
    }

    /**
     * 请求后台刷新配置。合并连续事件（观察者对每个 key 各回调一次），
     * 且整段查库在单线程后台执行，不占用 system_server 的 Binder / 音频线程。
     */
    private void requestConfigRefresh() {
        if (!this.mRefreshQueued.compareAndSet(false, true)) {
            return;
        }
        try {
            this.mConfigExecutor.execute(() -> {
                try {
                    Config loaded = loadConfigNow();
                    if (loaded != null) {
                        reapplyStepsIfNeeded();
                    }
                    log(4, TAG, "config refreshed in background");
                } catch (Throwable t) {
                    log(5, TAG, "background config refresh failed: " + t);
                } finally {
                    this.mRefreshQueued.set(false);
                }
            });
        } catch (Throwable t) {
            this.mRefreshQueued.set(false);
            log(5, TAG, "schedule config refresh failed: " + t);
        }
    }

    /**
     * 返回 null 表示 SettingsProvider 尚未就绪；空 Map 表示已就绪但用户从未配置。
     * 二者必须区分：早期空读若被当作"已配置默认值"固化，整次开机都会停在默认行为。
     */
    private Map<String, String> snapshotSettings() {
        ContentResolver resolver = resolver();
        if (resolver == null) {
            return null;
        }
        Cursor cursor = null;
        boolean queried = false;
        try {
            cursor = resolver.query(Settings.System.CONTENT_URI, new String[]{"name", "value"},
                    "name LIKE ?", new String[]{"volume_fine_%"}, null);
            if (cursor != null) {
                queried = true;
                HashMap<String, String> map = new HashMap<>();
                while (cursor.moveToNext()) {
                    map.put(cursor.getString(0), cursor.getString(1));
                }
                if (!map.isEmpty()) {
                    return map;
                }
            }
        } catch (Throwable t) {
            log(5, TAG, "snapshotSettings query failed, fallback: " + t);
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable ignored) {
                }
            }
        }
        // LIKE 查询失败时降级为逐键读取；若查询根本没成功且逐条也全空，视为 Settings 未就绪。
        Map<String, String> fallback = readKeysOneByOne(resolver);
        if (!queried && fallback.isEmpty()) {
            return null;
        }
        return fallback;
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
        // 启动期同步加载一次配置：此后所有 Hook 回调的 config() 都只读内存快照。
        try {
            loadConfigNow();
        } catch (Throwable t) {
            log(6, TAG, "initial config load failed: " + t);
        }
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
        // 这两个 Hook 只输出调试日志，默认 debug 关闭时安装它们是纯开销：
        // updateIndexFactors / setStreamVolumeIndexAS 都在音频热路径上，每次调用
        // 都要过一次 Hook 框架。仅当 debug 打开时才安装；注意 debug 在启动期读取，
        // 运行中切换需重启才会挂载/卸载这两个日志 Hook。
        if (config().debug) {
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
        }
        ensureConfigReceiver();
        startReceiverRetry();
        // 兜底补偿：LKM/内核更新后启动时序会变化，SettingsProvider 可能在模块注入之后才就绪。
        // 分时重试直到读到真实配置，避免整机停留在默认行为、需要用户手动进设置页保存才恢复。
        scheduleConfigCatchUp();
        log(4, TAG, "hooks installed, api=" + getApiVersion()
                + ", framework=" + getFrameworkName() + " " + getFrameworkVersion());
    }

    /**
     * 启动后分时补偿加载配置：一旦 mConfig 成功建立即停止。
     * 覆盖"模块注入早于 SettingsProvider 就绪"的时序，确保用户配置在开机后自动生效。
     */
    private void scheduleConfigCatchUp() {
        Thread t = new Thread(() -> {
            long[] delays = {1000L, 3000L, 7000L, 15000L};
            for (long d : delays) {
                if (this.mConfig != null) {
                    return;
                }
                try {
                    Thread.sleep(d);
                } catch (InterruptedException e) {
                    return;
                }
                Config loaded = loadConfigNow();
                if (loaded != null) {
                    // 真配置到位后补算档位：修复构造期因配置未就绪而按默认值改刻度的问题。
                    reapplyStepsIfNeeded();
                    log(4, TAG, "config catch-up loaded after " + d + "ms");
                    return;
                }
            }
            log(5, TAG, "config still unavailable after catch-up window");
        }, "VolumeFineCfgCatchUp");
        t.setDaemon(true);
        t.start();
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
            // 先执行原生构造，让 VolumeStreamState 完成初始化并读取 ro.config.media_vol_steps，
            // 之后才启动卸载计时。若计时从 proceed 之前开始，构造耗时超过窗口时属性 Hook
            // 会被提前卸载，导致"数组已改、属性仍是原生值"的档位不一致。
            Object result = chain.proceed();
            scheduleStepsHookUnhook();
            return result;
        });
    }

    private void applyStepCount() {
        // 只在拿到真实配置后才应用并置位：启动早期 config() 可能返回默认兜底，
        // 若此时就按默认值改档位并置 mPatched，本次开机会一直停在错误档位。
        Config c = this.mConfig;
        if (c == null) {
            return;
        }
        Object obj = this.mMaxStreamVolume;
        if (!(obj instanceof int[])) {
            return;
        }
        int[] limits = (int[]) obj;
        // 抓一份原生档位，供"关闭模块"时按比例回滚，避免写死 29 档（只抓一次）
        if (this.mStockSteps == null) {
            this.mStockSteps = limits.clone();
        }
        if (!c.enabled) {
            if (!this.mPatched) {
                this.mPatched = true;
                restoreStockVolumeSettings();
            }
            return;
        }
        // 档位已按当前配置应用过则不重复写数组（补偿/观察者刷新可能再次触发）
        if (this.mPatched && this.mAppliedSteps == c.steps) {
            return;
        }
        for (Integer stream : c.streams) {
            int idx = stream == null ? -1 : stream.intValue();
            if (idx >= 0 && idx < limits.length && c.steps > 0) {
                if (limits[idx] != c.steps) {
                    log(4, TAG, "stream " + idx + " volume steps: " + limits[idx] + " -> " + c.steps);
                    // 只改数组元素，不改 static final 字段的引用（Android 17 限制）
                    limits[idx] = c.steps;
                }
            }
        }
        this.mAppliedSteps = c.steps;
        this.mPatched = true;
    }

    private void hookVolumeStepsProperty(ClassLoader cl) throws Throwable {
        Method getInt = Class.forName("android.os.SystemProperties", false, cl)
                .getDeclaredMethod("getInt", String.class, int.class);
        getInt.setAccessible(true);
        this.mStepsHook = hook(getInt).setPriority(Integer.MAX_VALUE).intercept(chain -> {
            if (PROP_MEDIA_VOL_STEPS.equals(chain.getArg(0))) {
                Config c = config();
                if (c.enabled) {
                    return c.steps;
                }
            }
            return chain.proceed();
        });
    }

    /** 延迟卸载全局属性 Hook：只保留启动窗口，运行期彻底移除这层全局开销。 */
    private void scheduleStepsHookUnhook() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000L);
                HookHandle handle = this.mStepsHook;
                if (handle != null) {
                    handle.unhook();
                    this.mStepsHook = null;
                    log(4, TAG, "media_vol_steps property hook unhooked (startup window closed)");
                }
            } catch (Throwable th) {
                log(5, TAG, "unhook property hook failed: " + th);
            }
        }, "VolumeFineUnhook");
        t.setDaemon(true);
        t.start();
    }

    private void hookVolumeStreamState(ClassLoader cl) throws Throwable {
        Class<?> clazz = Class.forName("com.android.server.audio.AudioService$VolumeStreamState", false, cl);
        this.fStreamType = field(clazz, "mStreamType");
        this.fIndexMap = field(clazz, "mIndexMap");
        this.fIndexMax = field(clazz, "mIndexMax");

        Method adjustIndex = clazz.getDeclaredMethod("adjustIndex", int.class, int.class, String.class, boolean.class);
        adjustIndex.setAccessible(true);
        // 保留 deoptimize：防止 adjustIndex 被 JIT 内联后本 Hook 静默失效。
        // 它带来的解释执行开销，远小于"音量调节整个不生效"的风险；未经真机验证请勿移除。
        try {
            deoptimize(adjustIndex);
        } catch (Throwable t) {
            log(5, TAG, "deoptimize adjustIndex failed: " + t);
        }
        hook(adjustIndex).setPriority(Integer.MAX_VALUE).intercept(this::handleAdjustIndex);
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
            current = indexes.get(DEVICE_OUT_DEFAULT, Integer.MIN_VALUE);
        }
        // 当前档位未知时不要用 0 充当：会把高音量区误判成细调区、放大步进甚至打到 0 档。
        // 索引未知就让系统原生逻辑处理，本模块不介入。
        if (current == Integer.MIN_VALUE) {
            return chain.proceed();
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
        return chain.proceed(new Object[]{adjusted, chain.getArg(1), chain.getArg(2), chain.getArg(3)});
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
                // 不再置空缓存（那会让 Hook 热路径回源查库），改为后台线程刷新。
                requestConfigRefresh();
            }
        };
        try {
            for (String key : ConfigContract.READ_KEYS) {
                resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
            }
            this.mConfigObserver = observer;
            log(4, TAG, "config observer registered");
            // 观察者注册成功说明 resolver 可用；立即补一次刷新，纠正启动早期可能的空读缓存。
            requestConfigRefresh();
        } catch (Throwable t) {
            log(5, TAG, "register config observer failed: " + t);
        }
    }

    private void registerConfigReceiver() {
        Context context = systemContext();
        if (context == null) {
            throw new IllegalStateException("no system context");
        }
        // 模块 UID 取不到会让 fail-closed 误杀合法广播。优先用 LSPosed 提供的信息，
        // 失败时回退到 PackageManager 按包名查询（system_server 里稳定可用）。
        try {
            ApplicationInfo ai = getModuleApplicationInfo();
            if (ai != null) {
                this.mModuleUid = ai.uid;
            }
        } catch (Throwable ignored) {
        }
        if (this.mModuleUid <= 0) {
            try {
                ApplicationInfo ai = context.getPackageManager()
                        .getApplicationInfo("com.hypervolumenice", 0);
                if (ai != null) {
                    this.mModuleUid = ai.uid;
                }
            } catch (Throwable ignored) {
            }
            log(4, TAG, "module uid resolved via PM: " + this.mModuleUid);
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
        // 主校验：预共享令牌。App 与模块同源编译，能带对令牌即可确认是模块自身发出，
        // 不依赖运行期的 getModuleApplicationInfo()/getSentFromUid()（这些在启动早期
        // 或定制 ROM 上常失败，是上一版 fail-closed 误杀合法广播的根因）。
        if (ConfigContract.AUTH_TOKEN.equals(intent.getStringExtra(ConfigContract.EXTRA_AUTH))) {
            return true;
        }
        // 兼容回退：无令牌的旧版 App，仍用发送方 UID 校验。
        if (this.mModuleUid <= 0) {
            log(5, TAG, "reject config broadcast: no valid token and module uid unknown");
            return false;
        }
        int senderUid = senderUid(receiver);
        if (config().debug) {
            log(3, TAG, "config broadcast (no token) from uid=" + senderUid + " (module=" + this.mModuleUid + ")");
        }
        if (senderUid <= 0) {
            log(5, TAG, "reject config broadcast: sender uid unknown");
            return false;
        }
        return senderUid == this.mModuleUid;
    }

    /** 取发送方 UID：API 31+ 用 getSentFromUid，低版本回退隐藏的 getSendingUid。 */
    private int senderUid(BroadcastReceiver receiver) {
        try {
            Method method = this.mSentFromUid;
            if (method == null) {
                try {
                    method = BroadcastReceiver.class.getMethod("getSentFromUid");
                } catch (NoSuchMethodException lowApi) {
                    method = BroadcastReceiver.class.getDeclaredMethod("getSendingUid");
                }
                method.setAccessible(true);
                this.mSentFromUid = method;
            }
            Object uid = method.invoke(receiver);
            if (uid instanceof Integer) {
                return (Integer) uid;
            }
        } catch (Throwable t) {
            log(5, TAG, "resolve sender uid failed: " + t);
        }
        return -1;
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
        // 写入后立即后台刷新；设置页的回读校验走自己的 ContentResolver，不依赖这里。
        requestConfigRefresh();
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
        // 用实际写入 MAX_STREAM_VOLUME 的档位作为回滚比例的分母。
        // 若启动期因配置未就绪而按默认值应用（或从未应用），c.steps 可能与真实生效值不同，
        // 直接用它会导致缩放比例错误。优先取 mAppliedSteps，无效时再退回配置值。
        int applied = this.mAppliedSteps > 0 ? this.mAppliedSteps : c.steps;
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

}
