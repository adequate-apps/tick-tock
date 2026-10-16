// SPDX-License-Identifier: GPL-3.0-or-later
// Test process: framed commands in, actual native mono PCM and duck completions out.
#include "control/OutputController.h"
#include "support/FakeDevice.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <vector>

namespace {
constexpr std::uint32_t kMagic = 0x54414631;  // Protocol version 1.
constexpr std::uint32_t kRender = 1, kDuck = 2, kQuit = 3;
constexpr std::int32_t kBlock = 192;

bool readWord(std::uint32_t& value) {
    unsigned char bytes[4];
    if (!std::cin.read(reinterpret_cast<char*>(bytes), sizeof(bytes))) return false;
    value = (static_cast<std::uint32_t>(bytes[0]) << 24) |
            (static_cast<std::uint32_t>(bytes[1]) << 16) |
            (static_cast<std::uint32_t>(bytes[2]) << 8) | bytes[3];
    return true;
}

void word(std::uint32_t value) {
    const unsigned char bytes[] = {static_cast<unsigned char>(value >> 24),
        static_cast<unsigned char>(value >> 16), static_cast<unsigned char>(value >> 8),
        static_cast<unsigned char>(value)};
    std::cout.write(reinterpret_cast<const char*>(bytes), sizeof(bytes));
}

void sample(float value) {
    static_assert(sizeof(float) == sizeof(std::uint32_t));
    std::uint32_t bits;
    std::memcpy(&bits, &value, sizeof(bits));
    word(bits);
}
}  // namespace

int main(int argc, char** argv) {
    if (argc != 6) return 2;
    const int rate = std::atoi(argv[1]);
    const int midi = std::atoi(argv[2]);
    const int voice = std::atoi(argv[3]);
    const int a4 = std::atoi(argv[4]);
    const int consumer = std::atoi(argv[5]);
    if ((rate != 44100 && rate != 48000) || midi < 21 || midi > 108 ||
        voice < 0 || voice > 2 || a4 < 415 || a4 > 466 ||
        (consumer != 1 && consumer != 2)) return 2;

    using namespace ticktock::render;
    using namespace ticktock::control;
    ticktock::test::FakeDevice device;
    device.nativeRate = rate;
    // Synthetic zero-latency presentation clock: frame zero is time zero.
    // A rendered block is presented before its completion is returned to the JVM.
    device.timestampValid = true;
    OutputController controller(device);
    const auto tool = static_cast<OutputTool>(consumer);
    controller.registerTool(tool);
    if (!device.running() || device.opens != 1 || device.starts != 1) return 6;
    ControlEvent opened[4];
    if (controller.drain(opened, 4) != 1 || opened[0].kind != EventKind::Opened || opened[0].a != rate) return 6;
    if (!controller.setA4(a4) || !controller.voiceOn(0, midi, static_cast<Voice>(voice), 0.2f)) return 3;
    word(kMagic);
    std::cout.flush();

    std::uint32_t command;
    while (readWord(command)) {
        if (command == kQuit) {
            // Unregistering the requested consumer must close the sole registered stream.
            // A driver that accidentally registered a different consumer fails here.
            controller.unregisterTool(tool);
            if (device.running() || device.stops != 1 || device.closes != 1) return 6;
            ControlEvent closed[4];
            if (controller.drain(closed, 4) != 1 || closed[0].kind != EventKind::Closed) return 6;
            return 0;
        }
        if (command == kDuck) {
            std::uint32_t bits, generation;
            if (!readWord(bits) || !readWord(generation) || generation == 0) return 4;
            float gain;
            std::memcpy(&gain, &bits, sizeof(gain));
            if (!std::isfinite(gain) || gain < 0.3f || gain > 1.0f) return 4;
            if (!controller.duck(gain, static_cast<std::int32_t>(generation))) return 3;
            continue;
        }
        if (command != kRender) return 4;
        std::uint32_t frames;
        if (!readWord(frames) || frames == 0 || frames > 8192) return 4;
        std::vector<float> mono(frames);
        float stereo[kBlock * kChannels];
        for (std::uint32_t offset = 0; offset < frames;) {
            const auto count = std::min(static_cast<std::uint32_t>(kBlock), frames - offset);
            if (!device.running()) return 6;
            device.render(stereo, static_cast<std::int32_t>(count));
            for (std::uint32_t i = 0; i < count; ++i) mono[offset + i] = stereo[i * kChannels];
            offset += count;
        }
        std::vector<DuckEvent> events;
        ControlEvent batch[16];
        std::int32_t count;
        while ((count = controller.drain(batch, 16)) > 0) {
            for (std::int32_t i = 0; i < count; ++i) {
                if (batch[i].kind != EventKind::DuckSettled) return 6;
                events.push_back(DuckEvent{static_cast<std::int32_t>(batch[i].a), batch[i].c});
            }
        }
        word(kMagic);
        word(frames);
        word(static_cast<std::uint32_t>(events.size()));
        for (const auto& completed : events) {
            word(static_cast<std::uint32_t>(completed.generation));
            word(static_cast<std::uint32_t>(static_cast<std::uint64_t>(completed.frame) >> 32));
            word(static_cast<std::uint32_t>(completed.frame));
        }
        for (float value : mono) sample(value);
        std::cout.flush();
        if (!std::cout) return 5;
    }
    // A truncated command stream is a harness failure, not a completed case.
    return 4;
}
