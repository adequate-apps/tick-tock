// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.history

import ticktock.core.contracts.AlwaysEntitled
import ticktock.core.contracts.Entitlement
import ticktock.core.contracts.GatedFeature

class AllHistoryAccess(private val access: Entitlement = AlwaysEntitled) {
    val allowed: Boolean get() = access.has(GatedFeature.ALL_HISTORY)
}
