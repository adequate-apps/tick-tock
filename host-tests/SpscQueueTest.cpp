// SPDX-License-Identifier: GPL-3.0-or-later
#include "render/SpscQueue.h"

#include <gtest/gtest.h>

#include <thread>
#include <vector>

using ticktock::render::SpscQueue;

TEST(SpscQueue, keepsOrderAndRefusesWhenFull) {
    SpscQueue<int, 4> q;
    EXPECT_EQ(0u, q.size());
    for (int i = 0; i < 4; ++i) EXPECT_TRUE(q.tryPush(i));
    EXPECT_FALSE(q.tryPush(99));
    EXPECT_EQ(4u, q.size());
    int v = -1;
    for (int i = 0; i < 4; ++i) {
        ASSERT_TRUE(q.tryPop(v));
        EXPECT_EQ(i, v);
    }
    EXPECT_FALSE(q.tryPop(v));
}

TEST(SpscQueue, wrapsAroundManyTimes) {
    SpscQueue<int, 8> q;
    int expected = 0;
    for (int round = 0; round < 1000; ++round) {
        for (int i = 0; i < 5; ++i) ASSERT_TRUE(q.tryPush(round * 5 + i));
        int v = 0;
        for (int i = 0; i < 5; ++i) {
            ASSERT_TRUE(q.tryPop(v));
            EXPECT_EQ(expected++, v);
        }
    }
}

TEST(SpscQueue, handsOffBetweenTwoThreadsWithoutLoss) {
    SpscQueue<long, 256> q;
    const long count = 200000;
    std::thread producer([&] {
        for (long i = 0; i < count;) {
            if (q.tryPush(i)) ++i;
        }
    });
    std::vector<long> seen;
    seen.reserve(static_cast<std::size_t>(count));
    long v = 0;
    while (static_cast<long>(seen.size()) < count) {
        if (q.tryPop(v)) seen.push_back(v);
    }
    producer.join();
    for (long i = 0; i < count; ++i) ASSERT_EQ(i, seen[static_cast<std::size_t>(i)]);
}
