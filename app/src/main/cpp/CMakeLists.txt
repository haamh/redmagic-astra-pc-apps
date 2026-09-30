cmake_minimum_required(VERSION 3.22.1)
project("stream4k60" LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_C_STANDARD 17)

file(GLOB_RECURSE STREAM4K_CPP CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/*.cpp")
add_library(stream4k60_engine SHARED ${STREAM4K_CPP})

find_library(log-lib log)
find_library(android-lib android)
find_library(egl-lib EGL)
find_library(gles3-lib GLESv3)
find_library(mediandk-lib mediandk)
find_library(camera2ndk-lib camera2ndk)
find_library(aaudio-lib aaudio)
find_library(nativewindow-lib nativewindow)

target_include_directories(stream4k60_engine PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_link_libraries(stream4k60_engine
    ${log-lib} ${android-lib} ${egl-lib} ${gles3-lib} ${mediandk-lib}
    ${camera2ndk-lib} ${aaudio-lib} ${nativewindow-lib} jnigraphics)
# armv8.2-a, not armv9-a: the Astra's Snapdragon 8 Elite (Qualcomm Oryon) has no SVE/SVE2, and armv9-a lets the
# auto-vectorizer emit SVE instructions that crash it with SIGILL. Every Android arm64 SoC in scope supports v8.2-a.
target_compile_options(stream4k60_engine PRIVATE -O3 -ffast-math -ftree-vectorize -march=armv8.2-a -DSTREAM4K60_ENGINE)
