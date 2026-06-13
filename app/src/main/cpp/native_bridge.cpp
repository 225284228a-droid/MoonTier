#include <jni.h>
#include <algorithm>
#include <cstdlib>
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

}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_parseConfig(JNIEnv *env, jclass, jstring config)
{
    const std::string cfg = toString(env, config);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return parse_config(cfg.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_runNetworkInstance(JNIEnv *env, jclass, jstring config)
{
    const std::string cfg = toString(env, config);
    setenv("ET_CONSOLE_LOG_LEVEL", "info", 1);
    setenv("RUST_LOG", "CORE=info,easytier=info", 1);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return run_network_instance(cfg.c_str());
}

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

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_collectNetworkInfos(JNIEnv *env, jclass, jint maxLength)
{
    if (maxLength <= 0)
        return toJString(env, "{}");

    std::vector<KeyValuePair> infos(static_cast<size_t>(maxLength));
    std::lock_guard<std::mutex> lock(ffiMutex);
    const int count = collect_network_infos(infos.data(), infos.size());
    if (count < 0)
        return env->NewStringUTF("{}");
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
            json << "\"" << jsonEscape(infos[static_cast<size_t>(i)].value) << "\"";
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

extern "C" JNIEXPORT jint JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_setTunFd(JNIEnv *env, jclass, jstring instanceName, jint fd)
{
    const std::string name = toString(env, instanceName);
    std::lock_guard<std::mutex> lock(ffiMutex);
    return set_tun_fd(name.c_str(), fd);
}

extern "C" JNIEXPORT jstring JNICALL
Java_cn_moonflow_easytier_NativeEasyTier_getLastError(JNIEnv *env, jclass)
{
    const char *error = nullptr;
    std::lock_guard<std::mutex> lock(ffiMutex);
    get_error_msg(&error);
    if (!error)
        return nullptr;

    jstring out = env->NewStringUTF(error);
    free_string(error);
    return out;
}
