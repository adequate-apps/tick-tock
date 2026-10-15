// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts.fakes

import ticktock.core.contracts.Entitlement
import ticktock.core.contracts.GatedFeature

/** An [Entitlement] that grants exactly the features a test puts in [granted]. */
class FakeEntitlement(vararg initial: GatedFeature) : Entitlement {
    val granted: MutableSet<GatedFeature> = initial.toMutableSet()

    override fun has(feature: GatedFeature): Boolean = feature in granted
}
