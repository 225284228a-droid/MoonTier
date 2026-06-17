package cn.moonflow.easytier;

import android.util.Log;

public final class NativeEasyTier {
    static {
        System.loadLibrary("easytier_ffi");
        System.loadLibrary("moontier_native_bridge");
    }

    private NativeEasyTier() {}

    public static native int parseConfig(String config);
    public static native int runNetworkInstance(String config);
    public static native int retainNetworkInstance(String[] instanceNames);
    public static native String collectNetworkInfos(int maxLength);
    public static native int setTunFd(String instanceName, int fd);
    public static native int startConfigServerClient(String url, String hostname, String machineId, boolean secureMode);
    public static native int stopConfigServerClient();
    public static native int isConfigServerClientConnected();
    public static native boolean hasConfigServerClient();
    public static native String getLastError();

    public static int stopAllInstances() {
        return retainNetworkInstance(null);
    }

    // --- Config server callback ---

    public interface ConfigServerCallback {
        void onEvent(String eventJson);
    }

    private static volatile ConfigServerCallback configServerCallback;

    public static void setConfigServerCallback(ConfigServerCallback cb) {
        configServerCallback = cb;
    }

    public static boolean hasConfigServerCallback() {
        return configServerCallback != null;
    }

    // Called from native code on a worker thread
    public static void dispatchConfigServerEvent(String eventJson) {
        ConfigServerCallback cb = configServerCallback;
        if (cb != null) {
            try {
                cb.onEvent(eventJson);
            } catch (Exception e) {
                Log.e("MoonTierNtv", "config server callback error", e);
            }
        }
    }
}