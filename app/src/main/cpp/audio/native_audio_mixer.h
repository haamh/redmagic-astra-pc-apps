#pragma once

#include <aaudio/AAudio.h>
#include <jni.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace stream4k60 {

class NativeAudioMixer {
public:
    NativeAudioMixer(JavaVM* vm, jobject callback, int sampleRate, int channels, int blockFrames, int monitorDeviceId, bool monitorEnabled);
    ~NativeAudioMixer();
    bool start();
    bool addInput(const std::string& sourceId, int32_t deviceId, float volume, float balance, bool muted, int monitoring, int syncOffsetMs);
    bool addExternalInput(const std::string& sourceId, float volume, float balance, bool muted, int monitoring, int syncOffsetMs, bool solo);
    bool pushExternalPcm(const std::string& sourceId, const float* samples, size_t frames, int channels, int sampleRate, uint64_t ptsUs);
    bool removeInput(const std::string& sourceId);
    bool setInputConfig(const std::string& sourceId, float volume, float balance, bool muted, int monitoring, int syncOffsetMs, bool solo);
    void stop();
    void setMonitorVolume(float volume);
    void setMonitorMuted(bool muted);
    float getPeak(const std::string& sourceId) const;

private:
    struct Ring {
        explicit Ring(size_t capacityFrames, int sampleRate);
        bool push(const float* data, size_t frames, int channels, uint64_t ptsUs);
        size_t availableFrames() const;
        bool pop(float* dst, size_t frames);
        size_t discard(size_t frames);
        uint64_t startPtsUs() const;
        void clear();
        std::vector<float> data;
        std::atomic<size_t> head{0}, tail{0};
        std::atomic<uint64_t> startPts{0};
        size_t capacityFrames;
        int channels = 2;
        int sampleRate = 48000;
    };

    struct Input {
        std::string id;
        int32_t deviceId = -1;
        bool external = false;
        int channels = 2;
        int sampleRate = 48000;
        double resamplePhase = 0.0;
        bool haveResampleHistory = false;
        float lastSampleL = 0.0f, lastSampleR = 0.0f;
        std::vector<float> resampleScratch;
        AAudioStream* stream = nullptr;
        std::unique_ptr<Ring> ring;
        std::atomic<float> volume{1.0f};
        std::atomic<float> balance{0.0f};
        std::atomic<bool> muted{false};
        std::atomic<int> monitoring{0};
        std::atomic<int> syncOffsetMs{0};
        std::atomic<bool> solo{false};
        std::atomic<bool> active{true};
        std::atomic<float> peak{0.0f};
        NativeAudioMixer* owner = nullptr;
    };

    static aaudio_data_callback_result_t dataCallback(AAudioStream*, void*, void*, int32_t);
    static void errorCallback(AAudioStream*, void*, aaudio_result_t);
    static aaudio_data_callback_result_t monitorCallback(AAudioStream*, void*, void*, int32_t);
    void capture(Input& input, void* audioData, int32_t frames);
    void mixLoop();
    bool openInput(Input& input);
    void closeInput(Input& input);
    bool openMonitor();
    void closeMonitor();
    uint64_t timestampFor(AAudioStream* stream, int frames, int sourceRate) const;
    size_t resampleToProgramBus(Input& input, const float* src, int frames, float* dst, size_t dstCapacity);
    JNIEnv* envForThread(bool& attached) const;

    JavaVM* vm_ = nullptr;
    jobject callback_ = nullptr;
    jmethodID callbackMethod_ = nullptr;
    int sampleRate_ = 48000;
    int channels_ = 2;
    int blockFrames_ = 480;
    int monitorDeviceId_ = -1;
    std::atomic<bool> running_{false};
    std::atomic<bool> monitorEnabled_{false};
    AAudioStream* monitorStream_ = nullptr;
    std::unique_ptr<Ring> monitorRing_;
    std::atomic<float> monitorVolume_{1.0f};
    std::atomic<bool> monitorMuted_{false};
    mutable std::mutex inputsMutex_;
    std::vector<std::unique_ptr<Input>> inputs_;
    std::thread mixThread_;
};

} // namespace stream4k60
