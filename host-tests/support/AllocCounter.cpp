// SPDX-License-Identifier: GPL-3.0-or-later
//
// Replaces the global allocation functions so a test can prove a code path
// never reaches the heap (OUT-16). Counting is off until a test turns it on,
// so gtest's own allocations stay out of the count.
#include "AllocCounter.h"

#include <cstdlib>
#include <new>

namespace ticktock::test {
std::atomic<long> gAllocations{0};
std::atomic<bool> gCounting{false};
}  // namespace ticktock::test

namespace {
void* countedAllocate(std::size_t size) {
    if (ticktock::test::gCounting.load(std::memory_order_relaxed)) {
        ticktock::test::gAllocations.fetch_add(1, std::memory_order_relaxed);
    }
    void* p = std::malloc(size == 0 ? 1 : size);
    if (p == nullptr) std::abort();
    return p;
}
}  // namespace

void* operator new(std::size_t size) { return countedAllocate(size); }
void* operator new[](std::size_t size) { return countedAllocate(size); }
void* operator new(std::size_t size, const std::nothrow_t&) noexcept { return countedAllocate(size); }
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept { return countedAllocate(size); }
void operator delete(void* p) noexcept { std::free(p); }
void operator delete[](void* p) noexcept { std::free(p); }
void operator delete(void* p, std::size_t) noexcept { std::free(p); }
void operator delete[](void* p, std::size_t) noexcept { std::free(p); }
