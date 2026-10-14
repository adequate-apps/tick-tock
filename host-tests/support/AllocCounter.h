// SPDX-License-Identifier: GPL-3.0-or-later
//
// A global operator new that counts while a test asks it to. Linked only
// into the alloc variant; see AllocCounter.cpp.
#pragma once

#include <atomic>

namespace ticktock::test {

extern std::atomic<long> gAllocations;
extern std::atomic<bool> gCounting;

struct CountAllocations {
    CountAllocations() {
        gAllocations.store(0);
        gCounting.store(true);
    }
    ~CountAllocations() { gCounting.store(false); }
    long count() const { return gAllocations.load(); }
};

}  // namespace ticktock::test
