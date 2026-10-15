// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import ticktock.core.contracts.AlwaysEntitled
import ticktock.core.contracts.Entitlement
import ticktock.core.contracts.GatedFeature

class NamedSessionsAccess(private val access: Entitlement = AlwaysEntitled) {
    val allowed: Boolean get() = access.has(GatedFeature.NAMED_SESSIONS)
}
