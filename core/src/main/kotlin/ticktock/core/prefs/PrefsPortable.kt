// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import ticktock.core.config.PortableSettings

/**
 * The bridge between the prefs file and the personal configuration (owner
 * decision 3). Both directions walk the field table and touch only the rows
 * with a [PortableLink], so the set of settings a player carries between
 * phones is stated once, on the row.
 */
object PrefsPortable {
    /** The portable fields of [prefs], ready to write into a configuration file. */
    fun export(prefs: Prefs): PortableSettings =
        PrefsSchema.fields.fold(PortableSettings()) { settings, field -> field.exportTo(settings, prefs) }

    /**
     * [prefs] with every setting the file states replaced; what the file leaves
     * out stays as it is. Applying the same settings twice changes nothing.
     */
    fun apply(prefs: Prefs, settings: PortableSettings): Prefs =
        PrefsSchema.fields.fold(prefs) { current, field -> field.importFrom(current, settings) }
}
