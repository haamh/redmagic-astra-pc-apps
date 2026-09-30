#pragma once
#include <atomic>
#include <vector>

namespace stream4k60 {
template<typename T>
class RingBuffer {
public:
    RingBuffer(size_t capacity) : buffer_(capacity), head_(0), tail_(0), capacity_(capacity) {}
    bool push(const T& item) {
        size_t h = head_.load(std::memory_order_relaxed);
        size_t next_h = (h + 1) % capacity_;
        if (next_h == tail_.load(std::memory_order_acquire)) return false;
        buffer_[h] = item;
        head_.store(next_h, std::memory_order_release);
        return true;
    }
    bool pop(T& item) {
        size_t t = tail_.load(std::memory_order_relaxed);
        if (t == head_.load(std::memory_order_acquire)) return false;
        item = buffer_[t];
        tail_.store((t + 1) % capacity_, std::memory_order_release);
        return true;
    }
private:
    std::vector<T> buffer_;
    std::atomic<size_t> head_;
    std::atomic<size_t> tail_;
    size_t capacity_;
};
}
