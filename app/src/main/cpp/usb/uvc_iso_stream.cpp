#include "uvc_iso_stream.h"
#include <cerrno>
#include <cstring>
#include <poll.h>
#include <sys/ioctl.h>
#include <time.h>
#include <unistd.h>
#include <algorithm>
#include <chrono>

namespace stream4k60 {

UvcIsoStream::UvcIsoStream(int fd, uint8_t endpoint, size_t packetBytes, int packetsPerUrb,
                           int urbCount, jobject callback, jmethodID frameMethod, JavaVM* vm)
    : fd_(fd), endpoint_(endpoint), packetBytes_(packetBytes),
      packetsPerUrb_(std::clamp(packetsPerUrb, 4, 128)),
      urbCount_(std::clamp(urbCount, 4, 32)), callback_(callback),
      frameMethod_(frameMethod), vm_(vm) {}

UvcIsoStream::~UvcIsoStream() {
    stop();
    if (callback_ && vm_) {
        JNIEnv* env = nullptr;
        bool attached = false;
        if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
            if (vm_->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
        if (env) env->DeleteGlobalRef(callback_);
        if (attached) vm_->DetachCurrentThread();
        callback_ = nullptr;
    }
}

bool UvcIsoStream::start() {
    if (running_.exchange(true)) return false;
    if (fd_ < 0 || packetBytes_ == 0 || !callback_ || !frameMethod_ || !vm_) {
        running_ = false;
        return false;
    }

    slots_.reserve(static_cast<size_t>(urbCount_));
    const size_t urbBytes = sizeof(usbfs::Urb) +
                            sizeof(usbfs::IsoPacketDesc) * static_cast<size_t>(packetsPerUrb_);
    for (int i = 0; i < urbCount_; ++i) {
        auto slot = std::make_unique<Slot>();
        slot->urbMemorySize = urbBytes;
        slot->urbMemory = static_cast<uint8_t*>(calloc(1, urbBytes));
        if (!slot->urbMemory) { running_ = false; return false; }
        slot->urb = reinterpret_cast<usbfs::Urb*>(slot->urbMemory);
        slot->storage.resize(packetBytes_ * static_cast<size_t>(packetsPerUrb_));
        slot->urb->type = usbfs::URB_TYPE_ISO;
        slot->urb->endpoint = endpoint_;
        slot->urb->flags = usbfs::URB_ISO_ASAP;
        slot->urb->buffer = slot->storage.data();
        slot->urb->buffer_length = static_cast<int32_t>(slot->storage.size());
        slot->urb->number_of_packets = packetsPerUrb_;
        slot->urb->usercontext = slot.get();
        auto* desc = reinterpret_cast<usbfs::IsoPacketDesc*>(slot->urbMemory + sizeof(usbfs::Urb));
        for (int p = 0; p < packetsPerUrb_; ++p) desc[p].length = static_cast<uint32_t>(packetBytes_);
        slots_.push_back(std::move(slot));
    }

    for (auto& s : slots_) {
        if (!submit(*s)) {
            stop();
            return false;
        }
    }
    frame_.reserve(2 * 1024 * 1024);
    thread_ = std::thread(&UvcIsoStream::reapLoop, this);
    callbackThread_ = std::thread(&UvcIsoStream::callbackLoop, this);
    return true;
}

bool UvcIsoStream::submit(Slot& slot) {
    auto* desc = reinterpret_cast<usbfs::IsoPacketDesc*>(slot.urbMemory + sizeof(usbfs::Urb));
    for (int p = 0; p < packetsPerUrb_; ++p) {
        desc[p].actual_length = 0;
        desc[p].status = 0;
        desc[p].length = static_cast<uint32_t>(packetBytes_);
    }
    slot.urb->status = 0;
    slot.urb->actual_length = 0;
    slot.urb->error_count = 0;
    if (ioctl(fd_, USBDEVFS_SUBMITURB, slot.urb) < 0) return false;
    inFlight_++;
    return true;
}

void UvcIsoStream::discard(Slot& slot) {
    if (!slot.urb) return;
    ioctl(fd_, USBDEVFS_DISCARDURB, slot.urb);
}

void UvcIsoStream::stop() {
    const bool wasRunning = running_.exchange(false);
    if (wasRunning) for (auto& s : slots_) discard(*s);
    frameQueueCv_.notify_all();
    if (thread_.joinable()) thread_.join();
    if (callbackThread_.joinable()) callbackThread_.join();
    if (usbfs::reapOutstanding(fd_, inFlight_)) {
        for (auto& s : slots_) {
            free(s->urbMemory);
            s->urbMemory = nullptr;
            s->urb = nullptr;
        }
    } else {
        // The kernel still owns some URBs: leak their memory rather than let it write into freed buffers.
        for (auto& s : slots_) (void)s.release();
    }
    inFlight_ = 0;
    slots_.clear();
    frame_.clear();
    { std::lock_guard<std::mutex> lock(frameQueueMutex_); frameQueue_.clear(); }
    currentFid_ = -1;
}

uint64_t UvcIsoStream::monotonicUs() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<uint64_t>(ts.tv_sec) * 1000000ull + static_cast<uint64_t>(ts.tv_nsec) / 1000ull;
}

void UvcIsoStream::reapLoop() {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            running_ = false;
            return;
        }
        attached = true;
    }

    while (running_) {
        void* context = nullptr;
        int rc = ioctl(fd_, USBDEVFS_REAPURBNDELAY, &context);
        if (rc < 0) {
            if (errno == EAGAIN || errno == EINTR) {
                struct pollfd pfd{fd_, POLLIN, 0};
                poll(&pfd, 1, 2);
                continue;
            }
            running_ = false;
            break;
        }
        inFlight_--;
        auto* slot = reinterpret_cast<Slot*>(context);
        if (!slot || !slot->urb) continue;
        processUrb(slot);
        if (running_ && !submit(*slot)) {
            running_ = false;
            break;
        }
    }
    if (attached) vm_->DetachCurrentThread();
}

void UvcIsoStream::processUrb(Slot* slot) {
    auto* desc = reinterpret_cast<usbfs::IsoPacketDesc*>(slot->urbMemory + sizeof(usbfs::Urb));
    size_t base = 0;
    for (int p = 0; p < packetsPerUrb_; ++p) {
        const auto& d = desc[p];
        const size_t available = std::min<size_t>(d.actual_length, packetBytes_);
        if (d.status != 0) {
            packetErrors_++;
            sawError_ = true;
        }
        if (available) processPacket(slot->storage.data() + base, available, d.status);
        base += packetBytes_;
    }
}

void UvcIsoStream::processPacket(const uint8_t* data, size_t size, uint32_t status) {
    if (size < 2) return;
    const uint8_t headerLen = data[0];
    if (headerLen < 2 || headerLen > size) return;
    const uint8_t flags = data[1];
    if ((flags & 0x40) != 0) {
        packetErrors_++;
        sawError_ = true;
    }
    const int fid = flags & 0x01;
    if (currentFid_ < 0) currentFid_ = fid;
    if (fid != currentFid_) {
        if (!frame_.empty()) emitFrame();
        frame_.clear();
        currentFid_ = fid;
        sawError_ = false;
    }
    const size_t payloadOffset = headerLen;
    if (payloadOffset < size) {
        // UVC payloads are allowed to be split across any number of USB packets/URBs.
        // Keep one bounded frame accumulator so malformed devices cannot exhaust memory.
        constexpr size_t kMaxFrame = 32ull * 1024ull * 1024ull;
        if (frame_.size() + (size - payloadOffset) > kMaxFrame) {
            frame_.clear();
            droppedFrames_++;
            sawError_ = true;
        } else {
            frame_.insert(frame_.end(), data + payloadOffset, data + size);
        }
    }
    if (flags & 0x04) {
        if (headerLen >= 6) {
            const uint32_t pts = static_cast<uint32_t>(data[2]) |
                                 (static_cast<uint32_t>(data[3]) << 8) |
                                 (static_cast<uint32_t>(data[4]) << 16) |
                                 (static_cast<uint32_t>(data[5]) << 24);
            (void)pts; // UVC PTS clock frequency is device-specific; host monotonic timestamp is used.
        }
    }
    if (flags & 0x02) {
        if (!frame_.empty()) emitFrame();
        frame_.clear();
    }
}

void UvcIsoStream::emitFrame() {
    if (frame_.empty()) return;
    QueuedFrame frame;
    frame.data = std::move(frame_);
    frame.ptsUs = monotonicUs();
    frame.hadError = sawError_;
    frame_.clear();
    frame_.reserve(2 * 1024 * 1024);
    {
        std::lock_guard<std::mutex> lock(frameQueueMutex_);
        constexpr size_t kMaxQueuedFrames = 4;
        if (frameQueue_.size() >= kMaxQueuedFrames) {
            frameQueue_.pop_front();
            droppedFrames_++;
        }
        frameQueue_.push_back(std::move(frame));
    }
    frameQueueCv_.notify_one();
    sawError_ = false;
}

void UvcIsoStream::callbackLoop() {
    JNIEnv* env = nullptr;
    bool attached = false;
    if (vm_->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (vm_->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    while (running_) {
        QueuedFrame frame;
        {
            std::unique_lock<std::mutex> lock(frameQueueMutex_);
            frameQueueCv_.wait_for(lock, std::chrono::milliseconds(10), [&] { return !frameQueue_.empty() || !running_; });
            if (frameQueue_.empty()) { if (!running_) break; else continue; }
            frame = std::move(frameQueue_.front());
            frameQueue_.pop_front();
        }
        jobject direct = env->NewDirectByteBuffer(frame.data.data(), static_cast<jlong>(frame.data.size()));
        if (direct) {
            env->CallVoidMethod(callback_, frameMethod_, direct, static_cast<jlong>(frame.ptsUs), static_cast<jint>(frame.hadError ? 1 : 0));
            env->DeleteLocalRef(direct);
        }
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            droppedFrames_++;
        } else {
            frames_++;
        }
    }
    if (attached) vm_->DetachCurrentThread();
}

} // namespace stream4k60
