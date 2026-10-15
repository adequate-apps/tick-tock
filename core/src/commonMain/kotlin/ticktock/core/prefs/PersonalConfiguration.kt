// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigMerge
import ticktock.core.config.PortableSettings

/** A configuration import changes its settings and payload in one preferences update. */
object PersonalConfiguration {
    fun snapshot(prefs: Prefs): PersonalConfig = prefs.personalConfig.copy(
        settings = PrefsPortable.export(prefs).copy(unknownFields = prefs.personalConfig.settings.unknownFields))

    fun apply(prefs: Prefs, incoming: PersonalConfig): Prefs {
        val merged = PersonalConfigMerge.merge(snapshot(prefs), incoming)
        val nickname = if (merged.settings.instrumentKey != prefs.instrumentKey) "" else prefs.hornNickname
        return PrefsPortable.apply(prefs, merged.settings).copy(hornNickname = nickname, personalConfig = merged.copy(
            settings = PortableSettings(unknownFields = merged.settings.unknownFields)))
    }
}
