// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.core.ranges

import ticktock.core.catalogue.Instrument
import ticktock.core.music.Pitch
import ticktock.core.music.Transposition

/**
 * The user's own keyed range for one instrument (owner decision 3, layer 3).
 * It replaces the keyed tier and can only widen the extended tier (RNG-15).
 */
data class UserRangeOverride(val keyed: WrittenRange)

/**
 * Resolves base, then app overlay, then user into one [EffectiveRange] and
 * says which layer set each end. Pure: the same inputs always give the same
 * answer, and nothing here reads a file.
 *
 * Rules, in order:
 *  1. Base: MuseScore's sounding spans become written pitch through the
 *     catalogue's transposition. A catch-all instrument has no base entry and
 *     starts with the whole detector window on both tiers (RNG-10).
 *  2. App overlay: a keyed entry replaces the keyed tier; an extended entry
 *     replaces the extended tier.
 *  3. User: a keyed override replaces the keyed tier.
 *  4. Extended is widened to cover keyed, so keyed is always inside extended
 *     (RNG-01, RNG-15). An end moved by this step belongs to the layer that
 *     set the keyed end it was widened to.
 */
object RangeResolver {
    fun resolve(
        instrument: Instrument,
        base: BaseRanges,
        overlay: OverlayRanges,
        user: UserRangeOverride? = null,
    ): EffectiveRange {
        val t = instrument.transposition
        var keyedLow: RangeEnd
        var keyedHigh: RangeEnd
        var extendedLow: RangeEnd
        var extendedHigh: RangeEnd

        val baseEntry = instrument.musescoreId?.let { base.entries[instrument.key] }
        if (baseEntry != null) {
            keyedLow = RangeEnd(Transposition.writtenFromSounding(baseEntry.soundingKeyedLow, t), RangeLayer.BASE)
            keyedHigh = RangeEnd(Transposition.writtenFromSounding(baseEntry.soundingKeyedHigh, t), RangeLayer.BASE)
            extendedLow = RangeEnd(Transposition.writtenFromSounding(baseEntry.soundingExtendedLow, t), RangeLayer.BASE)
            extendedHigh = RangeEnd(Transposition.writtenFromSounding(baseEntry.soundingExtendedHigh, t), RangeLayer.BASE)
        } else {
            if (!instrument.catchAll) throw RangeDataException("${instrument.key}: no base range for ${instrument.musescoreId}")
            val window = Pitch.DETECTOR_WINDOW
            keyedLow = RangeEnd(Transposition.writtenFromSounding(window.first, t), RangeLayer.BASE)
            keyedHigh = RangeEnd(Transposition.writtenFromSounding(window.last, t), RangeLayer.BASE)
            extendedLow = keyedLow
            extendedHigh = keyedHigh
        }

        overlay.keyed[instrument.key]?.let { entry ->
            keyedLow = RangeEnd(entry.range.low, RangeLayer.APP_OVERLAY)
            keyedHigh = RangeEnd(entry.range.high, RangeLayer.APP_OVERLAY)
        }
        overlay.extended[instrument.key]?.let { entry ->
            extendedLow = RangeEnd(entry.range.low, RangeLayer.APP_OVERLAY)
            extendedHigh = RangeEnd(entry.range.high, RangeLayer.APP_OVERLAY)
        }

        user?.let { override ->
            keyedLow = RangeEnd(override.keyed.low, RangeLayer.USER)
            keyedHigh = RangeEnd(override.keyed.high, RangeLayer.USER)
        }

        if (keyedLow.midi < extendedLow.midi) extendedLow = keyedLow
        if (keyedHigh.midi > extendedHigh.midi) extendedHigh = keyedHigh

        return EffectiveRange(keyedLow, keyedHigh, extendedLow, extendedHigh)
    }

    /** True when a sounding note counts as in range for judgement purposes (RNG-02: the extended tier decides). */
    fun inExtendedRange(instrument: Instrument, range: EffectiveRange, soundingMidi: Int): Boolean =
        Transposition.writtenFromSounding(soundingMidi, instrument.transposition) in range.extended
}
