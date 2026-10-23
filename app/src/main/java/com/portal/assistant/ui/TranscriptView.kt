package com.portal.assistant.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.portal.assistant.conversation.ConversationHub
import com.portal.assistant.conversation.RevealProgress
import com.portal.assistant.conversation.Role
import com.portal.assistant.conversation.Turn
import com.portal.assistant.ui.theme.Accent
import com.portal.assistant.ui.theme.Measure
import com.portal.assistant.ui.theme.SECONDARY_ALPHA
import com.portal.assistant.ui.theme.TextSize
import com.portal.assistant.ui.theme.TurnSpacing
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

// Older turns fade so the latest answer stands out — but stay readable at distance (raised from 0.45).
private const val OLDER_TURN_ALPHA = 0.62f

// How far from the newest content the user can be scrolled and still count as "following".
private const val BOTTOM_SLOP_PX = 160

// Paced reveal: the model's transcription text arrives faster than its 24 kHz speech plays. The Transcript
// stamps how many words have been "spoken" on the latest model turn (Turn.revealedWords, computed by
// RevealProgress from audio played ÷ received); this screen just shows that many words while SPEAKING and
// snaps to the turn's text when it ends (cut to the words heard if it ended early — see Transcript). Each
// freshly revealed word fades in over WORD_FADE_MS (device-tunable).
private const val WORD_FADE_MS = 140

// Below this playback level the model is "thinking" (no audible output yet); at/above it, audio is flowing
// so the thinking dots give way even before the first transcript word arrives.
private const val THINKING_AUDIO_EPS = 0.05f

private const val THINKING_DOTS = 3

@Composable
internal fun Transcript(turns: List<Turn>, phase: ConversationHub.UiPhase, audioLevel: StateFlow<Float>) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // reverseLayout anchors the conversation to the BOTTOM (newest just above the mic, history above it);
    // index 0 is the newest, so we feed the list reversed.
    val items = turns.asReversed()
    val latest = turns.lastOrNull()
    val latestId = latest?.id

    // The model has taken the turn (SPEAKING) but hasn't produced words OR audio yet — the Search/tool
    // "thinking" gap. Show animated dots where the answer will land so the latency reads as working, not stuck.
    // The audio gate matters: the server can stream audio before any transcript (latest still the USER turn),
    // and we must drop the dots the moment sound starts, not wait for the first transcribed word.
    val level by audioLevel.collectAsStateWithLifecycle()
    val thinking = phase == ConversationHub.UiPhase.SPEAKING &&
        (latest?.role != Role.MODEL || latest.text.isBlank()) &&
        level < THINKING_AUDIO_EPS

    // "At bottom" = pinned to the newest (index 0). Flips false when the user scrolls up into history,
    // pausing auto-follow so we never fight them reading back.
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= BOTTOM_SLOP_PX
        }
    }

    // A new turn re-engages follow even if the user had scrolled up.
    LaunchedEffect(turns.size) { listState.animateScrollToItem(0) }
    // The newest turn grows as text streams (user chips) or words reveal (model, via revealedWords) — follow
    // while pinned.
    LaunchedEffect(latest?.text, latest?.revealedWords) { if (atBottom) listState.scrollToItem(0) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Cap the line measure for readability and center the column — queried against the real width at
        // runtime so it adapts to any Portal model / orientation (no hardcoded resolution). Both user
        // questions and model answers share this one left edge (single-column reading, no left/right split).
        val gutter = ((maxWidth - Measure.Reading) / 2).coerceAtLeast(Measure.MinGutter)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = gutter, vertical = 16.dp),
        ) {
            // reverseLayout → the first-declared item sits at the bottom (newest), just after the question.
            if (thinking) {
                item(key = "thinking") { ThinkingDots() }
            }
            items(items, key = { it.id }) { turn ->
                // The Transcript stamps revealedWords on Jarvis's active turn while it speaks; reveal it word-
                // by-word only then. Every finished/older turn (and any turn once speaking ends) shows its text —
                // the full answer, or the words heard if it was cut short (the Transcript trims a cut turn's text
                // and clears its count, so an interrupted answer still latest while SPEAKING shows what was heard).
                val revealing = turn.id == latestId && phase == ConversationHub.UiPhase.SPEAKING
                TurnItem(
                    turn = turn,
                    dimmed = turn.id != latestId,
                    revealedWords = if (revealing) turn.revealedWords else null,
                )
            }
        }
        AnimatedVisibility(
            visible = !atBottom,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        ) {
            ScrollToBottomButton { scope.launch { listState.animateScrollToItem(0) } }
        }
    }
}

@Composable
private fun TurnItem(turn: Turn, dimmed: Boolean, revealedWords: Int?) {
    val alpha = if (dimmed) OLDER_TURN_ALPHA else 1f
    when (turn.role) {
        // The user's question reads as a quiet, left-aligned inset ABOVE its answer — same reading edge as
        // the model text (one column, no right-side bubble), smaller and dimmer so the answer stays the star.
        // A larger gap above groups each question with the answer that follows (a Q&A pair). An accent ›
        // marker distinguishes a question from an answer without a chip.
        Role.USER -> Text(
            text = buildAnnotatedString {
                withStyle(SpanStyle(color = Accent.copy(alpha = if (dimmed) OLDER_TURN_ALPHA else 1f))) {
                    append("›  ")
                }
                append(turn.text)
            },
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = if (dimmed) OLDER_TURN_ALPHA else SECONDARY_ALPHA),
            fontSize = TextSize.UserEcho,
            modifier = Modifier.fillMaxWidth().padding(top = TurnSpacing.PairGap, bottom = TurnSpacing.Couple),
        )

        Role.MODEL -> {
            val modifier = Modifier.fillMaxWidth().padding(top = TurnSpacing.Couple, bottom = TurnSpacing.Trailing)
            if (revealedWords != null) {
                RevealingModelText(turn.text, revealedWords, modifier)
            } else {
                Text(
                    text = turn.text,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
                    fontSize = TextSize.ModelText,
                    lineHeight = TextSize.ModelLine,
                    modifier = modifier,
                )
            }
        }
    }
}

/**
 * The active model answer, revealed word-by-word in step with its speech: the first [revealedWords] words
 * of the *original* [text] are shown (so whitespace/newlines are preserved verbatim — no reflow when the
 * turn later snaps to the full plain text) and the most-recently revealed word fades in (alpha 0→1 over
 * [WORD_FADE_MS]).
 */
@Composable
private fun RevealingModelText(text: String, revealedWords: Int, modifier: Modifier = Modifier) {
    val ranges = remember(text) { RevealProgress.wordRanges(text) }
    val shown = revealedWords.coerceIn(0, ranges.size)
    val color = MaterialTheme.colorScheme.onBackground

    // A FRESH fade per newly revealed word: keyed on [shown], so each trailing word starts at 0 on its very
    // first composition (no full-alpha frame → no blink) and fades in exactly once. Already-revealed words
    // render at full alpha and are never re-animated ("once rendered, don't touch the word").
    val fade = remember(shown) { Animatable(0f) }
    LaunchedEffect(shown) { if (shown > 0) fade.animateTo(1f, tween(WORD_FADE_MS)) }

    val annotated = buildAnnotatedString {
        if (shown > 0) {
            val last = ranges[shown - 1]
            // Everything up to the newest word (prior words + their original whitespace) at full alpha…
            withStyle(SpanStyle(color = color)) { append(text.substring(0, last.first)) }
            // …then the newest word fades in. At full reveal, include the original trailing text too so this
            // matches the plain-Text snap exactly.
            val end = if (shown >= ranges.size) text.length else last.last + 1
            withStyle(SpanStyle(color = color.copy(alpha = fade.value))) { append(text.substring(last.first, end)) }
        }
    }
    Text(text = annotated, fontSize = TextSize.ModelText, lineHeight = TextSize.ModelLine, modifier = modifier)
}

/** Three staggered, breathing dots — the model's "thinking" placeholder during the pre-output gap. */
@Composable
private fun ThinkingDots(modifier: Modifier = Modifier) {
    val reduceMotion = LocalReduceMotion.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 6.dp)
            .semantics { contentDescription = "Jarvis is thinking" },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (reduceMotion) {
            // Three steady dots still read as "working", without the ripple.
            repeat(THINKING_DOTS) { Box(Modifier.size(14.dp).clip(CircleShape).background(Accent.copy(alpha = 0.7f))) }
        } else {
            val transition = rememberInfiniteTransition(label = "thinking")
            repeat(THINKING_DOTS) { i ->
                val alpha by transition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(600),
                        repeatMode = RepeatMode.Reverse,
                        initialStartOffset = StartOffset(i * 200), // stagger so the dots ripple
                    ),
                    label = "dot$i",
                )
                Box(Modifier.size(14.dp).clip(CircleShape).background(Accent.copy(alpha = alpha)))
            }
        }
    }
}
