// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ticktock.core.contracts.ForegroundOwner
import ticktock.core.contracts.OutputTool
import ticktock.core.contracts.fakes.FakeForegroundOwner

class OutputRegistryTest {
    private val owner = FakeForegroundOwner()
    private val registry = OutputRegistry(owner)
    private val trace = mutableListOf<String>()

    private fun register(tool: OutputTool) = registry.register(tool) { trace += "open" }
    private fun unregister(tool: OutputTool) = registry.unregister(tool) { trace += "close" }

    @Test
    fun `OUT-01 the stream opens on the first tool and closes on the last`() {
        assertTrue(register(OutputTool.METRONOME))
        assertFalse(register(OutputTool.DRONE))
        assertFalse(register(OutputTool.DRONE))
        assertEquals(listOf("open"), trace)
        assertEquals(setOf(OutputTool.METRONOME, OutputTool.DRONE), registry.registered)

        assertFalse(unregister(OutputTool.METRONOME))
        assertEquals(listOf("open"), trace)
        assertTrue(unregister(OutputTool.DRONE))
        assertEquals(listOf("open", "close"), trace)
        assertTrue(registry.isEmpty)
    }

    @Test
    fun `BGH-01 the owner hears started before the open and stopped after the close`() {
        val ordered = mutableListOf<String>()
        val bracketing = object : ForegroundOwner {
            override fun onToolsStarted() { ordered += "started" }
            override fun onToolsStopped() { ordered += "stopped" }
        }
        val registry = OutputRegistry(bracketing)

        registry.register(OutputTool.DRONE) { ordered += "open" }
        registry.register(OutputTool.PITCH_PIPE) { ordered += "open" }
        registry.unregister(OutputTool.DRONE) { ordered += "close" }
        registry.unregister(OutputTool.PITCH_PIPE) { ordered += "close" }

        assertEquals(listOf("started", "open", "close", "stopped"), ordered)
    }

    @Test
    fun `BGH-01 a tool that never registered is not a transition`() {
        register(OutputTool.DRONE)
        assertFalse(unregister(OutputTool.TEST_TONE))
        assertEquals(listOf("started"), owner.calls)
        assertEquals(listOf("open"), trace)
    }

    @Test
    fun `clear drops every tool with one close and one stopped, and is nothing when empty`() {
        assertEquals(emptySet<OutputTool>(), registry.clear { trace += "close" })
        assertEquals(emptyList<String>(), owner.calls)

        register(OutputTool.DRONE)
        register(OutputTool.METRONOME)
        val dropped = registry.clear { trace += "close" }

        assertEquals(setOf(OutputTool.DRONE, OutputTool.METRONOME), dropped)
        assertEquals(listOf("open", "close"), trace)
        assertEquals(listOf("started", "stopped"), owner.calls)
        assertTrue(registry.isEmpty)
    }
}
