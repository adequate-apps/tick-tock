// SPDX-License-Identifier: GPL-3.0-or-later
package ticktock.app.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ticktock.app.R
import ticktock.app.output.OutputSession
import ticktock.design.GridlineMetrics
import ticktock.design.GridlineSpacing
import ticktock.design.GridlineType
import ticktock.design.components.*

@Composable
internal fun BluetoothNotice(output: OutputSession) {
    val state by output.state.collectAsStateWithLifecycle()
    if (state.bluetoothNotice) Notice(stringResource(R.string.bluetooth_delay), kind = NoticeKind.Attention,
        icon = GlIcon.BLUETOOTH, iconDescription = stringResource(R.string.bluetooth_name), tag = "bluetooth-notice") {
        Column(verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp2)) {
            Button(stringResource(R.string.bluetooth_measure), output::beginCalibration, Modifier.fillMaxWidth(), tag = "bluetooth-measure")
            Button(stringResource(R.string.bluetooth_dismiss), output::dismissBluetooth, Modifier.fillMaxWidth(), tag = "bluetooth-dismiss")
        }
    }
}

/** Calibration observes its click counter here; the surrounding frame stays out of the beat stream. */
@Composable
internal fun DelayDialog(output: OutputSession) {
    val state by output.calibration.collectAsStateWithLifecycle()
    val measurement = state ?: return
    SquareDialog(stringResource(R.string.bluetooth_measure), output::cancelCalibration, tag = "delay-dialog") {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GridlineSpacing.sp3)) {
            if (!measurement.complete) {
                ProgressSquares(8, measurement.clicks, measurement.clicks - 1, stringResource(R.string.bluetooth_measure), tag = "delay-progress", cellSize = GridlineSpacing.sp6)
                GlText(stringResource(R.string.delay_progress, measurement.taps), style = GridlineType.value)
                Button(stringResource(R.string.delay_tap), output::calibrationTap, Modifier.fillMaxWidth().heightIn(min = GridlineMetrics.touchTarget * 2), ButtonTone.Primary, tag = "delay-tap")
                GlText(stringResource(R.string.delay_instruction))
            } else {
                val offset = measurement.offsetMs
                if (offset != null) {
                    Field(Modifier.fillMaxWidth()) {
                        Caption(stringResource(R.string.delay_visual))
                        GlText(stringResource(R.string.delay_offset, offset), style = GridlineType.note)
                    }
                    Button(stringResource(R.string.delay_use, offset), output::useCalibration, Modifier.fillMaxWidth(), ButtonTone.Primary, tag = "delay-use")
                } else GlText(stringResource(R.string.delay_missed))
                Button(stringResource(R.string.delay_again), output::retryCalibration, Modifier.fillMaxWidth(), tag = "delay-again")
            }
            Button(stringResource(R.string.action_cancel), output::cancelCalibration, Modifier.fillMaxWidth(), tag = "delay-cancel")
        }
    }
}
