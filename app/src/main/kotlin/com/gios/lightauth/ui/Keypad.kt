package com.gios.lightauth.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.lightauth.ui.theme.Dim
import com.gios.lightauth.ui.theme.RuleGrey

/**
 * The PIN keypad, shared by the lock screen and by setting a new PIN.
 *
 * Word buttons and hairline rules, like the rest of the app — no filled keys, because a grid
 * of light rectangles on a matte greyscale panel is the one thing that would glare. `DEL` and
 * `ENTER` flank the zero rather than sitting in a bottom bar, so the whole interaction stays
 * under one thumb.
 */
@Composable
fun Keypad(
    entered: String,
    maxLength: Int,
    enabled: Boolean,
    onDigit: (Char) -> Unit,
    onDelete: () -> Unit,
    onSubmit: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().alpha(if (enabled) 1f else 0.35f)) {
        for (row in listOf("123", "456", "789")) {
            KeyRow {
                row.forEach { digit ->
                    Key(digit.toString(), enabled) { onDigit(digit) }
                }
            }
        }
        KeyRow {
            Key("DEL", enabled && entered.isNotEmpty(), onClick = onDelete)
            Key("0", enabled) { onDigit('0') }
            Key("ENTER", enabled && entered.length >= 4, onClick = onSubmit)
        }
    }
}

/** Filled for each digit entered, hollow for the rest. Never the digits themselves. */
@Composable
fun PinDots(entered: Int, total: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(total.coerceAtLeast(1)) { index ->
            Box(
                Modifier
                    .padding(horizontal = 7.dp)
                    .size(13.dp)
                    .then(
                        if (index < entered) {
                            Modifier.background(Color.White, CircleShape)
                        } else {
                            Modifier.border(1.dp, Dim, CircleShape)
                        },
                    ),
            )
        }
    }
}

@Composable
private fun KeyRow(content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
    Rule()
}

/**
 * One key, exactly a third of the row.
 *
 * `weight(1f)`, emphatically not `fillMaxWidth(1f / 3f)`. Inside a Row, a fractional fill
 * resolves against the width *remaining* after the previously measured children, not against
 * the row — so three keys asking for a third each came out as ⅓, 2⁄9 and 4⁄27 of the width.
 * The keypad shrank left to right and sat off-centre with the leftover space padded around it.
 * Weights are the only thing that divides a Row evenly.
 */
@Composable
private fun RowScope.Key(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .weight(1f)
            .aspectRatio(1.6f)
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = if (label.length == 1) {
                MaterialTheme.typography.titleLarge
            } else {
                MaterialTheme.typography.labelLarge
            },
            color = if (enabled) Color.White else RuleGrey,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

@Composable
fun KeypadHeading(title: String, subtitle: String?, entered: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        PinDots(entered, total)
        Spacer(Modifier.height(14.dp))
        Text(
            subtitle.orEmpty(),
            style = MaterialTheme.typography.bodyMedium,
            color = Dim,
            textAlign = TextAlign.Center,
            // Reserved whether or not there is a message, so the keypad never jumps when
            // one appears.
            modifier = Modifier.fillMaxWidth().height(40.dp),
        )
    }
}
