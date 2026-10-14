// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app

/**
 * The working name, for places that run before resources are available (the
 * crash log header, log tags). Screens use the app_name string resource. A
 * test checks the two agree and that the literal appears nowhere else.
 */
object AppIdentity {
    const val NAME = "TickTock"
}
