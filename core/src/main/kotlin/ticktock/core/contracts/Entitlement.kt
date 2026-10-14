// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.contracts

/**
 * The three features a reserve pricing plan would sell as one unlock. Decision
 * 15 sells the whole app once, so every feature is granted; the enumeration
 * exists so the gate sits in exactly three places and nowhere else (ENT-01).
 */
enum class GatedFeature { ALL_HISTORY, NAMED_SESSIONS, EXPORT }

/**
 * Whether the player may use a [GatedFeature]. One production implementation,
 * [AlwaysEntitled], and no licence check anywhere: the code is GPL, so a check
 * would buy nothing (decision 15). The seam is kept so that the reserve plan,
 * a free listing with a single non-consumable unlock, stays a one-module
 * change that never touches the gated screens.
 */
interface Entitlement {
    fun has(feature: GatedFeature): Boolean
}

/** 2.0: everything is included in the one price. */
object AlwaysEntitled : Entitlement {
    override fun has(feature: GatedFeature): Boolean = true
}
