#include <jni.h>
#include <cerrno>
#include <fcntl.h>
#include <sys/syscall.h>
#include <unistd.h>

extern "C" JNIEXPORT jboolean JNICALL
Java_io_github_kkkhs_pocketrun_AtomicSamplePublisher_renameNoReplace(
        JNIEnv* env, jobject, jstring source, jstring target) {
    const char* from = env->GetStringUTFChars(source, nullptr);
    if (from == nullptr) return JNI_FALSE;
    const char* to = env->GetStringUTFChars(target, nullptr);
    if (to == nullptr) {
        env->ReleaseStringUTFChars(source, from);
        return JNI_FALSE;
    }
    constexpr unsigned int noReplace = 1;
    const long result = syscall(__NR_renameat2, AT_FDCWD, from, AT_FDCWD, to, noReplace);
    const int error = errno;
    env->ReleaseStringUTFChars(source, from);
    env->ReleaseStringUTFChars(target, to);
    if (result == 0) return JNI_TRUE;
    if (error == EEXIST) return JNI_FALSE;
    jclass exception = env->FindClass("java/io/IOException");
    if (exception != nullptr) {
        env->ThrowNew(exception, "Atomic no-replace sample publication failed; no replacement fallback was performed.");
    }
    return JNI_FALSE;
}
