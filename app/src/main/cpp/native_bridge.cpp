#include <jni.h>
#include <dlfcn.h>
#include <pthread.h>
#include <algorithm>
#include <cstdlib>
#include <cstdint>
#include <deque>
#include <mutex>
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
int shared_tun_config(const char **out);
int attach_shared_tun_fd(int fd);
int run_network_instance_with_source(const char *cfg, int source);
int set_network_instance_enabled(const char *instance_id, bool enabled);
int collect_runtime_configs(const char **out_json);
int restore_runtime_configs(const char *json);
int list_instance(KeyValuePair *infos, size_t max_length);
int delete_network_instance(const char **names, size_t length);
int call_json_rpc(const char *service, const char *method, const char *domain, const char *payload, const char **out);
using EventCallback = void (*)(const char *, void *);
int set_runtime_event_callback(EventCallback callback, void *user_data);
int start_config_server_client(const char *url, const char *hostname, const char *machine_id, bool secure_mode, EventCallback callback, void *user_data);
int detach_config_server_client_preserve_instances();
int stop_config_server_client();
int is_config_server_client_connected();
void get_error_msg(const char **out);
void free_string(const char *s);
}

namespace {

std::mutex ffiMutex;
std::mutex eventMutex;
std::deque<std::pair<int64_t, std::string>> events;
JavaVM *javaVm = nullptr;
jclass nativeClass = nullptr;
jmethodID notifyMethod = nullptr;
pthread_key_t attachedThreadKey;
bool attachedThreadKeyReady = false;

int checked(JNIEnv *env, int code)
{
    if (code >= 0) return code;
    const char *error = nullptr;
    get_error_msg(&error);
    jclass cls = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(cls, error ? error : "EasyTier native operation failed");
    env->DeleteLocalRef(cls);
    if (error) free_string(error);
    return code;
}

void detachAttachedThread(void *)
{
    if (javaVm) javaVm->DetachCurrentThread();
}

// Runtime callbacks arrive on long-lived tokio threads. Attaching once per
// thread avoids allocating a java.lang.Thread for every peer event; the key
// destructor detaches when the native thread exits.
JNIEnv *callbackEnv()
{
    JNIEnv *env = nullptr;
    if (javaVm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) return env;
    JavaVMAttachArgs args{JNI_VERSION_1_6, "MoonTier-native-event", nullptr};
    if (javaVm->AttachCurrentThreadAsDaemon(&env, &args) != JNI_OK) return nullptr;
    if (attachedThreadKeyReady) pthread_setspecific(attachedThreadKey, env);
    return env;
}

void configServerEvent(const char *json, void *userData)
{
    if (!json) return;
    {
        std::lock_guard<std::mutex> guard(eventMutex);
        if (events.size() >= 256) events.pop_front();
        events.emplace_back(static_cast<int64_t>(reinterpret_cast<intptr_t>(userData)), json);
    }
    if (!javaVm || !nativeClass || !notifyMethod) return;
    JNIEnv *env = callbackEnv();
    if (!env) return;
    env->CallStaticVoidMethod(nativeClass, notifyMethod);
    if (env->ExceptionCheck()) env->ExceptionClear();
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

void appendJsonEscaped(std::string &out, const char *raw)
{
    if (!raw)
        return;
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
}

} // namespace

// --- JNI_OnLoad ---

extern "C" JNIEXPORT jint JNICALL
JNI_OnLoad(JavaVM *vm, void *)
{
    javaVm = vm;
    attachedThreadKeyReady = pthread_key_create(&attachedThreadKey, detachAttachedThread) == 0;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    auto cls = env->FindClass("cn/moonflow/easytier/NativeEasyTier");
    if (!cls) return JNI_ERR;
    nativeClass = static_cast<jclass>(env->NewGlobalRef(cls));
    notifyMethod = env->GetStaticMethodID(cls, "onNativeEventAvailable", "()V");
    env->DeleteLocalRef(cls);
    if (!notifyMethod) return JNI_ERR;
    return JNI_VERSION_1_6;
}

// --- parseConfig ---

extern "C" JNIEXPORT void JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_setLogLevel(JNIEnv *env, jclass, jstring level)
{
    const std::string requested = toString(env, level);
    std::lock_guard<std::mutex> lock(ffiMutex);
    if (requested == "debug") {
        setenv("ET_CONSOLE_LOG_LEVEL", "debug", 1);
        setenv("RUST_LOG", "CORE=debug,easytier=debug", 1);
    } else if (requested == "normal") {
        setenv("ET_CONSOLE_LOG_LEVEL", "warn", 1);
        setenv("RUST_LOG", "CORE=warn,easytier=warn", 1);
    } else {
        setenv("ET_CONSOLE_LOG_LEVEL", "off", 1);
        setenv("RUST_LOG", "off", 1);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_parseConfig(JNIEnv *env, jclass, jstring config)
{
    const std::string cfg = toString(env, config);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, parse_config(cfg.c_str()));
}

// --- runNetworkInstance ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_runNetworkInstance(JNIEnv *env, jclass, jstring config)
{
    const std::string cfg = toString(env, config);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, run_network_instance(cfg.c_str()));
}

// --- retainNetworkInstance ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_retainNetworkInstance(JNIEnv *env, jclass, jobjectArray names)
{
    if (!names) {
        std::lock_guard<std::mutex> lock(ffiMutex);
        return checked(env, retain_network_instance(nullptr, 0));
    }

    const jsize count = env->GetArrayLength(names);
    if (count <= 0) {
        std::lock_guard<std::mutex> lock(ffiMutex);
        return checked(env, retain_network_instance(nullptr, 0));
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
    return checked(env, retain_network_instance(ptrs.data(), ptrs.size()));
}

// --- collectNetworkInfos ---

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_collectNetworkInfos(JNIEnv *env, jclass, jint maxLength)
{
    if (maxLength <= 0)
        return toJString(env, "{}");

    std::vector<KeyValuePair> infos(static_cast<size_t>(maxLength));
    int count;
    {
        std::lock_guard<std::mutex> lock(ffiMutex);
        count = collect_network_infos(infos.data(), infos.size());
        if (checked(env, count) < 0)
            return nullptr;
    }
    const auto boundedCount = static_cast<size_t>(std::min(count, maxLength));

    size_t size = 2;
    for (size_t i = 0; i < boundedCount; ++i) {
        if (infos[i].key) size += std::char_traits<char>::length(infos[i].key) + 4;
        if (infos[i].value) size += std::char_traits<char>::length(infos[i].value);
    }
    std::string json;
    json.reserve(size + 16);
    json += '{';
    bool first = true;
    for (size_t i = 0; i < boundedCount; ++i) {
        if (!infos[i].key)
            continue;
        if (!first)
            json += ',';
        first = false;
        json += '"';
        appendJsonEscaped(json, infos[i].key);
        json += "\":";
        json += infos[i].value ? infos[i].value : "null";
    }
    json += '}';

    for (size_t i = 0; i < boundedCount; ++i) {
        if (infos[i].key)
            free_string(infos[i].key);
        if (infos[i].value)
            free_string(infos[i].value);
    }

    return toJString(env, json);
}

// --- setTunFd ---

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_setTunFd(JNIEnv *env, jclass, jstring instanceName, jint fd)
{
    const std::string name = toString(env, instanceName);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, set_tun_fd(name.c_str(), fd));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_runNetworkInstanceWithSource(JNIEnv *env, jclass, jstring config, jint source)
{
    const auto cfg = toString(env, config);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, run_network_instance_with_source(cfg.c_str(), source));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_setNetworkInstanceEnabled(JNIEnv *env, jclass, jstring instanceId, jboolean enabled)
{
    const auto id = toString(env, instanceId);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, set_network_instance_enabled(id.c_str(), enabled == JNI_TRUE));
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_collectRuntimeConfigs(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    const char *json = nullptr;
    if (checked(env, collect_runtime_configs(&json)) < 0) return nullptr;
    if (!json) {
        const auto cls = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(cls, "EasyTier returned no runtime configuration snapshot");
        env->DeleteLocalRef(cls);
        return nullptr;
    }
    const auto out = toJString(env, json);
    if (json) free_string(json);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_restoreRuntimeConfigs(JNIEnv *env, jclass, jstring configs)
{
    const auto json = toString(env, configs);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, restore_runtime_configs(json.c_str()));
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_listInstances(JNIEnv *env, jclass, jint maximum)
{
    if (maximum <= 0) return toJString(env, "{}");
    std::vector<KeyValuePair> values(static_cast<size_t>(maximum));
    std::lock_guard<std::mutex> lock(ffiMutex);
    const int count = list_instance(values.data(), values.size());
    if (checked(env, count) < 0) return nullptr;
    std::string json = "{";
    for (int i = 0; i < std::min(count, maximum); ++i) {
        const auto &entry = values[static_cast<size_t>(i)];
        if (i) json += ',';
        json += '"';
        appendJsonEscaped(json, entry.key);
        json += "\":\"";
        appendJsonEscaped(json, entry.value);
        json += '"';
        if (entry.key) free_string(entry.key);
        if (entry.value) free_string(entry.value);
    }
    json += '}';
    return toJString(env, json);
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_deleteNetworkInstance(JNIEnv *env, jclass, jstring nameValue)
{
    const auto name = toString(env, nameValue);
    const char *names[] = {name.c_str()};
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, delete_network_instance(names, 1));
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_callJsonRpc(JNIEnv *env, jclass, jstring serviceValue, jstring methodValue, jstring domainValue, jstring payloadValue)
{
    const auto service = toString(env, serviceValue);
    const auto method = toString(env, methodValue);
    const auto domain = toString(env, domainValue);
    const auto payload = toString(env, payloadValue);
    std::lock_guard<std::mutex> lock(ffiMutex);
    const char *response = nullptr;
    if (checked(env, call_json_rpc(service.c_str(), method.c_str(), domain.empty() ? nullptr : domain.c_str(), payload.c_str(), &response)) < 0) return nullptr;
    const auto out = toJString(env, response ? response : "{}");
    if (response) free_string(response);
    return out;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_startConfigServerClient(JNIEnv *env, jclass, jstring urlValue, jstring hostnameValue, jstring machineValue, jboolean secure, jlong generation)
{
    const auto url = toString(env, urlValue);
    const auto hostname = toString(env, hostnameValue);
    const auto machine = toString(env, machineValue);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, start_config_server_client(url.c_str(), hostname.empty() ? nullptr : hostname.c_str(), machine.c_str(), secure == JNI_TRUE, configServerEvent, reinterpret_cast<void *>(static_cast<intptr_t>(generation))));
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_detachConfigServerClient(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, detach_config_server_client_preserve_instances());
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_stopConfigServerClient(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, stop_config_server_client());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_isConfigServerClientConnected(JNIEnv *, jclass)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    return is_config_server_client_connected() == 1 ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_drainConfigServerEvents(JNIEnv *env, jclass)
{
    std::deque<std::pair<int64_t, std::string>> drained;
    {
        std::lock_guard<std::mutex> lock(eventMutex);
        drained.swap(events);
    }
    std::string json = "[";
    bool first = true;
    for (const auto &entry : drained) {
        if (!first) json += ',';
        first = false;
        json += "{\"generation\":";
        json += std::to_string(entry.first);
        json += ",\"payload\":";
        json += entry.second;
        json += '}';
    }
    json += ']';
    return toJString(env, json);
}

extern "C" JNIEXPORT void JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_setRuntimeEventsEnabled(JNIEnv *env, jclass, jboolean enabled)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    checked(env, set_runtime_event_callback(enabled == JNI_TRUE ? configServerEvent : nullptr,
        reinterpret_cast<void *>(static_cast<intptr_t>(-1))));
}

// --- getLastError ---

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_getLastError(JNIEnv *env, jclass)
{
    const char *error = nullptr;
    std::lock_guard<std::mutex> lock(ffiMutex);
    get_error_msg(&error);
    if (!error) {
        return nullptr;
    }

    jstring out = env->NewStringUTF(error);
    free_string(error);
    return out;
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_sharedTunConfig(JNIEnv *env, jclass)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    const char *json = nullptr;
    if (checked(env, shared_tun_config(&json)) < 0) return nullptr;
    jstring result = toJString(env, json ? json : "{}");
    if (json) free_string(json);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_attachSharedTunFd(JNIEnv *env, jclass, jint fd)
{
    std::lock_guard<std::mutex> lock(ffiMutex);
    return checked(env, attach_shared_tun_fd(fd));
}

// Returns pages freed by the Rust runtime to the kernel after traffic spikes.
extern "C" JNIEXPORT void JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_trimNativeHeap(JNIEnv *, jclass)
{
    using Mallopt = int (*)(int, int);
    static const auto mallopt = reinterpret_cast<Mallopt>(dlsym(RTLD_DEFAULT, "mallopt"));
    if (!mallopt) return;
    // M_PURGE_ALL (-104) is newer than M_PURGE (-101); both return 0 when unsupported.
    if (mallopt(-104, 0) == 0) mallopt(-101, 0);
}
