// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.prefs

import ticktock.core.config.ParseResult
import ticktock.core.config.PersonalConfig
import ticktock.core.config.PersonalConfigCodec
import ticktock.core.json.Json
import ticktock.core.json.JsonValue

/** Ranges, profiles and unread portable fields share the atomic preferences file. */
class PersonalConfigField : PrefField<PersonalConfig>("personalConfig", PersonalConfig(),
    { it.personalConfig }, { prefs, config -> prefs.copy(personalConfig = config) }, null) {
    override val allowed = "a valid personal configuration"
    override fun encode(value: PersonalConfig): JsonValue = PersonalConfigCodec.toJson(value)
    override fun decode(raw: JsonValue): Decoded<PersonalConfig> = when (val parsed = PersonalConfigCodec.parse(Json.write(raw))) {
        is ParseResult.Ok -> Decoded.Valid(parsed.config)
        is ParseResult.Failed -> Decoded.Invalid
    }
}
