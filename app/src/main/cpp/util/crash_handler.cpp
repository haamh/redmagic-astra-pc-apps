// Records native crashes (SIGSEGV, SIGILL, SIGABRT...) to a file readable with `adb shell cat`, because device
// logcat can be restricted. Only async-signal-tolerant calls are used: open/write, the unwinder and dladdr.
#include <jni.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>
#include <cstdint>
#include <cstdio>

namespace {
char gPath[512];
struct sigaction gPrevious[32];
const int kSignals[] = {SIGSEGV, SIGBUS, SIGILL, SIGABRT, SIGFPE};
constexpr int kMaxFrames = 48;

struct Trace { uintptr_t pcs[kMaxFrames]; int count; };

_Unwind_Reason_Code collect(_Unwind_Context* context, void* arg) {
    auto* trace = static_cast<Trace*>(arg);
    const uintptr_t pc = _Unwind_GetIP(context);
    if (pc && trace->count < kMaxFrames) trace->pcs[trace->count++] = pc;
    return trace->count < kMaxFrames ? _URC_NO_REASON : _URC_END_OF_STACK;
}

void writeStr(int fd, const char* s) { if (s) (void)!write(fd, s, strlen(s)); }

void writeFrame(int fd, int index, uintptr_t pc) {
    char line[768];
    Dl_info info{};
    if (dladdr(reinterpret_cast<void*>(pc), &info) && info.dli_fname) {
        const char* lib = strrchr(info.dli_fname, '/');
        snprintf(line, sizeof line, "#%02d pc %016lx  %s+0x%lx  %s+0x%lx\n", index, (unsigned long)pc,
                 lib ? lib + 1 : info.dli_fname, (unsigned long)(pc - (uintptr_t)info.dli_fbase),
                 info.dli_sname ? info.dli_sname : "?", info.dli_saddr ? (unsigned long)(pc - (uintptr_t)info.dli_saddr) : 0ul);
    } else {
        snprintf(line, sizeof line, "#%02d pc %016lx  ?\n", index, (unsigned long)pc);
    }
    writeStr(fd, line);
}

void onSignal(int sig, siginfo_t* info, void* ucontext) {
    const int fd = open(gPath, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd >= 0) {
        char head[256];
        snprintf(head, sizeof head, "Stream4k60 native crash: signal %d (%s), code %d, fault address %p, thread %d\n",
                 sig, strsignal(sig), info ? info->si_code : 0, info ? info->si_addr : nullptr, gettid());
        writeStr(fd, head);
#if defined(__aarch64__)
        if (ucontext) writeFrame(fd, -1, static_cast<ucontext_t*>(ucontext)->uc_mcontext.pc);
#endif
        Trace trace{{}, 0};
        _Unwind_Backtrace(collect, &trace);
        for (int i = 0; i < trace.count; ++i) writeFrame(fd, i, trace.pcs[i]);
        close(fd);
    }
    // Hand the signal to the previous handler (Android's debuggerd) so the normal crash report still happens.
    sigaction(sig, &gPrevious[sig], nullptr);
    raise(sig);
}
} // namespace

extern "C" JNIEXPORT void JNICALL Java_com_stream4k60_app_engine_NativeEngine_installCrashHandler(JNIEnv* env, jclass, jstring path) {
    const char* p = env->GetStringUTFChars(path, nullptr);
    if (!p) return;
    strncpy(gPath, p, sizeof gPath - 1);
    env->ReleaseStringUTFChars(path, p);
    static bool installed = false;
    if (installed) return;
    installed = true;
    static char altStack[64 * 1024];
    stack_t ss{};
    ss.ss_sp = altStack; ss.ss_size = sizeof altStack; ss.ss_flags = 0;
    sigaltstack(&ss, nullptr);
    struct sigaction action{};
    action.sa_sigaction = onSignal;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    for (int sig : kSignals) sigaction(sig, &action, &gPrevious[sig]);
}
