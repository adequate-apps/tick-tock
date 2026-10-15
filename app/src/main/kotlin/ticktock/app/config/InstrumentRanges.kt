// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.config

import ticktock.core.catalogue.Instrument
import ticktock.core.prefs.Prefs
import ticktock.core.ranges.BaseRanges
import ticktock.core.ranges.EffectiveRange
import ticktock.core.ranges.OverlayRanges
import ticktock.core.ranges.RangeResolver

/** Imported ranges use the same three-layer resolver as the catalogue. */
internal fun effectiveRange(instrument: Instrument, prefs: Prefs): EffectiveRange = RangeResolver.resolve(
    instrument, BaseRanges.bundled, OverlayRanges.bundled, prefs.personalConfig.rangeOverrides[instrument.key])
