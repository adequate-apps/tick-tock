// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.ForegroundOwner

/** A [ForegroundOwner] that records each hook as "started" or "stopped", in order. */
class FakeForegroundOwner : ForegroundOwner {
    val calls = mutableListOf<String>()

    override fun onToolsStarted() {
        calls += "started"
    }

    override fun onToolsStopped() {
        calls += "stopped"
    }
}
