#pragma once
#include <jni.h>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <thread>
#include <vector>
#include <deque>
#include <condition_variable>
#include <mutex>
#include "usbfs_compat.h"

namespace stream4k60 {
class UvcBulkStream {
public:
    UvcBulkStream(int fd, uint8_t endpoint, size_t packetBytes, size_t transferBytes, int urbCount,
                  jobject callback, jmethodID frameMethod, JavaVM* vm);
    ~UvcBulkStream();
    bool start();
    void stop();
    uint64_t frames() const { return frames_.load(); }
    uint64_t transferErrors() const { return transferErrors_.load(); }
    uint64_t droppedFrames() const { return droppedFrames_.load(); }
private:
    struct Slot {
        usbfs::Urb* urb=nullptr;
        std::vector<uint8_t> storage;
        uint8_t* urbMemory=nullptr;
        size_t urbMemorySize=0;
    };
    void reapLoop();
    bool submit(Slot& slot);
    void discard(Slot& slot);
    void processUrb(Slot* slot);
    void processBytes(const uint8_t* data,size_t size);
    struct QueuedFrame { std::vector<uint8_t> data; uint64_t ptsUs=0; bool hadError=false; };
    void emitFrame();
    void callbackLoop();
    static uint64_t monotonicUs();
    int fd_; uint8_t endpoint_; size_t packetBytes_; size_t transferBytes_; int urbCount_;
    jobject callback_; jmethodID frameMethod_; JavaVM* vm_;
    std::vector<std::unique_ptr<Slot>> slots_;
    std::thread thread_; std::thread callbackThread_; std::atomic<bool> running_{false};
    std::mutex frameQueueMutex_; std::condition_variable frameQueueCv_; std::deque<QueuedFrame> frameQueue_;
    std::atomic<uint64_t> frames_{0},transferErrors_{0},droppedFrames_{0};
    std::vector<uint8_t> frame_; int currentFid_=-1; bool sawError_=false;
};
}
