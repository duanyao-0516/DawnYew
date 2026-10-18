package com.dawnyew.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.dawnyew.presentation.Hand
import com.dawnyew.presentation.RpsOutcome
import com.dawnyew.presentation.judgeRps
import com.dawnyew.presentation.randomHand

@Composable internal fun RockPaperScissorsPage() {
    var upperName by rememberSaveable { mutableStateOf<String?>(null) }
    var lowerName by rememberSaveable { mutableStateOf<String?>(null) }
    val upper = upperName?.let(Hand::valueOf)
    val lower = lowerName?.let(Hand::valueOf)
    val preferredButtonSize = (96f * LocalDensity.current.fontScale.coerceIn(1f, 1.875f)).dp
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val buttonSize = minOf(preferredButtonSize, maxWidth, maxHeight / 3).coerceAtLeast(48.dp)
        val compactButton = buttonSize < preferredButtonSize
        Column(Modifier.fillMaxSize()) {
            RpsPlayerArea(
                hand = upper, opponent = lower, player = "玩家二", centreInset = buttonSize / 2 + 8.dp,
                modifier = Modifier.weight(1f).fillMaxWidth().rotate(180f)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
            )
            RpsPlayerArea(
                hand = lower, opponent = upper, player = "玩家一", centreInset = buttonSize / 2 + 8.dp,
                modifier = Modifier.weight(1f).fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer),
            )
        }
        Button(
            onClick = {
                val nextUpper = randomHand()
                val nextLower = randomHand()
                upperName = nextUpper.name
                lowerName = nextLower.name
            },
            shape = CircleShape, contentPadding = PaddingValues(12.dp),
            modifier = Modifier.align(Alignment.Center).size(buttonSize).semantics {
                contentDescription = if (upper == null) "双方同时随机出拳" else "再来一局，双方同时随机出拳"
            },
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 6.dp),
        ) {
            if (compactButton) AppGlyph("做个决定")
            else Text(if (upper == null) "出拳" else "再来", textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable private fun RpsPlayerArea(hand: Hand?, opponent: Hand?, player: String, centreInset: Dp, modifier: Modifier) {
    val outcome = if (hand != null && opponent != null) judgeRps(hand, opponent) else null
    // Insets belong to the scroll content, so short halves always retain a viewport.
    Box(
        modifier,
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = centreInset, end = 16.dp, bottom = 12.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text(player, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            Text(
                when (hand) { Hand.ROCK -> "✊"; Hand.SCISSORS -> "✌"; Hand.PAPER -> "✋"; null -> "?" },
                fontSize = 56.sp,
            )
            Text(
                when (hand) { Hand.ROCK -> "石头"; Hand.SCISSORS -> "剪刀"; Hand.PAPER -> "布"; null -> "准备好了吗" },
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when (outcome) { RpsOutcome.WIN -> "你赢了"; RpsOutcome.LOSE -> "对方获胜"; RpsOutcome.DRAW -> "平局"; null -> "轻点中间按钮，同时随机出拳" },
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
        }
    }
}
