// SPDX-License-Identifier: GPL-3.0-or-later
//
// The one JNI file. It binds ticktock.engine.NativeBridge to the controller:
// one native method per OutputCommand variant, park and resume for the
// route and call policies, and a drain that hands every OutputEvent to a
// Kotlin listener as numbers (see control/OutputController.h for the
// field layout per event kind). Nothing here runs on the audio thread.
//
// Kotlin side (engine/src/main/kotlin/ticktock/engine/NativeBridge.kt):
//   class NativeBridge { external fun nativeCreate(): Long ... }
// The symbol names below follow from that class name; NativeBridgeTest
// keeps the two lists in step.
#include <jni.h>
#include <semaphore.h>
#include <time.h>

#include <cstdint>

#include "AAudioDevice.h"
#include "control/OutputController.h"

namespace {

using ticktock::AAudioDevice;
using ticktock::control::ControlEvent;
using ticktock::control::Diagnostics;
using ticktock::control::OutputController;
using ticktock::control::ParkReason;
using ticktock::render::Accent;
using ticktock::render::ClickSound;
using ticktock::render::kBeatsMax;
using ticktock::render::MetroParams;
using ticktock::render::MuteReason;
using ticktock::render::OutputTool;
using ticktock::render::Voice;

// The device, the controller and the wake-up the device posts, as one
// object behind the Long handle Kotlin holds.
struct Bridge {
    sem_t wake{};
    AAudioDevice device;
    OutputController controller;

    Bridge() : device(&Bridge::onWake, this), controller(device) { sem_init(&wake, 0, 0); }
    ~Bridge() { sem_destroy(&wake); }

    static void onWake(void* context) {
        auto* self = static_cast<Bridge*>(context);
        // Only when there is something to drain; the post is a futex wake,
        // never a wait, so the audio thread cannot block here.
        if (self->controller.engine().beats().size() > 0 || self->controller.hasPendingError()) sem_post(&self->wake);
    }

    void signal() { sem_post(&wake); }
};

Bridge* bridge(jlong handle) { return reinterpret_cast<Bridge*>(static_cast<std::intptr_t>(handle)); }

MetroParams metroParams(JNIEnv* env, jint bpm, jint beatsPerBar, jint beatUnit, jboolean compoundPulse,
                        jintArray accents, jint subdivision, jint click, jfloat gain, jint barsOn, jint barsOff, jint beatLimit, jint generation) {
    MetroParams p;
    p.bpm = bpm;
    p.beatsPerBar = beatsPerBar;
    p.beatUnit = beatUnit;
    p.compoundPulse = compoundPulse == JNI_TRUE;
    p.subdivision = subdivision;
    p.click = static_cast<ClickSound>(click);
    p.gain = gain;
    p.barsOn = barsOn;
    p.barsOff = barsOff;
    p.beatLimit = beatLimit;
    p.generation = generation;
    for (Accent& a : p.accents) a = Accent::Normal;
    if (accents != nullptr) {
        const jsize n = env->GetArrayLength(accents);
        jint buffer[kBeatsMax];
        const jsize count = n < kBeatsMax ? n : kBeatsMax;
        env->GetIntArrayRegion(accents, 0, count, buffer);
        for (jsize i = 0; i < count; ++i) p.accents[i] = static_cast<Accent>(buffer[i]);
    }
    return p;
}

}  // namespace

// Defined in engine.cpp with C linkage.
extern "C" std::int32_t ticktock_engine_abi_version();

extern "C" {

JNIEXPORT jint JNICALL Java_ticktock_engine_NativeBridge_nativeAbiVersion(JNIEnv*, jobject) {
    return ticktock_engine_abi_version();
}

JNIEXPORT jlong JNICALL Java_ticktock_engine_NativeBridge_nativeCreate(JNIEnv*, jobject) {
    return static_cast<jlong>(reinterpret_cast<std::intptr_t>(new Bridge()));
}

JNIEXPORT void JNICALL Java_ticktock_engine_NativeBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    delete bridge(handle);
}

JNIEXPORT void JNICALL Java_ticktock_engine_NativeBridge_nativeRegister(JNIEnv*, jobject, jlong handle, jint tool) {
    Bridge* b = bridge(handle);
    b->controller.registerTool(static_cast<OutputTool>(tool));
    b->signal();
}

JNIEXPORT void JNICALL Java_ticktock_engine_NativeBridge_nativeUnregister(JNIEnv*, jobject, jlong handle, jint tool) {
    Bridge* b = bridge(handle);
    b->controller.unregisterTool(static_cast<OutputTool>(tool));
    b->signal();
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeVoiceOn(JNIEnv*, jobject, jlong handle, jint slot,
                                                                           jint midi, jint voice, jfloat gain) {
    return bridge(handle)->controller.voiceOn(slot, midi, static_cast<Voice>(voice), gain) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeVoiceOff(JNIEnv*, jobject, jlong handle, jint slot) {
    return bridge(handle)->controller.voiceOff(slot) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeVoiceGain(JNIEnv*, jobject, jlong handle, jint slot,
                                                                             jfloat gain) {
    return bridge(handle)->controller.voiceGain(slot, gain) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeSetA4(JNIEnv*, jobject, jlong handle, jdouble hz) {
    return bridge(handle)->controller.setA4(hz) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeDuck(JNIEnv*, jobject, jlong handle, jfloat gain) {
    return bridge(handle)->controller.duck(gain) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeMetroStart(
    JNIEnv* env, jobject, jlong handle, jint bpm, jint beatsPerBar, jint beatUnit, jboolean compoundPulse,
    jintArray accents, jint subdivision, jint click, jfloat gain, jint barsOn, jint barsOff, jint beatLimit, jint generation) {
    const MetroParams p = metroParams(env, bpm, beatsPerBar, beatUnit, compoundPulse, accents, subdivision, click, gain,
                                      barsOn, barsOff, beatLimit, generation);
    return bridge(handle)->controller.metroStart(p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeMetroUpdate(
    JNIEnv* env, jobject, jlong handle, jint bpm, jint beatsPerBar, jint beatUnit, jboolean compoundPulse,
    jintArray accents, jint subdivision, jint click, jfloat gain, jint barsOn, jint barsOff, jint beatLimit, jint generation) {
    const MetroParams p = metroParams(env, bpm, beatsPerBar, beatUnit, compoundPulse, accents, subdivision, click, gain,
                                      barsOn, barsOff, beatLimit, generation);
    return bridge(handle)->controller.metroUpdate(p) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeMetroStop(JNIEnv*, jobject, jlong handle) {
    return bridge(handle)->controller.metroStop() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_ticktock_engine_NativeBridge_nativeMute(JNIEnv*, jobject, jlong handle, jint reason,
                                                                        jboolean active) {
    return bridge(handle)->controller.mute(static_cast<MuteReason>(reason), active == JNI_TRUE) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_ticktock_engine_NativeBridge_nativePark(JNIEnv*, jobject, jlong handle, jint reason) {
    Bridge* b = bridge(handle);
    b->controller.park(static_cast<ParkReason>(reason));
    b->signal();
}

JNIEXPORT void JNICALL Java_ticktock_engine_NativeBridge_nativeResume(JNIEnv*, jobject, jlong handle) {
    Bridge* b = bridge(handle);
    b->controller.resume();
    b->signal();
}

// Waits up to timeoutMillis for something to report, then hands every
// pending event to listener.onEvent(kind, a, b, c, d, tools, ints).
// Returns the number delivered.
JNIEXPORT jint JNICALL Java_ticktock_engine_NativeBridge_nativeDrainEvents(JNIEnv* env, jobject, jlong handle,
                                                                           jobject listener, jint timeoutMillis) {
    Bridge* b = bridge(handle);
    if (timeoutMillis > 0 && !b->controller.hasEvents()) {
        timespec deadline{};
        clock_gettime(CLOCK_REALTIME, &deadline);
        deadline.tv_sec += timeoutMillis / 1000;
        deadline.tv_nsec += static_cast<long>(timeoutMillis % 1000) * 1000000L;
        if (deadline.tv_nsec >= 1000000000L) {
            deadline.tv_sec += 1;
            deadline.tv_nsec -= 1000000000L;
        }
        sem_timedwait(&b->wake, &deadline);
    }
    // Posts can pile up faster than drains; the pile means nothing by now.
    while (sem_trywait(&b->wake) == 0) {}

    ControlEvent events[64];
    const std::int32_t n = b->controller.drain(events, 64);
    if (n == 0 || listener == nullptr) return 0;

    jclass cls = env->GetObjectClass(listener);
    jmethodID onEvent = env->GetMethodID(cls, "onEvent", "(IJJJJI[I)V");
    env->DeleteLocalRef(cls);
    if (onEvent == nullptr) return 0;

    for (std::int32_t i = 0; i < n; ++i) {
        const ControlEvent& e = events[i];
        jintArray ints = nullptr;
        if (e.intCount > 0) {
            ints = env->NewIntArray(e.intCount);
            if (ints != nullptr) env->SetIntArrayRegion(ints, 0, e.intCount, e.ints);
        }
        env->CallVoidMethod(listener, onEvent, static_cast<jint>(e.kind), static_cast<jlong>(e.a),
                            static_cast<jlong>(e.b), static_cast<jlong>(e.c), static_cast<jlong>(e.d),
                            static_cast<jint>(e.tools), ints);
        if (ints != nullptr) env->DeleteLocalRef(ints);
        if (env->ExceptionCheck()) return i + 1;
    }
    return n;
}

JNIEXPORT jint JNICALL Java_ticktock_engine_NativeBridge_nativeLatencyMillis(JNIEnv*, jobject, jlong handle) {
    return bridge(handle)->controller.latencyMillis();
}

// [lateBeatsDropped, underruns, bufferBursts, growthSteps, droppedCommands,
//  droppedBeats, framesRendered, sampleRate, streamOpen, parked]
JNIEXPORT jlongArray JNICALL Java_ticktock_engine_NativeBridge_nativeDiagnostics(JNIEnv* env, jobject, jlong handle) {
    const Diagnostics d = bridge(handle)->controller.diagnostics();
    const jlong values[10] = {d.lateBeatsDropped, d.underruns,       d.bufferBursts,  d.growthSteps,
                              d.droppedCommands,  d.droppedBeats,    d.framesRendered, d.sampleRate,
                              d.streamOpen ? 1 : 0, d.parked ? 1 : 0};
    jlongArray out = env->NewLongArray(10);
    if (out != nullptr) env->SetLongArrayRegion(out, 0, 10, values);
    return out;
}

}  // extern "C"
