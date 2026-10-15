// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ticktock.core.contracts.ForegroundOwner
import ticktock.core.contracts.NoForegroundOwner
import ticktock.core.contracts.OutputCommand
import ticktock.core.contracts.OutputEvent
import ticktock.core.contracts.OutputInfo
import ticktock.core.contracts.OutputPort
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.ParkReason
import ticktock.core.contracts.RouteClass

/**
 * An [OutputPort] that records every command and lets a test play the engine:
 * open, unplug, change rate, fail, beat. It keeps a registry of tools so the
 * open/close rule (OUT-01) and the [ForegroundOwner] hooks on its two
 * transitions (BGH-01) are observable without the real engine.
 */
class FakeOutputPort(private val foregroundOwner: ForegroundOwner = NoForegroundOwner) : OutputPort {
    val sent = mutableListOf<OutputCommand>()
    val registered = linkedSetOf<OutputTool>()

    /** Events are replayed so a collector that starts late still sees them. */
    private val eventFlow = MutableSharedFlow<OutputEvent>(replay = REPLAY, extraBufferCapacity = REPLAY)
    val emitted = mutableListOf<OutputEvent>()

    override val events: Flow<OutputEvent> get() = eventFlow
    override val info = MutableStateFlow<OutputInfo?>(null)

    override fun send(cmd: OutputCommand) {
        sent += cmd
        // The `when` has no else branch on purpose: a new command variant fails
        // to compile until this fake learns about it.
        when (cmd) {
            is OutputCommand.Register -> {
                val wasEmpty = registered.isEmpty()
                registered += cmd.tool
                if (wasEmpty) {
                    // The owner's session brackets the stream: started before open.
                    foregroundOwner.onToolsStarted()
                    open(DEFAULT_INFO)
                }
            }
            is OutputCommand.Unregister -> {
                val wasRegistered = registered.remove(cmd.tool)
                if (wasRegistered && registered.isEmpty()) {
                    close()
                    foregroundOwner.onToolsStopped()
                }
            }
            is OutputCommand.VoiceOn,
            is OutputCommand.VoiceOff,
            is OutputCommand.VoiceGain,
            is OutputCommand.SetA4,
            is OutputCommand.Duck,
            is OutputCommand.MetroStart,
            is OutputCommand.MetroUpdate,
            OutputCommand.MetroStop,
            is OutputCommand.Mute,
            -> Unit
        }
    }

    fun emit(event: OutputEvent) {
        emitted += event
        check(eventFlow.tryEmit(event)) { "fake event buffer full" }
    }

    fun open(info: OutputInfo) {
        this.info.value = info
        emit(OutputEvent.Opened(info))
    }

    fun close() {
        info.value = null
        emit(OutputEvent.Closed)
    }

    /** Headphones pulled: every registered tool parks and the stream closes. */
    fun unplug() {
        emit(OutputEvent.Parked(ParkReason.HEADPHONES_DISCONNECTED, registered.toSet()))
        info.value = null
    }

    /** An incoming call: every registered tool parks and nothing restarts until the player acts. */
    fun incomingCall() {
        emit(OutputEvent.Parked(ParkReason.INCOMING_CALL, registered.toSet()))
        info.value = null
    }

    /** Route returned, possibly at another rate: the parked set restarts. */
    fun replug(sampleRate: Int, routeClass: RouteClass = RouteClass.HEADPHONES) {
        val next = DEFAULT_INFO.copy(sampleRate = sampleRate, routeClass = routeClass)
        info.value = next
        emit(OutputEvent.Resumed(registered.toSet(), next))
    }

    fun fail(deviceName: String, refusedRates: List<Int>) {
        info.value = null
        emit(OutputEvent.Failed(deviceName, refusedRates))
    }

    fun beat(
        index: Long, beatInBar: Int, frame: Long, nanos: Long,
        bar: Long = 0, silentBar: Boolean = false, generation: Int = 0, audible: Boolean = true,
    ) {
        emit(OutputEvent.Beat(index, beatInBar, frame, nanos, bar, silentBar, generation, audible))
    }

    companion object {
        const val REPLAY = 1024
        val DEFAULT_INFO = OutputInfo(
            sampleRate = 48_000,
            framesPerBlock = 240,
            routeClass = RouteClass.SPEAKER,
            deviceName = "Fake speaker",
            latencyMillis = 20,
        )
    }
}
