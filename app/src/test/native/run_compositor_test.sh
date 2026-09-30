#!/bin/sh
# Builds and runs the native compositor tests on the desktop with Mesa's software GLES.
# Needs: g++, a JDK (for jni.h), and Mesa EGL/GLES development packages (libegl-dev libgles-dev libgl1-mesa-dri).
set -e
cd "$(dirname "$0")"
JDK_HOME="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac || command -v java)")")")}"
OUT="${TMPDIR:-/tmp}/stream4k_compositor_test"
# -fpermissive: Android's jni.h types AttachCurrentThread(JNIEnv**), the desktop JDK uses void**.
g++ -std=c++20 -O1 -fpermissive -w -DGL_GLEXT_PROTOTYPES -Istubs -I"$JDK_HOME/include" -I"$JDK_HOME/include/linux" \
    compositor_test.cpp desktop_egl.cpp ../../main/cpp/gpu/gl_compositor.cpp \
    -lEGL -lGLESv2 -lpthread -o "$OUT"
exec "$OUT"
