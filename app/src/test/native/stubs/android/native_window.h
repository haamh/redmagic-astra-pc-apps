#pragma once
// Desktop test stub: the compositor tests render offscreen and never create window surfaces.
#include <EGL/egl.h>
typedef struct ANativeWindow ANativeWindow;
inline void ANativeWindow_release(ANativeWindow*) {}
inline int ANativeWindow_getWidth(ANativeWindow*) { return 0; }
inline int ANativeWindow_getHeight(ANativeWindow*) { return 0; }
