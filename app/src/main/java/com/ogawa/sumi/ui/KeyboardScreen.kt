package com.ogawa.sumi.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.ogawa.sumi.ai.AISuggestion
import com.ogawa.sumi.ime.FlickDirection
import com.ogawa.sumi.ime.InputMode
import com.ogawa.sumi.ime.KanaComposer
import com.ogawa.sumi.ime.KeyInput
import com.ogawa.sumi.ime.KeyboardStateHolder
import com.ogawa.sumi.ime.ShiftState
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.sqrt

// ============================================================================
// テーマ（最小限）
// ============================================================================

private val BgKeyboard = Color(0xFFEBEAE5)
private val BgKey = Color(0xFFFFFFFF)
private val BgKeyCtrl = Color(0xFFD6D4CE)
private val TextPrimary = Color(0xFF1F1F1D)
private val TextSecondary = Color(0xFF9A9A96)
private val Accent = Color(0xFF7F77DD)
private val AccentDark = Color(0xFF534AB7)
private val AccentSoft = Color(0x1F7F77DD)
private val BorderSubtle = Color(0x14000000)

@Composable
fun MinimalKeyboardTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            background = BgKeyboard,
            surface = BgKey,
            onSurface = TextPrimary,
            primary = Accent
        ),
        content = content
    )
}

// ============================================================================
// キーボード全体
// ============================================================================

@Composable
fun KeyboardScreen(
    state: KeyboardStateHolder,
    onKeyInput: (KeyInput) -> Unit,
    onCandidateSelect: (String) -> Unit,
    onAISuggestionSelect: (AISuggestion) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgKeyboard)
    ) {
        AISuggestionBar(
            suggestions = state.aiSuggestions,
            loading = state.aiLoading,
            onSelect = onAISuggestionSelect
        )
        CandidateBar(
            candidates = state.candidates,
            onSelect = onCandidateSelect
        )
        // フリック ⇄ QWERTY をクロスフェードで切替（150ms ease）
        Crossfade(
            targetState = state.inputMode,
            animationSpec = tween(durationMillis = 150),
            label = "keyboard-layout"
        ) { mode ->
            when (mode) {
                InputMode.QWERTY_ROMAJI -> QwertyGrid(
                    shiftState = state.shiftState,
                    onKeyInput = onKeyInput
                )
                else -> KeyGrid(onKeyInput = onKeyInput)
            }
        }
    }
}

// ============================================================================
// AI候補バー
// ============================================================================

@Composable
private fun AISuggestionBar(
    suggestions: List<AISuggestion>,
    loading: Boolean,
    onSelect: (AISuggestion) -> Unit
) {
    if (suggestions.isEmpty() && !loading) return

    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgKeyboard)
            .horizontalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AILabel()
        if (loading) {
            Text(
                text = "候補を作成中...",
                color = TextSecondary,
                fontSize = 12.sp
            )
        } else {
            suggestions.forEach { suggestion ->
                AIPill(suggestion = suggestion, onClick = { onSelect(suggestion) })
            }
        }
    }
}

@Composable
private fun AILabel() {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(AccentSoft)
            .padding(horizontal = 9.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "✦",
            color = AccentDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "AI",
            color = AccentDark,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun AIPill(suggestion: AISuggestion, onClick: () -> Unit) {
    val displayText = if (suggestion.text.length > 14) {
        suggestion.text.take(13) + "…"
    } else suggestion.text

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(BgKey)
            .border(0.5.dp, Color(0x4D7F77DD), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text = displayText,
            color = TextPrimary,
            fontSize = 12.sp
        )
    }
}

// ============================================================================
// 変換候補バー
// ============================================================================

@Composable
private fun CandidateBar(
    candidates: List<String>,
    onSelect: (String) -> Unit
) {
    if (candidates.isEmpty()) {
        // 空のときも高さを保持して、キーボードが上下に動かないようにする
        Spacer(modifier = Modifier.height(36.dp).fillMaxWidth().background(BgKeyboard))
        return
    }

    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgKeyboard)
            .horizontalScroll(scroll)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        candidates.forEachIndexed { index, cand ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(cand) }
                    .padding(horizontal = 11.dp, vertical = 6.dp)
            ) {
                Text(
                    text = cand,
                    color = if (index == 0) AccentDark else TextPrimary,
                    fontSize = 13.sp,
                    fontWeight = if (index == 0) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}

// ============================================================================
// 12キーグリッド
// ============================================================================

private data class KeyDef(
    val label: String,
    val base: String? = null,           // フリック対応キーは base に文字を入れる
    val isControl: Boolean = false,
    val controlAction: KeyInput? = null
)

private val KEY_LAYOUT: List<List<KeyDef>> = listOf(
    listOf(KeyDef("あ", base = "あ"), KeyDef("か", base = "か"), KeyDef("さ", base = "さ"),
        KeyDef("⌫", isControl = true, controlAction = KeyInput.Backspace)),
    listOf(KeyDef("た", base = "た"), KeyDef("な", base = "な"), KeyDef("は", base = "は"),
        KeyDef("空白", isControl = true, controlAction = KeyInput.Space)),
    listOf(KeyDef("ま", base = "ま"), KeyDef("や", base = "や"), KeyDef("ら", base = "ら"),
        KeyDef("改行", isControl = true, controlAction = KeyInput.Enter)),
    listOf(KeyDef("あA1", isControl = true, controlAction = KeyInput.SwitchMode),
        KeyDef("わ", base = "わ"), KeyDef("、。?!", base = "、"),
        KeyDef("⌨", isControl = true, controlAction = KeyInput.SwitchKeyboard))
)

@Composable
private fun KeyGrid(onKeyInput: (KeyInput) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgKeyboard)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        KEY_LAYOUT.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                row.forEach { def ->
                    Box(modifier = Modifier.weight(1f)) {
                        Key(def = def, onKeyInput = onKeyInput)
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(def: KeyDef, onKeyInput: (KeyInput) -> Unit) {
    val bg = if (def.isControl) BgKeyCtrl else BgKey
    val fontSize = if (def.isControl) 12.sp else 18.sp

    val keyModifier = Modifier
        .fillMaxWidth()
        .height(46.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(bg)
        .border(0.5.dp, BorderSubtle, RoundedCornerShape(8.dp))

    if (def.isControl) {
        Box(
            modifier = keyModifier.clickable {
                def.controlAction?.let(onKeyInput)
            },
            contentAlignment = Alignment.Center
        ) {
            Text(text = def.label, color = TextPrimary, fontSize = fontSize)
        }
    } else {
        // フリック対応キー: タップ＝中央、ドラッグ方向で別の母音
        FlickKey(
            label = def.label,
            base = def.base!!,
            modifier = keyModifier,
            onKeyInput = onKeyInput
        )
    }
}

@Composable
private fun FlickKey(
    label: String,
    base: String,
    modifier: Modifier,
    onKeyInput: (KeyInput) -> Unit
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val thresholdPx = with(density) { 12.dp.toPx() }
    val longPressMillis = 250L

    var showPopup by remember { mutableStateOf(false) }
    var currentDirection by remember { mutableStateOf(FlickDirection.CENTER) }

    Box(
        modifier = modifier.pointerInput(base) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitFirstDown()
                    val downPos = down.position
                    val downTime = System.currentTimeMillis()
                    var lastPos: Offset = downPos
                    var inPopupMode = false
                    var longPressEvaluated = false

                    while (true) {
                        // BUG FIX: 指を止めたままだと awaitPointerEvent() がブロックし続け、
                        // 長押し判定が一度も走らない問題があった。
                        // withTimeoutOrNull で、長押し時間経過時に必ずタイムアウトさせる。
                        val event = if (inPopupMode || longPressEvaluated) {
                            // ポップアップモード突入後 or 長押し判定済みは通常待機
                            awaitPointerEvent()
                        } else {
                            val elapsed = System.currentTimeMillis() - downTime
                            val remaining = (longPressMillis - elapsed).coerceAtLeast(1L)
                            withTimeoutOrNull(remaining) { awaitPointerEvent() }
                        }

                        if (event == null) {
                            // 長押しタイムアウト発火: 動きが小さければポップアップ表示
                            val dx = lastPos.x - downPos.x
                            val dy = lastPos.y - downPos.y
                            val movement = sqrt(dx * dx + dy * dy)
                            if (movement < thresholdPx) {
                                inPopupMode = true
                                showPopup = true
                            }
                            longPressEvaluated = true
                            continue
                        }

                        val change = event.changes.firstOrNull() ?: break
                        val pos = change.position

                        if (!change.pressed) {
                            // 指を離した
                            val dir = if (inPopupMode) currentDirection
                                      else resolveDirection(downPos, lastPos, thresholdPx)
                            onKeyInput(KeyInput.Kana(base, dir))
                            showPopup = false
                            currentDirection = FlickDirection.CENTER
                            break
                        }

                        lastPos = pos

                        // ポップアップ表示中は指の位置で方向をリアルタイム更新
                        if (inPopupMode) {
                            currentDirection = resolveDirection(downPos, pos, thresholdPx)
                        }
                    }
                }
            }
        },
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = TextPrimary, fontSize = 18.sp)

        if (showPopup) {
            // 鍵の上部に浮かぶフリックポップアップ
            Popup(
                alignment = Alignment.TopCenter,
                offset = with(density) { IntOffset(0, -(110.dp.toPx()).toInt()) },
                properties = PopupProperties(focusable = false, dismissOnClickOutside = false)
            ) {
                FlickPopupContent(base = base, currentDirection = currentDirection)
            }
        }
    }
}

/**
 * フリックポップアップ本体。
 * 中央 + 上下左右の5マスに、フリック先の文字を表示する。
 * 現在の方向（指の位置から推定）はアクセント色でハイライト。
 *
 *      [up]
 * [left][cent][right]
 *      [down]
 */
@Composable
private fun FlickPopupContent(base: String, currentDirection: FlickDirection) {
    val options = KanaComposer.flickOptionsFor(base) ?: return
    if (options.size < 5) return
    val center = options[0]
    val left = options[1]
    val up = options[2]
    val right = options[3]
    val down = options[4]

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF1A1A1C))
            .padding(6.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PopupCell("", false)
                PopupCell(up, currentDirection == FlickDirection.UP)
                PopupCell("", false)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PopupCell(left, currentDirection == FlickDirection.LEFT)
                PopupCell(center, currentDirection == FlickDirection.CENTER, isCenter = true)
                PopupCell(right, currentDirection == FlickDirection.RIGHT)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                PopupCell("", false)
                PopupCell(down, currentDirection == FlickDirection.DOWN)
                PopupCell("", false)
            }
        }
    }
}

@Composable
private fun PopupCell(char: String, highlighted: Boolean, isCenter: Boolean = false) {
    val bg = when {
        highlighted -> Accent
        isCenter -> Color(0x33FFFFFF)
        else -> Color.Transparent
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(5.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        if (char.isNotEmpty()) {
            Text(
                text = char,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = if (highlighted) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

private fun resolveDirection(start: Offset, end: Offset, threshold: Float): FlickDirection {
    val dx = end.x - start.x
    val dy = end.y - start.y
    if (abs(dx) < threshold && abs(dy) < threshold) return FlickDirection.CENTER
    return if (abs(dx) > abs(dy)) {
        if (dx > 0) FlickDirection.RIGHT else FlickDirection.LEFT
    } else {
        if (dy > 0) FlickDirection.DOWN else FlickDirection.UP
    }
}

// 必要に応じて: awaitFirstDown のヘルパー
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDown():
        androidx.compose.ui.input.pointer.PointerInputChange {
    var event: androidx.compose.ui.input.pointer.PointerEvent
    do {
        event = awaitPointerEvent()
    } while (!event.changes.any { it.pressed && !it.previousPressed })
    return event.changes.first { it.pressed && !it.previousPressed }
}

// ============================================================================
// QWERTY ローマ字レイアウト
// ============================================================================
//
// 設計:
//   - 4行構成、合計幅 weight=10 で完全対称
//   - Row1: 10文字 (q-p)               → 1×10 = 10
//   - Row2: 9文字 + 両端 0.5 spacer    → 0.5 + 1×9 + 0.5 = 10
//   - Row3: shift 1.5 + 7文字 + back 1.5 → 1.5 + 7 + 1.5 = 10
//   - Row4: mode 1.5 + globe 1.5 + space 4 + comma 1 + enter 2 = 10
//
//   キー高さ 42dp (フリックの46dpより小さく、4行になっても全体高さ同等に)
//   コントロールキーは BgKeyCtrl で文字キーと視覚的に区別
//   Enter のみアクセント色 (AccentDark) で送信動作を示唆

@Composable
private fun QwertyGrid(
    shiftState: ShiftState,
    onKeyInput: (KeyInput) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgKeyboard)
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        // Row 1: q w e r t y u i o p
        QwertyLetterRow(
            letters = listOf("q","w","e","r","t","y","u","i","o","p"),
            shiftState = shiftState,
            onKeyInput = onKeyInput
        )
        // Row 2: a s d f g h j k l (with 0.5 spacer each side for stagger)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Spacer(modifier = Modifier.weight(0.5f))
            for (ch in listOf("a","s","d","f","g","h","j","k","l")) {
                Box(modifier = Modifier.weight(1f)) {
                    QwertyLetterKey(ch, shiftState, onKeyInput)
                }
            }
            Spacer(modifier = Modifier.weight(0.5f))
        }
        // Row 3: shift + z x c v b n m + backspace
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(modifier = Modifier.weight(1.5f)) {
                ShiftKey(state = shiftState, onClick = { onKeyInput(KeyInput.Shift) })
            }
            for (ch in listOf("z","x","c","v","b","n","m")) {
                Box(modifier = Modifier.weight(1f)) {
                    QwertyLetterKey(ch, shiftState, onKeyInput)
                }
            }
            Box(modifier = Modifier.weight(1.5f)) {
                QwertyControlKey(label = "⌫", onClick = { onKeyInput(KeyInput.Backspace) })
            }
        }
        // Row 4: あ + ⌨ + space + 、 + 改行
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(modifier = Modifier.weight(1.5f)) {
                QwertyControlKey(label = "あ", onClick = { onKeyInput(KeyInput.SwitchMode) })
            }
            Box(modifier = Modifier.weight(1.5f)) {
                QwertyControlKey(label = "⌨", onClick = { onKeyInput(KeyInput.SwitchKeyboard) })
            }
            Box(modifier = Modifier.weight(4f)) {
                QwertyControlKey(label = "", onClick = { onKeyInput(KeyInput.Space) })
            }
            Box(modifier = Modifier.weight(1f)) {
                QwertyControlKey(label = "、", onClick = { onKeyInput(KeyInput.RomajiChar(',')) })
            }
            Box(modifier = Modifier.weight(2f)) {
                QwertyControlKey(
                    label = "改行",
                    onClick = { onKeyInput(KeyInput.Enter) },
                    accentColor = true
                )
            }
        }
    }
}

@Composable
private fun QwertyLetterRow(
    letters: List<String>,
    shiftState: ShiftState,
    onKeyInput: (KeyInput) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        for (ch in letters) {
            Box(modifier = Modifier.weight(1f)) {
                QwertyLetterKey(ch, shiftState, onKeyInput)
            }
        }
    }
}

@Composable
private fun QwertyLetterKey(
    letter: String,
    shiftState: ShiftState,
    onKeyInput: (KeyInput) -> Unit
) {
    // シフト ON 中は大文字表示＆生英字として直接コミット
    // シフト OFF 中は小文字表示＆ローマ字→かな変換
    val displayLetter = if (shiftState.isShifted) letter.uppercase() else letter
    val ch = displayLetter[0]

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BgKey)
            .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .clickable {
                if (shiftState.isShifted) {
                    onKeyInput(KeyInput.AlphabetChar(ch))
                } else {
                    onKeyInput(KeyInput.RomajiChar(ch))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayLetter,
            color = TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.Normal
        )
    }
}

/**
 * シフトキー。3状態で見た目を切替:
 *   OFF        → グレー背景、黒アイコン
 *   SHIFT_ONCE → 白背景、紫アイコン（次の1文字大文字を示唆）
 *   CAPS_LOCK  → 薄紫背景、紫アイコン（持続を示唆）
 */
@Composable
private fun ShiftKey(state: ShiftState, onClick: () -> Unit) {
    val bg = when (state) {
        ShiftState.OFF -> BgKeyCtrl
        ShiftState.SHIFT_ONCE -> BgKey
        ShiftState.CAPS_LOCK -> AccentSoft
    }
    val fg = when (state) {
        ShiftState.OFF -> TextPrimary
        else -> AccentDark
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bg)
            .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Caps lock は ⇪ で固定状態を示唆、それ以外は ⇧
        val symbol = if (state == ShiftState.CAPS_LOCK) "⇪" else "⇧"
        Text(text = symbol, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QwertyControlKey(
    label: String,
    onClick: () -> Unit,
    accentColor: Boolean = false
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BgKeyCtrl)
            .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label,
                color = if (accentColor) AccentDark else TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
