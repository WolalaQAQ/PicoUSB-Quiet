// PicoUSB-Quiet - Zygisk loader for the system_server helper.
//
// Before specialization read the (root-owned) DEX and rules.txt from the module dir; after
// specialization start a detached native worker that waits for sys.boot_completed + the real
// system_server cmdline, loads the DEX with InMemoryDexClassLoader and calls
// org.picousbquiet.QuietServer.start(String). No method hooks.
// Every non-system_server process unloads the library immediately.
#include <sys/types.h>   // dev_t / ino_t used by zygisk.hpp before it includes them
#include "zygisk.hpp"

#include <android/log.h>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <pthread.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>

namespace {
constexpr const char* kTag = "PicoUSB-Quiet";
constexpr size_t kMaxDex = 256 * 1024;
constexpr size_t kMaxConfig = 8 * 1024;

unsigned char* dex_bytes;
size_t dex_size;
char* rules_text;

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

bool read_exact(int fd, void* data, size_t length) {
    auto* out = static_cast<unsigned char*>(data);
    while (length) {
        ssize_t n = read(fd, out, length);
        if (n < 0 && errno == EINTR) continue;
        if (n <= 0) return false;
        out += n; length -= static_cast<size_t>(n);
    }
    return true;
}

// Root-owned, not group/world writable, regular file, bounded size. Returns malloc'd buffer
// with one spare NUL byte, or nullptr.
unsigned char* load_file(int directory, const char* name, size_t min_size, size_t max_size, size_t* size) {
    int fd = openat(directory, name, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) { LOGE("open %s errno=%d", name, errno); return nullptr; }
    struct stat st {};
    unsigned char* buffer = nullptr;
    if (fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_uid == 0 && (st.st_mode & 0022) == 0 &&
        static_cast<size_t>(st.st_size) >= min_size && static_cast<size_t>(st.st_size) <= max_size) {
        buffer = static_cast<unsigned char*>(malloc(static_cast<size_t>(st.st_size) + 1));
        if (buffer && read_exact(fd, buffer, static_cast<size_t>(st.st_size))) {
            buffer[st.st_size] = 0;
            *size = static_cast<size_t>(st.st_size);
        } else { free(buffer); buffer = nullptr; }
    } else {
        LOGE("%s rejected (owner/mode/size)", name);
    }
    close(fd);
    return buffer;
}

bool checked(JNIEnv* env, const char* step) {
    if (!env->ExceptionCheck()) return true;
    env->ExceptionClear();
    LOGE("DEX bootstrap failed at %s", step);
    return false;
}

void release_buffers() {
    free(dex_bytes); dex_bytes = nullptr; dex_size = 0;
    free(rules_text); rules_text = nullptr;
}

void bootstrap(JNIEnv* env) {
    if (env->PushLocalFrame(24) != JNI_OK) { checked(env, "local frame"); release_buffers(); return; }
    auto boot = [&]() -> bool {
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(dex_size));
        if (!checked(env, "byte array") || !bytes) return false;
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(dex_size), reinterpret_cast<const jbyte*>(dex_bytes));
        if (!checked(env, "DEX bytes")) return false;
        jclass buffer_class = env->FindClass("java/nio/ByteBuffer");
        if (!checked(env, "ByteBuffer") || !buffer_class) return false;
        jmethodID wrap = env->GetStaticMethodID(buffer_class, "wrap", "([B)Ljava/nio/ByteBuffer;");
        if (!checked(env, "ByteBuffer.wrap") || !wrap) return false;
        jobject buffer = env->CallStaticObjectMethod(buffer_class, wrap, bytes);
        if (!checked(env, "ByteBuffer instance") || !buffer) return false;
        jclass loader_class = env->FindClass("java/lang/ClassLoader");
        if (!checked(env, "ClassLoader") || !loader_class) return false;
        jmethodID get_parent = env->GetStaticMethodID(loader_class, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
        if (!checked(env, "getSystemClassLoader") || !get_parent) return false;
        jobject parent = env->CallStaticObjectMethod(loader_class, get_parent);
        if (!checked(env, "parent ClassLoader") || !parent) return false;
        jclass memory_class = env->FindClass("dalvik/system/InMemoryDexClassLoader");
        if (!checked(env, "InMemoryDexClassLoader") || !memory_class) return false;
        jmethodID ctor = env->GetMethodID(memory_class, "<init>", "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
        if (!checked(env, "loader ctor") || !ctor) return false;
        jobject loader = env->NewObject(memory_class, ctor, buffer, parent);
        if (!checked(env, "DEX loader") || !loader) return false;
        jmethodID load = env->GetMethodID(loader_class, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
        if (!checked(env, "loadClass") || !load) return false;
        jstring name = env->NewStringUTF("org.picousbquiet.QuietServer");
        if (!checked(env, "helper name") || !name) return false;
        auto server = static_cast<jclass>(env->CallObjectMethod(loader, load, name));
        if (!checked(env, "helper class") || !server) return false;
        jmethodID start = env->GetStaticMethodID(server, "start", "(Ljava/lang/String;)V");
        if (!checked(env, "helper start method") || !start) return false;
        jstring config = env->NewStringUTF(rules_text ? rules_text : "");
        if (!checked(env, "config string") || !config) return false;
        // Retain loader + DEX bytes for the system_server lifetime (classes load lazily).
        jobject loader_ref = env->NewGlobalRef(loader);
        jobject bytes_ref = env->NewGlobalRef(bytes);
        if (!loader_ref || !bytes_ref) return false;
        env->CallStaticVoidMethod(server, start, config);
        return checked(env, "helper start");
    };
    bool started = boot();
    env->PopLocalFrame(nullptr);
    release_buffers();
    if (started) LOGI("helper bootstrap done");
}

bool system_server_started() {
    // argv[0] becomes "system_server" only after the specialize thread finished.
    char name[32] {};
    int fd = open("/proc/self/cmdline", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    ssize_t n = read(fd, name, sizeof name - 1);
    close(fd);
    return n > 0 && !strcmp(name, "system_server");
}

void* worker_thread(void* context) {
    pthread_setname_np(pthread_self(), "usbq-boot");
    char done[PROP_VALUE_MAX];
    while (__system_property_get("sys.boot_completed", done) != 1 || done[0] != '1' || !system_server_started())
        sleep(1);
    auto* vm = static_cast<JavaVM*>(context);
    JNIEnv* env = nullptr;
    if (vm->AttachCurrentThreadAsDaemon(&env, nullptr) == JNI_OK) {
        LOGI("boot_completed=1; starting helper");
        bootstrap(env);
        vm->DetachCurrentThread();
    } else {
        LOGE("cannot attach worker");
        release_buffers();
    }
    return nullptr;
}

bool start_worker(JNIEnv* env) {
    if (!env || !dex_bytes || !dex_size) return false;
    JavaVM* vm = nullptr;
    int rc = env->GetJavaVM(&vm);
    if (rc == JNI_OK) {
        pthread_t thread;
        pthread_attr_t attributes;
        pthread_attr_init(&attributes);
        pthread_attr_setdetachstate(&attributes, PTHREAD_CREATE_DETACHED);
        rc = pthread_create(&thread, &attributes, worker_thread, vm);
        pthread_attr_destroy(&attributes);
        if (rc == 0) return true;
    }
    LOGE("cannot start worker (%d)", rc);
    release_buffers();
    return false;
}
} // namespace

class QuietModule : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api* api, JNIEnv* env) override { api_ = api; env_ = env; }

    void preServerSpecialize(zygisk::ServerSpecializeArgs*) override {
        int directory = api_->getModuleDir();
        if (directory >= 0) {
            size_t size = 0;
            unsigned char* dex = load_file(directory, "classes.dex", 112, kMaxDex, &size);
            if (dex && size >= 4 && !memcmp(dex, "dex\n", 4)) { dex_bytes = dex; dex_size = size; }
            else free(dex);
            size_t rules_size = 0;
            rules_text = reinterpret_cast<char*>(load_file(directory, "rules.txt", 1, kMaxConfig, &rules_size));
            close(directory);
        }
    }

    void postServerSpecialize(const zygisk::ServerSpecializeArgs*) override {
        if (!start_worker(env_)) api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs*) override {
        api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
    }

private:
    zygisk::Api* api_ = nullptr;
    JNIEnv* env_ = nullptr;
};

REGISTER_ZYGISK_MODULE(QuietModule)
