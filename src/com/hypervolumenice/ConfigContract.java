package com.hypervolumenice;

/**
 * 配置契约：键名、默认值、范围与生效方式。
 * App 侧（设置页）与 system_server 侧（模块）共用同一份定义，避免两边漂移。
 */
final class ConfigContract {

    private ConfigContract() {
    }

    static final String ACTION_CONFIG = "com.hypervolumenice.action.CONFIG";

    static final String KEY_ENABLED = "volume_fine_enabled";
    static final String KEY_STEPS = "volume_fine_steps";
    static final String KEY_THRESHOLD = "volume_fine_threshold";
    static final String KEY_FINE = "volume_fine_fine";
    static final String KEY_COARSE = "volume_fine_coarse";
    static final String KEY_SNAP = "volume_fine_snap";
    static final String KEY_DEBUG = "volume_fine_debug";
    static final String KEY_STREAMS = "volume_fine_streams";
    /** 默认值版本，用于把 2.x 的旧默认值一次性迁到 3.1 的新默认值。 */
    static final String KEY_DEFAULTS_VERSION = "volume_fine_defaults_version";
    static final int DEFAULTS_VERSION = 2;

    /** 允许经广播写入的键，顺序即设置页的展示顺序。 */
    static final String[] WRITE_KEYS = {
            KEY_ENABLED, KEY_STEPS, KEY_THRESHOLD, KEY_FINE, KEY_COARSE, KEY_SNAP, KEY_DEBUG
    };

    /** 模块侧 persist() 的白名单（比 UI 多一个默认值版本号）。 */
    static final String[] PERSIST_KEYS = {
            KEY_ENABLED, KEY_STEPS, KEY_THRESHOLD, KEY_FINE, KEY_COARSE, KEY_SNAP,
            KEY_DEBUG, KEY_DEFAULTS_VERSION
    };

    static final String[] READ_KEYS = {
            KEY_ENABLED, KEY_STEPS, KEY_THRESHOLD, KEY_FINE, KEY_COARSE, KEY_SNAP,
            KEY_DEBUG, KEY_STREAMS, KEY_DEFAULTS_VERSION
    };

    static final int DEF_ENABLED = 1;
    static final int DEF_STEPS = 60;
    static final int DEF_THRESHOLD = 12;
    static final int DEF_FINE = 1;
    static final int DEF_COARSE = 3;
    static final int DEF_SNAP = 1;
    static final int DEF_DEBUG = 0;
    static final String DEF_STREAMS = "3";

    /** 2.x 的出厂值，用来判断用户是不是"从未改过"。 */
    static final int LEGACY_DEF_STEPS = 100;
    static final int LEGACY_DEF_THRESHOLD = 30;
    static final int LEGACY_DEF_COARSE = 4;
    static final int LEGACY_DEF_SNAP = 2;

    static int def(String key) {
        switch (key) {
            case KEY_ENABLED:
                return DEF_ENABLED;
            case KEY_STEPS:
                return DEF_STEPS;
            case KEY_THRESHOLD:
                return DEF_THRESHOLD;
            case KEY_FINE:
                return DEF_FINE;
            case KEY_COARSE:
                return DEF_COARSE;
            case KEY_SNAP:
                return DEF_SNAP;
            default:
                return DEF_DEBUG;
        }
    }

    static int clamp(String key, int value) {
        switch (key) {
            case KEY_ENABLED:
            case KEY_DEBUG:
                return value == 0 ? 0 : 1;
            case KEY_STEPS:
                return clampInt(value, 15, 150);
            case KEY_THRESHOLD:
                return clampInt(value, 0, 150);
            case KEY_FINE:
                return clampInt(value, 1, 6);
            case KEY_COARSE:
                return clampInt(value, 1, 10);
            case KEY_SNAP:
                return clampInt(value, 1, 6);
            default:
                return value;
        }
    }

    /** 这两个参数在 AudioService 构造时才会被消费，改动后必须重启手机。 */
    static boolean needsReboot(String key) {
        return KEY_ENABLED.equals(key) || KEY_STEPS.equals(key);
    }

    static int clampInt(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    static String adb(String key, int value) {
        return "adb shell settings put system " + key + " " + value;
    }
}
