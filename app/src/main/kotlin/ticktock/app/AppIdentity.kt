// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

/**
 * The product name, for places that run before resources are available (the
 * crash log header, log tags). The code keeps its codename, TickTock. Screens use the app_name string resource. A
 * test checks the two agree and that the literal appears nowhere else.
 */
object AppIdentity {
    /** Launcher label and in-app title. */
    const val NAME = "Metronome"

    /** The brand: splash, About and the store listing. */
    const val BRAND = "Adequate Metronome"
}
