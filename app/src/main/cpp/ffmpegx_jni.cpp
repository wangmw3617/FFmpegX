/*
 * ffmpegx_jni.cpp
 * ---------------------------------------------------------------------------
 * JNI 桥接层。职责：
 *   1) 运行时按名 dlopen FFmpeg 共享库与两个 CLI 包装库，缓存函数指针；
 *   2) 把 Java String[] 转成 argv，调用 ffmpegx_run_ffmpeg / ffmpegx_run_ffprobe；
 *   3) 把 C 层 av_log 回调转发回 Kotlin（NativeLogSink）。
 *
 * 为什么用 dlopen 而不是直接链接：
 *   libffmpegcmd.so 与 libffprobecmd.so 都会导出同名符号（program_name 等），
 *   直接链接会产生符号抢占。dlopen(RTLD_LOCAL) + dlsym 精确取符号，最干净。
 */

#include <jni.h>

#include <android/log.h>
#include <dlfcn.h>
#include <pthread.h>

#include <atomic>
#include <cstring>
#include <string>
#include <vector>

#define LOG_TAG "FFmpegX-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

using LogCb  = void (*)(int level, const char *msg);
using RunFn  = int  (*)(int argc, char **argv, void *log_cb);
using CancelFn = void (*)(void);
using VersionFn = const char *(*)(void);

JavaVM   *g_vm        = nullptr;
jclass    g_sink_cls  = nullptr;
jmethodID g_sink_mid  = nullptr;

RunFn    g_run_ffmpeg   = nullptr;
RunFn    g_run_ffprobe  = nullptr;
CancelFn g_cancel_ffmpeg  = nullptr;
CancelFn g_cancel_ffprobe = nullptr;

std::atomic<bool> g_initialized{false};
std::string       g_version{"unknown"};
std::string       g_load_error;

/* 当前调用的 log sink（同一时刻只有一个 FFmpeg 会话在跑，用全局引用即可） */
pthread_mutex_t g_sink_lock = PTHREAD_MUTEX_INITIALIZER;
jobject         g_sink_ref  = nullptr;

/* ------------------------------------------------------------------ 日志转发 ---- */

void native_log_trampoline(int level, const char *msg)
{
    if (!g_vm || !msg) return;

    JNIEnv *env = nullptr;
    bool attached = false;

    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    pthread_mutex_lock(&g_sink_lock);
    jobject sink = g_sink_ref;
    if (sink != nullptr && g_sink_mid != nullptr) {
        jstring jmsg = env->NewStringUTF(msg);
        if (jmsg != nullptr) {
            env->CallVoidMethod(sink, g_sink_mid, static_cast<jint>(level), jmsg);
            env->DeleteLocalRef(jmsg);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }
    pthread_mutex_unlock(&g_sink_lock);

    if (attached) g_vm->DetachCurrentThread();
}

/* ------------------------------------------------------------------ 库加载 ---- */

/**
 * 按名 dlopen。App 的 classloader namespace 已经把 APK 的 native lib 目录
 * 放进搜索路径，所以直接给文件名即可（同时兼容 extractNativeLibs 开/关）。
 */
bool load_libraries()
{
    if (g_initialized.load()) return true;

    // FFmpeg 的共享库：先以 RTLD_GLOBAL 载入，保证后续 CLI 库的未定义符号能解析
    static const char *kAvLibs[] = {
        "libavutil.so",
        "libswresample.so",
        "libswscale.so",
        "libavcodec.so",
        "libavfilter.so",
        "libavformat.so",
    };
    for (const char *name : kAvLibs) {
        void *h = dlopen(name, RTLD_NOW | RTLD_GLOBAL);
        if (h == nullptr) {
            g_load_error = std::string("dlopen 失败: ") + name + " -> " + dlerror();
            LOGE("%s", g_load_error.c_str());
            return false;
        }
    }

    void *h_ffmpeg  = dlopen("libffmpegcmd.so",  RTLD_NOW | RTLD_LOCAL);
    void *h_ffprobe = dlopen("libffprobecmd.so", RTLD_NOW | RTLD_LOCAL);
    if (h_ffmpeg == nullptr || h_ffprobe == nullptr) {
        g_load_error = std::string("dlopen 失败: libffmpegcmd/libffprobecmd -> ") + dlerror();
        LOGE("%s", g_load_error.c_str());
        return false;
    }

    g_run_ffmpeg  = reinterpret_cast<RunFn>(dlsym(h_ffmpeg, "ffmpegx_run_ffmpeg"));
    g_cancel_ffmpeg = reinterpret_cast<CancelFn>(dlsym(h_ffmpeg, "ffmpegx_cancel_ffmpeg"));
    g_run_ffprobe = reinterpret_cast<RunFn>(dlsym(h_ffprobe, "ffmpegx_run_ffprobe"));
    g_cancel_ffprobe = reinterpret_cast<CancelFn>(dlsym(h_ffprobe, "ffmpegx_cancel_ffprobe"));

    if (g_run_ffmpeg == nullptr || g_run_ffprobe == nullptr) {
        g_load_error = "dlsym 失败: ffmpegx_run_ffmpeg / ffmpegx_run_ffprobe";
        LOGE("%s", g_load_error.c_str());
        return false;
    }

    void *h_avutil = dlopen("libavutil.so", RTLD_NOW | RTLD_GLOBAL);
    if (h_avutil != nullptr) {
        auto fn = reinterpret_cast<VersionFn>(dlsym(h_avutil, "av_version_info"));
        if (fn != nullptr && fn() != nullptr) g_version = fn();
    }

    g_initialized.store(true);
    LOGI("原生库加载完成，FFmpeg 版本: %s", g_version.c_str());
    return true;
}

/* --------------------------------------------------------------- argv 转换 ---- */

struct Argv {
    std::vector<std::string> storage;
    std::vector<char *>      ptrs;

    void add(const std::string &s) { storage.push_back(s); }

    void build()
    {
        ptrs.clear();
        ptrs.reserve(storage.size() + 1);
        for (auto &s : storage) ptrs.push_back(const_cast<char *>(s.c_str()));
        ptrs.push_back(nullptr);
    }
    int  argc() const { return static_cast<int>(storage.size()); }
    char **argv() { return ptrs.data(); }
};

bool to_argv(JNIEnv *env, jobjectArray arr, Argv &out)
{
    if (arr == nullptr) return false;
    const jsize n = env->GetArrayLength(arr);
    for (jsize i = 0; i < n; ++i) {
        auto js = reinterpret_cast<jstring>(env->GetObjectArrayElement(arr, i));
        if (js == nullptr) continue;
        const char *c = env->GetStringUTFChars(js, nullptr);
        if (c != nullptr) {
            out.add(std::string(c));
            env->ReleaseStringUTFChars(js, c);
        }
        env->DeleteLocalRef(js);
    }
    out.build();
    return out.argc() > 0;
}

void set_sink(JNIEnv *env, jobject sink)
{
    pthread_mutex_lock(&g_sink_lock);
    if (g_sink_ref != nullptr) {
        env->DeleteGlobalRef(g_sink_ref);
        g_sink_ref = nullptr;
    }
    if (sink != nullptr) {
        g_sink_ref = env->NewGlobalRef(sink);
    }
    pthread_mutex_unlock(&g_sink_lock);
}

void clear_sink(JNIEnv *env)
{
    set_sink(env, nullptr);
}

int run_cli(JNIEnv *env, jobjectArray args, jobject sink, RunFn fn)
{
    if (fn == nullptr) return -1;

    Argv argv;
    if (!to_argv(env, args, argv)) return -1;

    set_sink(env, sink);
    const int rc = fn(argv.argc(), argv.argv(),
                      reinterpret_cast<void *>(&native_log_trampoline));
    clear_sink(env);

    return rc;
}

}  // namespace

/* ========================================================================== */
/*                                JNI 入口                                    */
/* ========================================================================== */

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /*reserved*/)
{
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeInit(JNIEnv *env, jobject /*thiz*/)
{
    if (g_sink_cls == nullptr) {
        jclass cls = env->FindClass("com/zhiwei/ffmpegx/native/NativeLogSink");
        if (cls == nullptr) {
            env->ExceptionClear();
            return JNI_FALSE;
        }
        g_sink_cls = reinterpret_cast<jclass>(env->NewGlobalRef(cls));
        g_sink_mid = env->GetMethodID(g_sink_cls, "onLog", "(ILjava/lang/String;)V");
        env->DeleteLocalRef(cls);
        if (g_sink_mid == nullptr) {
            env->ExceptionClear();
            return JNI_FALSE;
        }
    }
    return load_libraries() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeVersion(JNIEnv *env, jobject /*thiz*/)
{
    return env->NewStringUTF(g_version.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeLoadError(JNIEnv *env, jobject /*thiz*/)
{
    return env->NewStringUTF(g_load_error.c_str());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeRunFfmpeg(
    JNIEnv *env, jobject /*thiz*/, jobjectArray args, jobject sink)
{
    return run_cli(env, args, sink, g_run_ffmpeg);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeRunFfprobe(
    JNIEnv *env, jobject /*thiz*/, jobjectArray args, jobject sink)
{
    return run_cli(env, args, sink, g_run_ffprobe);
}

extern "C" JNIEXPORT void JNICALL
Java_com_zhiwei_ffmpegx_native_FfmpegJni_nativeCancel(JNIEnv * /*env*/, jobject /*thiz*/)
{
    if (g_cancel_ffmpeg)  g_cancel_ffmpeg();
    if (g_cancel_ffprobe) g_cancel_ffprobe();
}
