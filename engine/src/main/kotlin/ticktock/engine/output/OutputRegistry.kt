// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.engine.output

import ticktock.core.contracts.ForegroundOwner
import ticktock.core.contracts.OutputTool

/**
 * The set of tools that want output (OUT-01). The stream exists while the set
 * is non-empty, and the two transitions that bracket it are the only places
 * the [ForegroundOwner] hears anything (BGH-01): started before the stream
 * opens, stopped after it closes. The ordering lives here rather than in the
 * control thread so that a future media-playback service can only ever be
 * started by a tool starting, which is the one moment Android guarantees the
 * app is in the foreground.
 *
 * Only the control thread touches an instance.
 */
class OutputRegistry(private val owner: ForegroundOwner) {
    private val tools = linkedSetOf<OutputTool>()

    /** A snapshot, in registration order. */
    val registered: Set<OutputTool> get() = tools.toSet()

    val isEmpty: Boolean get() = tools.isEmpty()

    operator fun contains(tool: OutputTool): Boolean = tool in tools

    /**
     * Adds [tool]. On the empty-to-one transition the owner hears started and
     * then [openStream] runs; a second registration of the same tool is not a
     * transition. Returns whether this call was the transition.
     */
    fun register(tool: OutputTool, openStream: () -> Unit): Boolean {
        val first = tools.isEmpty()
        tools += tool
        if (first) {
            owner.onToolsStarted()
            openStream()
        }
        return first
    }

    /**
     * Removes [tool]. On the one-to-empty transition [closeStream] runs and
     * then the owner hears stopped. A tool that was never registered is not a
     * transition. Returns whether this call was the transition.
     */
    fun unregister(tool: OutputTool, closeStream: () -> Unit): Boolean {
        if (!tools.remove(tool) || tools.isNotEmpty()) return false
        closeStream()
        owner.onToolsStopped()
        return true
    }

    /**
     * Drops every tool at once, the shape of a park nothing restarts from (an
     * answered call, the background, an open failure). One [closeStream], one
     * stopped. Returns the tools dropped, empty when there were none.
     */
    fun clear(closeStream: () -> Unit): Set<OutputTool> {
        if (tools.isEmpty()) return emptySet()
        val dropped = tools.toSet()
        tools.clear()
        closeStream()
        owner.onToolsStopped()
        return dropped
    }
}
