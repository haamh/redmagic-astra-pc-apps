#pragma once
#include <android/hardware_buffer.h>
#include <GLES3/gl32.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2ext.h>
#include <vector>
#include <queue>
#include <mutex>

namespace stream4k60 {

struct GpuFrame {
    AHardwareBuffer* buffer = nullptr;
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    GLuint textureId = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    int64_t timestampNs = 0;
    bool inUse = false;
};

class ZeroCopyBufferPool {
public:
    ZeroCopyBufferPool(uint32_t width, uint32_t height, uint32_t format, size_t poolSize);
    ~ZeroCopyBufferPool();
    
    GpuFrame* acquireFrame();
    void releaseFrame(GpuFrame* frame);
    void resize(uint32_t width, uint32_t height);
    
    size_t availableFrames() const;
    size_t totalFrames() const { return pool_.size(); }

private:
    std::vector<GpuFrame> pool_;
    std::queue<size_t> freeIndices_;
    mutable std::mutex mutex_;
    uint32_t width_, height_, format_;
    
    void allocateBuffer(GpuFrame& frame);
    void freeBuffer(GpuFrame& frame);
};

} // namespace stream4k60
