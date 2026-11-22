package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.tui.TerminalKeyEvent
import com.example.ui.theme.JetBrainsMonoFamily
import com.example.ui.theme.TerminalColorScheme

@Composable
fun ExtraKeysBar(
    scheme: TerminalColorScheme,
    ctrlActive: Boolean,
    altActive: Boolean,
    showCodingRow: Boolean,
    vibrateOnKey: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleAlt: () -> Unit,
    onToggleCodingRow: () -> Unit,
    onSpecialKey: (TerminalKeyEvent) -> Unit,
    onChar: (Char) -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current

    fun press(action: () -> Unit) {
        if (vibrateOnKey) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        action()
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(scheme.extraKeysBg)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        AnimatedVisibility(visible = showCodingRow) {
            val symbols = listOf('{', '}', '(', ')', '[', ']', '<', '>', '|', '&', ';', '=', '"', '\'', '\\', '#')
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                symbols.forEach { sym ->
                    ExtraKeyButton(
                        label = sym.toString(),
                        scheme = scheme,
                        active = false,
                        modifier = Modifier.widthIn(min = 38.dp),
                        onClick = { press { onChar(sym) } }
                    )
                }
            }
        }

        // Row 1: ESC / - HOME UP END PGUP
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ExtraKeyButton("ESC", scheme, false, Modifier.weight(1f).testTag("key_esc")) {
                press { onSpecialKey(TerminalKeyEvent.Escape) }
            }
            ExtraKeyButton("/", scheme, false, Modifier.weight(1f).testTag("key_slash")) {
                press { onChar('/') }
            }
            ExtraKeyButton("-", scheme, false, Modifier.weight(1f).testTag("key_dash")) {
                press { onChar('-') }
            }
            ExtraKeyButton("HOME", scheme, false, Modifier.weight(1f).testTag("key_home")) {
                press { onSpecialKey(TerminalKeyEvent.Home) }
            }
            ExtraKeyButton("▲", scheme, false, Modifier.weight(1f).testTag("key_up")) {
                press { onSpecialKey(TerminalKeyEvent.ArrowUp) }
            }
            ExtraKeyButton("END", scheme, false, Modifier.weight(1f).testTag("key_end")) {
                press { onSpecialKey(TerminalKeyEvent.End) }
            }
            ExtraKeyButton("SYM", scheme, showCodingRow, Modifier.weight(1f).testTag("key_sym")) {
                press { onToggleCodingRow() }
            }
        }

        // Row 2: TAB CTRL ALT LEFT DOWN RIGHT PGDN
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ExtraKeyButton("TAB", scheme, false, Modifier.weight(1f).testTag("key_tab")) {
                press { onSpecialKey(TerminalKeyEvent.Tab) }
            }
            ExtraKeyButton("CTRL", scheme, ctrlActive, Modifier.weight(1f).testTag("key_ctrl")) {
                press { onToggleCtrl() }
            }
            ExtraKeyButton("ALT", scheme, altActive, Modifier.weight(1f).testTag("key_alt")) {
                press { onToggleAlt() }
            }
            ExtraKeyButton("◄", scheme, false, Modifier.weight(1f).testTag("key_left")) {
                press { onSpecialKey(TerminalKeyEvent.ArrowLeft) }
            }
            ExtraKeyButton("▼", scheme, false, Modifier.weight(1f).testTag("key_down")) {
                press { onSpecialKey(TerminalKeyEvent.ArrowDown) }
            }
            ExtraKeyButton("►", scheme, false, Modifier.weight(1f).testTag("key_right")) {
                press { onSpecialKey(TerminalKeyEvent.ArrowRight) }
            }
            ExtraKeyButton("PGDN", scheme, false, Modifier.weight(1f).testTag("key_pgdn")) {
                press { onSpecialKey(TerminalKeyEvent.PageDown) }
            }
        }
    }
}

@Composable
private fun ExtraKeyButton(
    label: String,
    scheme: TerminalColorScheme,
    active: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val bg = if (active) scheme.extraKeyActive.copy(alpha = 0.22f) else scheme.extraKeyCap
    val border = if (active) scheme.extraKeyActive else Color.Transparent
    val textColor = if (active) scheme.extraKeyActive else scheme.foreground

    Box(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = JetBrainsMonoFamily,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            fontSize = 11.sp,
            color = textColor,
            maxLines = 1
        )
    }
}
