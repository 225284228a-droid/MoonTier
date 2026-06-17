#include <jni.h>
#include <algorithm>
#include <cstdlib>
#include <dlfcn.h>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>

extern "C" {
struct KeyValuePair {
    const char *key;
    const char *value;
};

int parse_config(const char *cfg_str);
int run_network_instance(const char *cfg_str);
int retain_network_instance(const char **inst_names, size_t length);
int collect_network_infos(KeyValuePair *infos, size_t max_length);
int set_tun_fd(const char *inst_name, int fd);
void get_error_msg(const char **out);
void free_string(const char *s);
}

namespace {

std::mutex ffiMutex;
std::string bridgeError;
JavaVM *gJvm = nullptr;
jclass gNativeEasyTierClass = nullptr;
jmethodID gDispatchMethod = nullptr;

using ConfigServerEventCallback = void (*)(const char *, void *);
using StartConfigServerClientFn = int (*)(const char *, const char *, const char *, bool, ConfigServerEventCallback, void *);
using StopConfigServerClientFn = int (*)();
using IsConfigServerClientConnectedFn = int (*)();

template <typename T>
T resolveSymbol(const char *name)
{
    return reinterpret_cast<T>(dlsym(RTLD_DEFAULT, name));
}

void setBridgeError(const std::string &message)
{
    bridgeError = message;
}

std::string toString(JNIEnv *env, jstring value)
{
    if (!value)
        return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars)
        env->ReleaseStringUTFChars(value, chars);
    return out;
}

jstring toJString(JNIEnv *env, const std::string &value)
{
    return env->NewStringUTF(value.c_str());
}

std::string jsonEscape(const char *raw)
{
    std::string out;
    if (!raw)
        return out;
    for (const char *p = raw; *p; ++p) {
        switch (*p) {
        case '\\': out += "\\\\"; break;
        case '"': out += "\\\""; break;
        case '\n': out += "\\n"; break;
        case '\r': out += "\\r"; break;
        case '\t': out += "\\t"; break;
        default: out += *p; break;
        }
    }
    return out;
}

// --- JNI callback dispatcher ---

void configServerEventCallbackC(const char *eventJson, void * /*userData*/)
{
    if (!gJvm || !eventJson)
        return;

    JNIEnv *env = nullptr;
    bool needDetach = false;
    int getEnvStat = gJvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);

    if (getEnvStat == JNI_EDETACHED) {
        if (gJvm->AttachCurrentThread(&env, nullptr) != JNI_OK)
            return;
        needDetach = true;
    } else if (getEnvStat != JNI_OK || !env) {
        return;
    }

    jclass cls = gNativeEasyTierClass;
    jmethodID mid = gDispatchMethod;
    if (cls && mid) {
        jstring jEvent = env->NewStringUTF(eventJson);
        if (jEvent) {
            env->CallStaticVoidMethod(cls, mid, jEvent);
            env->DeleteLocalRef(jEvent);
        }
    }

    if (needDetach)
        gJvm->DetachCurrentThread();
}

} // namespace

// --- JNI_OnLoad ---

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
{
    gJvm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK)
        return JNI_VERSION_1_6;

    jclass localCls = env->FindClass("cn/moonflow/easytier/NativeEasyTier");
    if (localCls) {
        gNativeEasyTierClass = static_cast<jclass>(env->NewGlobalRef(localCls));
        env->DeleteLocalRef(localCls);

        gDispatchMethod = env->GetStaticMethodID(
            gNativeEasyTierClass,
            "dispatchConfigServerEvent",
            "(Ljava/lang/String;)V");
    }

    return JNI_VERSION_1_6;
}

// --- parseConfig ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_parseConfig(JNIEnv *env, jclass, jstring config)
{
    const std::string cfg = toString(env, config);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return parse_config(cfg.c_str());
}

// --- runNetworkInstance ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_runNetworkInstance(JNIEnv *env, jclass, jstring config)
{
    const std::string cfg = toString(env, config);
    setenv("ET_CONSOLE_LOG_LEVEL", "info", 1);
    setenv("RUST_LOG", "CORE=info,easytier=info", 1);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return run_network_instance(cfg.c_str());
}

// --- retainNetworkInstance ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_retainNetworkInstance(JNIEnv *env, jclass, jobjectArray names)
{
    if (!names) {
        std::lock_guard<std::mutex> lock(ffiMutex);
        return retain_network_instance(nullptr, 0);
    }

    const jsize count = env->GetArrayLength(names);
    if (count <= 0) {
        std::lock_guard<std::mutex> lock(ffiMutex);
        return retain_network_instance(nullptr, 0);
    }

    std::vector<std::string> owned;
    std::vector<const char *> ptrs;
    owned.reserve(static_cast<size_t>(count));
    ptrs.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(names, i));
        owned.push_back(toString(env, item));
        env->DeleteLocalRef(item);
    }
    for (const std::string &item : owned)
        ptrs.push_back(item.c_str());

    std::lock_guard<std::mutex> lock(ffiMutex);
    return retain_network_instance(ptrs.data(), ptrs.size());
}

// --- collectNetworkInfos ---

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_collectNetworkInfos(JNIEnv *env, jclass, jint maxLength)
{
    if (maxLength <= 0)
        return toJString(env, "{}");

    std::vector<KeyValuePair> infos(static_cast<size_t>(maxLength));
    std::lock_guard<std::mutex> lock(ffiMutex);
    const int count = collect_network_infos(infos.data(), infos.size());
    if (count < 0)
        return nullptr;
    const int boundedCount = std::min(count, maxLength);

    std::ostringstream json;
    json << "{";
    bool first = true;
    for (int i = 0; i < boundedCount; ++i) {
        if (!infos[static_cast<size_t>(i)].key)
            continue;
        if (!first)
            json << ",";
        first = false;
        json << "\"" << jsonEscape(infos[static_cast<size_t>(i)].key) << "\":";
        if (infos[static_cast<size_t>(i)].value)
            json << infos[static_cast<size_t>(i)].value;
        else
            json << "null";
    }
    json << "}";

    for (int i = 0; i < boundedCount; ++i) {
        if (infos[static_cast<size_t>(i)].key)
            free_string(infos[static_cast<size_t>(i)].key);
        if (infos[static_cast<size_t>(i)].value)
            free_string(infos[static_cast<size_t>(i)].value);
    }

    return toJString(env, json.str());
}

// --- setTunFd ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_setTunFd(JNIEnv *env, jclass, jstring instanceName, jint fd)
{
    const std::string name = toString(env, instanceName);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return set_tun_fd(name.c_str(), fd);
}

// --- startConfigServerClient (with real callback) ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_startConfigServerClient(
    JNIEnv *env,
    jclass,
    jstring url,
    jstring hostname,
    jstring machineId,
    jboolean secureMode)
{
    const auto startClient = resolveSymbol<StartConfigServerClientFn>("start_config_server_client");
    if (!startClient) {
        std::lock_guard<std::mutex> lock(ffiMutex);
        setBridgeError("current libeasytier_ffi.so does not export start_config_server_client");
        return -1;
    }

    const std::string urlValue = toString(env, url);
    const std::string hostnameValue = toString(env, hostname);
    const std::string machineIdValue = toString(env, machineId);
    const char *hostnamePtr = hostnameValue.empty() ? nullptr : hostnameValue.c_str();

    std::lock_guard<std::mutex> lock(ffiMutex);
    return startClient(
        urlValue.c_str(),
        hostnamePtr,
        machineIdValue.c_str(),
        secureMode == JNI_TRUE,
        configServerEventCallbackC,
        nullptr);
}

// --- stopConfigServerClient ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_stopConfigServerClient(JNIEnv *, jclass)
{
    const auto stopClient = resolveSymbol<StopConfigServerClientFn>("stop_config_server_client");
    if (!stopClient) {
        std::lock_guard<std::mutex> lock(ffiMutex);
        setBridgeError("current libeasytier_ffi.so does not export stop_config_server_client");
        return -1;
    }

    std::lock_guard<std::mutex> lock(ffiMutex);
    return stopClient();
}

// --- isConfigServerClientConnected ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_isConfigServerClientConnected(JNIEnv *, jclass)
{
    const auto isConnected = resolveSymbol<IsConfigServerClientConnectedFn>("is_config_server_client_connected");
    if (!isConnected)
        return 0;

    std::lock_guard<std::mutex> lock(ffiMutex);
    return isConnected();
}

// --- hasConfigServerClient ---

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_hasConfigServerClient(JNIEnv *, jclass)
{
    const bool available =
        resolveSymbol<StartConfigServerClientFn>("start_config_server_client") &&
        resolveSymbol<StopConfigServerClientFn>("stop_config_server_client") &&
        resolveSymbol<IsConfigServerClientConnectedFn>("is_config_server_client_connected");
    return available ? JNI_TRUE : JNI_FALSE;
}

// --- getLastError ---

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_getLastError(JNIEnv *env, jclass)
{
    const char *error = nullptr;
    std::lock_guard<std::mutex> lock(ffiMutex);
    get_error_msg(&error);
    if (!error) {
        if (bridgeError.empty())
            return nullptr;
        return env->NewStringUTF(bridgeError.c_str());
    }

    jstring out = env->NewStringUTF(error);
    free_string(error);
    return out;
}