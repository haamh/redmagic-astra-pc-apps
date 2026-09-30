#include "zero_copy_buffer.h"

namespace stream4k60 {

ZeroCopyBufferPool::ZeroCopyBufferPool(uint32_t width, uint32_t height, uint32_t format, size_t poolSize)
    : width_(width), height_(height), format_(format) {
    pool_.resize(poolSize);
    for (size_t i = 0; i < poolSize; ++i) { allocateBuffer(pool_[i]); freeIndices_.push(i); }
}
ZeroCopyBufferPool::~ZeroCopyBufferPool() { for (auto& frame : pool_) freeBuffer(frame); }
GpuFrame* ZeroCopyBufferPool::acquireFrame() {
    std::lock_guard<std::mutex> lock(mutex_); if (freeIndices_.empty()) return nullptr;
    const size_t idx = freeIndices_.front(); freeIndices_.pop(); pool_[idx].inUse = true; return &pool_[idx];
}
void ZeroCopyBufferPool::releaseFrame(GpuFrame* frame) {
    if (!frame) return; std::lock_guard<std::mutex> lock(mutex_); if (!frame->inUse) return;
    frame->inUse = false; for (size_t i = 0; i < pool_.size(); ++i) if (&pool_[i] == frame) { freeIndices_.push(i); break; }
}
void ZeroCopyBufferPool::resize(uint32_t width, uint32_t height) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (width == width_ && height == height_) return;
    for (auto& frame : pool_) freeBuffer(frame);
    width_ = width; height_ = height;
    std::queue<size_t> empty; std::swap(freeIndices_, empty);
    for (size_t i = 0; i < pool_.size(); ++i) { allocateBuffer(pool_[i]); freeIndices_.push(i); }
}
size_t ZeroCopyBufferPool::availableFrames() const { std::lock_guard<std::mutex> lock(mutex_); return freeIndices_.size(); }

void ZeroCopyBufferPool::allocateBuffer(GpuFrame& frame) {
    AHardwareBuffer_Desc desc{};
    desc.width = width_; desc.height = height_; desc.layers = 1;
    desc.format = format_ ? format_ : AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    if (AHardwareBuffer_allocate(&desc, &frame.buffer) != 0 || !frame.buffer) return;
    frame.width = width_; frame.height = height_; frame.inUse = false;
}
void ZeroCopyBufferPool::freeBuffer(GpuFrame& frame) {
    if (frame.textureId) { glDeleteTextures(1, &frame.textureId); frame.textureId = 0; }
    if (frame.eglImage != EGL_NO_IMAGE_KHR) frame.eglImage = EGL_NO_IMAGE_KHR;
    if (frame.buffer) { AHardwareBuffer_release(frame.buffer); frame.buffer = nullptr; }
    frame.inUse = false;
}

} // namespace stream4k60
