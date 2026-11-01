package cn.moonflow.easytier;


public final class NativeEasyTier {
    static {
        System.loadLibrary("easytier_ffi");
        System.loadLibrary("moontier_native_bridge");
    }

    private NativeEasyTier() {}

    public static native void setLogLevel(String level);
    public static native int parseConfig(String config);
    public static native int runNetworkInstance(String config);
    public static native int retainNetworkInstance(String[] instanceNames);
    public static native String collectNetworkInfos(int maxLength);
    public static native String sharedTunConfig();
    public static native int attachSharedTunFd(int fd);
    public static native int setTunFd(String instanceName, int fd);
    public static native int runNetworkInstanceWithSource(String config, int source);
    public static native int setNetworkInstanceEnabled(String instanceId, boolean enabled);
    public static native String collectRuntimeConfigs();
    public static native int restoreRuntimeConfigs(String configs);
    public static native String listInstances(int maxLength);
    public static native int deleteNetworkInstance(String instanceName);
    public static native String callJsonRpc(String service, String method, String domain, String payload);
    public static native int startConfigServerClient(String url, String hostname, String machineId, boolean secureMode, long generation);
    public static native int detachConfigServerClient();
    public static native int stopConfigServerClient();
    public static native boolean isConfigServerClientConnected();
    public static native String drainConfigServerEvents();
    public static native void setRuntimeEventsEnabled(boolean enabled);
    public static native String getLastError();
    public static native void trimNativeHeap();

    private static volatile Runnable eventListener;

    public static void setEventListener(Runnable listener) { eventListener = listener; }

    // Called by the native callback. The listener only schedules a snapshot refresh.
    public static void onNativeEventAvailable() {
        Runnable listener = eventListener;
        if (listener != null) listener.run();
    }

    public static int stopAllInstances() {
        return retainNetworkInstance(null);
    }

}
