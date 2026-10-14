// SPDX-License-Identifier: GPL-3.0-or-later
//
// Single-producer, single-consumer ring of plain structs with a fixed
// capacity. One side is always the render callback, so there is no heap and
// no lock anywhere in here: two atomics with acquire/release ordering carry
// the hand-off. Capacity must be a power of two so the index wraps by mask.
#pragma once

#include <atomic>
#include <cstdint>

namespace ticktock::render {

template <typename T, std::uint32_t Capacity>
class SpscQueue {
    static_assert(Capacity >= 2 && (Capacity & (Capacity - 1)) == 0, "capacity must be a power of two");

public:
    // Producer side. Returns false when the ring is full; the item is dropped
    // and the caller decides what that means (the bridge reports it).
    bool tryPush(const T& item) {
        const std::uint32_t head = head_.load(std::memory_order_relaxed);
        const std::uint32_t tail = tail_.load(std::memory_order_acquire);
        if (head - tail == Capacity) return false;
        items_[head & kMask] = item;
        head_.store(head + 1, std::memory_order_release);
        return true;
    }

    // Consumer side. Returns false when the ring is empty.
    bool tryPop(T& out) {
        const std::uint32_t tail = tail_.load(std::memory_order_relaxed);
        const std::uint32_t head = head_.load(std::memory_order_acquire);
        if (head == tail) return false;
        out = items_[tail & kMask];
        tail_.store(tail + 1, std::memory_order_release);
        return true;
    }

    std::uint32_t size() const {
        return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_acquire);
    }

    static constexpr std::uint32_t capacity() { return Capacity; }

private:
    static constexpr std::uint32_t kMask = Capacity - 1;
    T items_[Capacity] = {};
    // Padded apart so the producer's and consumer's cache lines never ping-pong.
    alignas(64) std::atomic<std::uint32_t> head_{0};
    alignas(64) std::atomic<std::uint32_t> tail_{0};
};

}  // namespace ticktock::render
