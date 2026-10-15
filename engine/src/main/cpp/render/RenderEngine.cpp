// SPDX-License-Identifier: GPL-3.0-or-later
#include "RenderEngine.h"

namespace ticktock::render {

void RenderEngine::onStreamStart(double sampleRate) {
    sampleRate_ = sampleRate > 0.0 ? sampleRate : 48000.0;
    rampStep_ = static_cast<float>(1.0 / (kGainRampSeconds * sampleRate_));
    position_.store(0, std::memory_order_release);
    voices_.prepare(sampleRate_);
    clicks_.prepare(sampleRate_);
    generator_.onStreamStart(sampleRate_, 0);
    gate_ = 0.0f;
    duck_ = duckTarget_ = 1.0f;
    duckGeneration_ = 0;
}

void RenderEngine::apply(const Command& cmd) {
    switch (cmd.kind) {
        case CommandKind::VoiceOn: voices_.noteOn(cmd.slot, cmd.midi, cmd.voice, cmd.gain); break;
        case CommandKind::VoiceOff: voices_.noteOff(cmd.slot); break;
        case CommandKind::VoiceGain: voices_.setGain(cmd.slot, cmd.gain); break;
        case CommandKind::SetA4: voices_.setA4(cmd.a4); break;
        case CommandKind::Duck: {
            // POL-26: 0.3 is the floor; the detector never asks for silence.
            const float g = cmd.gain < 0.3f ? 0.3f : (cmd.gain > 1.0f ? 1.0f : cmd.gain);
            duckTarget_ = g;
            duckGeneration_ = cmd.generation;
            break;
        }
        case CommandKind::MetroStart: generator_.start(cmd.metro, position_.load(std::memory_order_relaxed)); break;
        case CommandKind::MetroUpdate:
            if (generator_.running() || generator_.finished()) generator_.update(cmd.metro, position_.load(std::memory_order_relaxed));
            else generator_.start(cmd.metro, position_.load(std::memory_order_relaxed));
            break;
        case CommandKind::MetroStop: generator_.stop(); break;
        case CommandKind::Mute: {
            const std::uint32_t bit = 1u << static_cast<std::uint32_t>(cmd.muteReason);
            if (cmd.active) muteMask_ |= bit; else muteMask_ &= ~bit;
            break;
        }
    }
}

void RenderEngine::rampMaster() {
    if (duck_ < duckTarget_) { duck_ += rampStep_; if (duck_ > duckTarget_) duck_ = duckTarget_; }
    else if (duck_ > duckTarget_) { duck_ -= rampStep_; if (duck_ < duckTarget_) duck_ = duckTarget_; }
    if (gate_ < gateTarget_) { gate_ += rampStep_; if (gate_ > gateTarget_) gate_ = gateTarget_; }
    else if (gate_ > gateTarget_) { gate_ -= rampStep_; if (gate_ < gateTarget_) gate_ = gateTarget_; }
}

void RenderEngine::renderSlice(float* interleaved, std::int32_t frames) {
    const std::int64_t blockStart = position_.load(std::memory_order_relaxed);
    for (std::int32_t i = 0; i < frames; ++i) mono_[i] = 0.0f;

    const std::int32_t count = generator_.render(blockStart, frames, clicks_, sliceEvents_, kMaxBeatsPerSlice);
    for (std::int32_t i = 0; i < count; ++i) {
        if (!beats_.tryPush(sliceEvents_[i])) droppedBeats_.fetch_add(1, std::memory_order_relaxed);
    }
    clicks_.render(mono_, frames);
    voices_.render(mono_, frames);

    // OUT-10: audible only while no mute reason is active; the gate ramps so
    // a mute never clicks, and the clamp keeps a loud mix inside the bus.
    gateTarget_ = muteMask_ == 0 ? 1.0f : 0.0f;
    for (std::int32_t i = 0; i < frames; ++i) {
        rampMaster();
        if (duckGeneration_ != 0 && duck_ == duckTarget_) {
            // A lost acknowledgement leaves the detector excluding unproven leakage.
            ducks_.tryPush(DuckEvent{duckGeneration_, blockStart + i});
            duckGeneration_ = 0;
        }
        float s = mono_[i] * duck_ * gate_;
        if (s > 1.0f) s = 1.0f; else if (s < -1.0f) s = -1.0f;
        interleaved[i * kChannels] = s;
        interleaved[i * kChannels + 1] = s;
    }

    position_.store(blockStart + frames, std::memory_order_release);
    lateDropped_.store(generator_.lateDropped(), std::memory_order_release);
}

void RenderEngine::render(float* interleaved, std::int32_t frames) {
    // Commands take effect at the block they arrive in, so an A4 change or
    // a tempo edit is heard at the next block and never mid-slice.
    Command cmd;
    while (commands_.tryPop(cmd)) apply(cmd);

    std::int32_t done = 0;
    while (done < frames) {
        const std::int32_t n = frames - done > kMaxSlice ? kMaxSlice : frames - done;
        renderSlice(interleaved + static_cast<std::ptrdiff_t>(done) * kChannels, n);
        done += n;
    }
}

}  // namespace ticktock::render
