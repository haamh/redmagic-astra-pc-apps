// Desktop replacement for gpu/egl_context.cpp: a surfaceless Mesa EGL context with GLES 3.2.
#include "../../main/cpp/gpu/egl_context.h"
#include <cstdlib>
EglContext::~EglContext() { release(); }
bool EglContext::loadExtensions() { return true; }
bool EglContext::initialize() {
    if (initialized_) return true;
    setenv("EGL_PLATFORM", "surfaceless", 0);
    display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (display_ == EGL_NO_DISPLAY || eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) return false;
    eglBindAPI(EGL_OPENGL_ES_API);
    initialized_ = true;
    return true;
}
bool EglContext::createOffscreenContext() {
    if (!initialize()) return false;
    const EGLint a[] = {EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(display_, a, &config_, 1, &n) || !n) return false;
    const EGLint ca[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE};
    context_ = eglCreateContext(display_, config_, EGL_NO_CONTEXT, ca);
    if (context_ == EGL_NO_CONTEXT) return false;
    const EGLint pa[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    surface_ = eglCreatePbufferSurface(display_, config_, pa);
    return surface_ != EGL_NO_SURFACE && eglMakeCurrent(display_, surface_, surface_, context_) == EGL_TRUE;
}
EGLSurface EglContext::createWindowSurface(ANativeWindow*) { return EGL_NO_SURFACE; }
void EglContext::release() {
    if (display_ != EGL_NO_DISPLAY) {
        eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (context_ != EGL_NO_CONTEXT) eglDestroyContext(display_, context_);
        if (surface_ != EGL_NO_SURFACE) eglDestroySurface(display_, surface_);
        eglTerminate(display_);
    }
    display_ = EGL_NO_DISPLAY; context_ = EGL_NO_CONTEXT; surface_ = EGL_NO_SURFACE; initialized_ = false;
}
