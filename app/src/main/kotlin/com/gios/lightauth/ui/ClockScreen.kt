package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gios.lightauth.hw.WheelNudge
import com.gios.lightauth.time.TimeMath
import com.gios.lightauth.time.TimeSource
import com.gios.lightauth.ui.theme.Dim
import com.gios.lightauth.ui.theme.Faint
import kotlinx.coroutines.delay

/**
 * The clock screen: what time this app thinks it is, and a correction for when that is
 * wrong.
 *
 * This exists because a drifted phone clock is indistinguishable, from the code screen,
 * from a wrong secret — both look like six plausible digits that the site refuses. Showing
 * the UTC clock the codes are actually derived from turns a mystery into a comparison you
 * can make against time.is in about five seconds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClockScreen(onBack: () -> Unit) {
    var offset by remember { mutableIntStateOf(TimeSource.offsetSeconds()) }
    var deviceNow by remember { mutableLongStateOf(TimeSource.deviceSeconds()) }

    LaunchedEffect(Unit) {
        while (true) {
            deviceNow = TimeSource.deviceSeconds()
            delay(250)
        }
    }

    fun apply(seconds: Int) {
        val next = TimeMath.clampOffset(seconds)
        offset = next
        TimeSource.setOffsetSeconds(next)
    }

    // A notch is a second. The wheel is the only way to nudge the correction without a
    // fingertip covering the digits you are trying to line up.
    WheelNudge { notches -> apply(offset + notches) }

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                colors = barColors(),
                title = { Text("Clock") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            ActionBar(
                listOf(
                    BarAction("−10s") { apply(offset - 10) },
                    BarAction("RESET") { apply(0) },
                    BarAction("+10s") { apply(offset + 10) },
                ),
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
                .background(Color.Black)
                .padding(horizontal = 16.dp),
        ) {
            Spacer(Modifier.height(20.dp))

            Text(
                TimeMath.formatUtcClock(TimeMath.corrected(deviceNow, offset)),
                style = MaterialTheme.typography.displaySmall,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                "UTC — compare with time.is",
                style = MaterialTheme.typography.bodyMedium,
                color = Dim,
            )

            Spacer(Modifier.height(24.dp))
            Rule()
            Spacer(Modifier.height(16.dp))

            Field("Phone clock", TimeMath.formatUtcClock(deviceNow))
            Spacer(Modifier.height(10.dp))
            Field("Correction", TimeMath.formatOffset(offset))

            Spacer(Modifier.height(20.dp))

            Text(
                if (offset == 0) {
                    "Codes use the phone's clock as-is. If every site rejects every " +
                        "code, the clock above is wrong — turn the wheel to line it up " +
                        "with time.is, one second per notch."
                } else {
                    "Every code is computed ${TimeMath.formatOffset(offset)} from the " +
                        "phone's clock. Turn the wheel to fine-tune, one second per notch."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = Faint,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Dim, maxLines = 1)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = Color.White, maxLines = 1)
    }
}
