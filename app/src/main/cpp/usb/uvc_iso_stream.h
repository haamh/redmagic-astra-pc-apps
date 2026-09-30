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

class UvcIsoStream {
public:
    UvcIsoStream(int fd, uint8_t endpoint, size_t packetBytes, int packetsPerUrb,
                 int urbCount, jobject callback, jmethodID frameMethod, JavaVM* vm);
    ~UvcIsoStream();

    bool start();
    void stop();
    uint64_t frames() const { return frames_.load(); }
    uint64_t packetErrors() const { return packetErrors_.load(); }
    uint64_t droppedFrames() const { return droppedFrames_.load(); }

private:
    struct Slot {
        usbfs::Urb* urb = nullptr;
        std::vector<uint8_t> storage;
        uint8_t* urbMemory = nullptr;
        size_t urbMemorySize = 0;
    };

    void reapLoop();
    void processUrb(Slot* slot);
    void processPacket(const uint8_t* data, size_t size, uint32_t status);
    struct QueuedFrame { std::vector<uint8_t> data; uint64_t ptsUs=0; bool hadError=false; };
    void emitFrame();
    void callbackLoop();
    static uint64_t monotonicUs();
    bool submit(Slot& slot);
    void discard(Slot& slot);

    int fd_;
    uint8_t endpoint_;
    size_t packetBytes_;
    int packetsPerUrb_;
    int urbCount_;
    jobject callback_;
    jmethodID frameMethod_;
    JavaVM* vm_;

    std::vector<std::unique_ptr<Slot>> slots_;
    std::thread thread_;
    std::thread callbackThread_;
    std::atomic<bool> running_{false};
    std::atomic<int> inFlight_{0}; // URBs submitted to the kernel and not yet reaped
    std::mutex frameQueueMutex_;
    std::condition_variable frameQueueCv_;
    std::deque<QueuedFrame> frameQueue_;
    std::atomic<uint64_t> frames_{0};
    std::atomic<uint64_t> packetErrors_{0};
    std::atomic<uint64_t> droppedFrames_{0};

    std::vector<uint8_t> frame_;
    int currentFid_ = -1;
    bool sawError_ = false;
    uint64_t framePtsUs_ = 0;
};

} // namespace stream4k60
