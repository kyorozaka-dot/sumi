package com.ogawa.sumi.ime

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.ogawa.sumi.ai.AISuggestion
import com.ogawa.sumi.ai.AISuggestionClient
import com.ogawa.sumi.ai.SuggestionContext
import com.ogawa.sumi.settings.KeyboardPreferences
import com.ogawa.sumi.ui.KeyboardScreen
import com.ogawa.sumi.ui.MinimalKeyboardTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ミニマル日本語キーボードのIMEサービス。
 *
 * 構造:
 *   InputMethodService
 *     ├─ ComposeView (keyboard UI)
 *     ├─ KanaComposer (フリック入力 → かな組み立て)
 *     ├─ ConversionEngine (かな → かな漢字変換) ※スタブ、本番はmozc等
 *     ├─ AISuggestionClient (会話文脈 → AI返信候補)
 *     └─ KeyboardStateHolder (Compose向け observable state)
 */
class MinimalKeyboardService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    // --- Lifecycle / ViewModel / SavedState owners (ComposeView用) ---
    private val lifecycleRegistry by lazy { LifecycleRegistry(this) }
    private val savedStateController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    // --- 依存コンポーネント ---
    private val flickComposer = KanaComposer()
    private val romajiComposer = RomajiComposer()
    private val conversion by lazy { SimpleDictConversionEngine(applicationContext) }
    private val state = KeyboardStateHolder()
    private val aiClient by lazy { AISuggestionClient(applicationContext) }
    private lateinit var kbPrefs: KeyboardPreferences

    /** 現在のモードに応じた composer を返す */
    private val activeComposer: InputComposer
        get() = when (state.inputMode) {
            InputMode.QWERTY_ROMAJI -> romajiComposer
            else -> flickComposer
        }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pendingSuggestionJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        // 設定 DataStore を初期化し、変更をリアルタイムで state に反映
        kbPrefs = KeyboardPreferences(applicationContext)
        serviceScope.launch {
            kbPrefs.snapshot.collect { snap ->
                state.theme           = snap.theme
                state.flickSensitivity = snap.flickSensitivity
                state.longPressMs     = snap.longPressMs
                state.haptic          = snap.haptic
                state.aiEnabled       = snap.aiEnabled
            }
        }
    }

    override fun onCreateInputView(): View {
        val composeView = ComposeView(this).apply {
            setContent {
                MinimalKeyboardTheme(themeMode = state.theme) {
                    KeyboardScreen(
                        state = state,
                        onKeyInput = ::handleKeyInput,
                        onCandidateSelect = ::handleCandidateSelect,
                        onAISuggestionSelect = ::handleAISuggestionSelect
                    )
                }
            }
        }
        // 🔑 ViewTree owners を ComposeView に設定（Android 16 クラッシュ修正）
        composeView.setViewTreeLifecycleOwner(this@MinimalKeyboardService)
        composeView.setViewTreeViewModelStoreOwner(this@MinimalKeyboardService)
        composeView.setViewTreeSavedStateRegistryOwner(this@MinimalKeyboardService)
        return composeView
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        super.onFinishInputView(finishingInput)
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        flickComposer.reset()
        romajiComposer.reset()
        state.reset()
        // 入力開始直後にも文脈から候補を出しておくと、最初の一文字目で待たせない
        scheduleAISuggestions(delayMillis = 200)
    }

    override fun onFinishInput() {
        flickComposer.reset()
        romajiComposer.reset()
        state.reset()
        pendingSuggestionJob?.cancel()
        super.onFinishInput()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        store.clear()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ====================================================================
    // 入力処理
    // ====================================================================

    private fun handleKeyInput(input: KeyInput) {
        performKeyFeedback()
        // シフトキー自体の処理は最優先で、状態リセット対象外
        if (input is KeyInput.Shift) {
            state.shiftState = state.shiftState.next()
            return
        }

        when (input) {
            is KeyInput.Kana -> {
                flickComposer.appendFlick(input.base, input.direction)
                onComposingChanged()
            }
            is KeyInput.RomajiChar -> {
                romajiComposer.input(input.char)
                onComposingChanged()
            }
            is KeyInput.AlphabetChar -> {
                // 保留中のかな入力があれば先に確定してから生英字をcommit
                if (!activeComposer.isEmpty()) {
                    activeComposer.finalizeInput()
                    val pending = activeComposer.currentText
                    currentInputConnection?.commitText(pending, 1)
                    activeComposer.reset()
                }
                currentInputConnection?.commitText(input.char.toString(), 1)
                state.composingText = ""
                state.candidates = emptyList()
                scheduleAISuggestions()
            }
            KeyInput.Backspace -> {
                val deletedFromComposer = activeComposer.deleteLast()
                if (deletedFromComposer) {
                    onComposingChanged()
                } else {
                    currentInputConnection?.deleteSurroundingText(1, 0)
                    scheduleAISuggestions()
                }
            }
            KeyInput.Space -> {
                // composing中ならスペースは「確定」として動かす（日本語IME慣例）
                if (activeComposer.isEmpty()) {
                    currentInputConnection?.commitText(" ", 1)
                } else {
                    activeComposer.finalizeInput()  // n → ん など
                    confirmFirstCandidate()
                }
            }
            KeyInput.Enter -> {
                if (activeComposer.isEmpty()) {
                    val ic = currentInputConnection
                    if (ic != null) {
                        // DOWN と UP の両方を送らないと多くのアプリで反応しない
                        ic.sendKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_DOWN,
                                android.view.KeyEvent.KEYCODE_ENTER
                            )
                        )
                        ic.sendKeyEvent(
                            android.view.KeyEvent(
                                android.view.KeyEvent.ACTION_UP,
                                android.view.KeyEvent.KEYCODE_ENTER
                            )
                        )
                    }
                } else {
                    activeComposer.finalizeInput()
                    confirmFirstCandidate()
                }
            }
            KeyInput.SwitchMode -> {
                // モード切替前に保留中の入力を確定（romajiの n などが取り残されないように）
                activeComposer.finalizeInput()
                if (!activeComposer.isEmpty()) {
                    currentInputConnection?.commitText(activeComposer.currentText, 1)
                    activeComposer.reset()
                    state.composingText = ""
                    state.candidates = emptyList()
                }
                state.inputMode = state.inputMode.next()
            }
            KeyInput.SwitchKeyboard -> {
                // システムIMEピッカーを開く（API 28+ でのみ利用可能）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    switchToNextInputMethod(false)
                } else {
                    // API 26-27 フォールバック: IMEピッカーダイアログを表示
                    val imm = getSystemService(INPUT_METHOD_SERVICE)
                            as? android.view.inputmethod.InputMethodManager
                    @Suppress("DEPRECATION")
                    imm?.showInputMethodPicker()
                }
            }
            KeyInput.Shift -> { /* 上で処理済み、ここには来ない */ }
        }

        // SHIFT_ONCE は何らかのアクション後に自動で OFF に戻す
        if (state.shiftState == ShiftState.SHIFT_ONCE) {
            state.shiftState = ShiftState.OFF
        }
    }

    private fun onComposingChanged() {
        val text = activeComposer.currentText
        currentInputConnection?.setComposingText(text, 1)
        state.composingText = text
        state.candidates = conversion.candidatesFor(text)
        scheduleAISuggestions()
    }

    private fun confirmFirstCandidate() {
        val text = state.candidates.firstOrNull() ?: activeComposer.currentText
        currentInputConnection?.commitText(text, 1)
        flickComposer.reset()
        romajiComposer.reset()
        state.composingText = ""
        state.candidates = emptyList()
        scheduleAISuggestions()
    }

    private fun handleCandidateSelect(candidate: String) {
        currentInputConnection?.commitText(candidate, 1)
        flickComposer.reset()
        romajiComposer.reset()
        state.composingText = ""
        state.candidates = emptyList()
        scheduleAISuggestions()
    }

    private fun handleAISuggestionSelect(suggestion: AISuggestion) {
        // 入力中のcomposingがある場合は捨てて、AI候補で置き換える
        if (!activeComposer.isEmpty()) {
            flickComposer.reset()
            romajiComposer.reset()
            currentInputConnection?.setComposingText("", 1)
            currentInputConnection?.finishComposingText()
        }
        currentInputConnection?.commitText(suggestion.text, 1)
        state.composingText = ""
        state.candidates = emptyList()
        state.aiSuggestions = emptyList()
    }

    // ====================================================================
    // キーフィードバック
    // ====================================================================

    /**
     * キー押下時の触覚フィードバック。
     * HAPTIC 設定が true のとき 20ms バイブレーション。
     * Android 12+ では VibratorManager 推奨だが、VibrationEffect.createOneShot は
     * API 26 以降利用可能（minSdk=26）なのでこの実装で問題ない。
     */
    @Suppress("DEPRECATION")
    private fun performKeyFeedback() {
        if (!state.haptic) return
        val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (vibrator.hasVibrator()) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        }
    }

    // ====================================================================
    // AI候補のトリガー（連打にデバウンス）
    // ====================================================================

    private fun scheduleAISuggestions(delayMillis: Long = 350) {
        // AI機能が無効なら候補をクリアして即終了
        if (!state.aiEnabled) {
            state.aiSuggestions = emptyList()
            return
        }
        pendingSuggestionJob?.cancel()
        pendingSuggestionJob = serviceScope.launch {
            delay(delayMillis)
            val ctx = collectContext() ?: return@launch
            state.aiLoading = true
            runCatching { aiClient.suggest(ctx) }
                .onSuccess { state.aiSuggestions = it }
                .onFailure { state.aiSuggestions = emptyList() }
            state.aiLoading = false
        }
    }

    private fun collectContext(): SuggestionContext? {
        val ic = currentInputConnection ?: return null
        val before = ic.getTextBeforeCursor(500, 0)?.toString().orEmpty()
        val after = ic.getTextAfterCursor(200, 0)?.toString().orEmpty()
        return SuggestionContext(
            textBeforeCursor = before,
            textAfterCursor = after,
            composingText = activeComposer.currentText,
            appPackage = currentInputEditorInfo?.packageName.orEmpty(),
            tonePreference = "auto" // TODO: DataStoreから読み込み
        )
    }
}

// ============================================================================
// 入力アクション
// ============================================================================

sealed interface KeyInput {
    data class Kana(val base: String, val direction: FlickDirection) : KeyInput   // フリック
    data class RomajiChar(val char: Char) : KeyInput                                // QWERTY 1文字 (かな変換対象)
    data class AlphabetChar(val char: Char) : KeyInput                              // QWERTY 1文字 (生の英字をそのままcommit)
    data object Shift : KeyInput
    data object Backspace : KeyInput
    data object Space : KeyInput
    data object Enter : KeyInput
    data object SwitchMode : KeyInput
    data object SwitchKeyboard : KeyInput
}

enum class FlickDirection { CENTER, LEFT, UP, RIGHT, DOWN }

/**
 * シフトキーの状態。OFF → SHIFT_ONCE → CAPS_LOCK → OFF と循環する。
 *   - OFF        : 通常入力（ローマ字→かな変換）
 *   - SHIFT_ONCE : 次の1文字だけ大文字英字を直接コミット、その後 OFF に戻る
 *   - CAPS_LOCK  : すべての英字を大文字直接コミット、再タップで OFF
 */
enum class ShiftState {
    OFF, SHIFT_ONCE, CAPS_LOCK;

    fun next(): ShiftState = when (this) {
        OFF -> SHIFT_ONCE
        SHIFT_ONCE -> CAPS_LOCK
        CAPS_LOCK -> OFF
    }

    /** いずれかの大文字モード（SHIFT_ONCE か CAPS_LOCK）か */
    val isShifted: Boolean get() = this != OFF
}

enum class InputMode {
    FLICK_KANA,       // 12キーフリック → ひらがな
    QWERTY_ROMAJI,    // QWERTY → ローマ字変換 → ひらがな
    QWERTY_ALPHABET,  // QWERTY → 英字直接入力 (TODO: UI 未実装)
    NUMBER;           // 数字パッド (TODO: UI 未実装)

    /**
     * モード循環。現状は実装済みの2モードのみを行き来する。
     * QWERTY_ALPHABET / NUMBER の UI を追加したら全モード循環に変更すること。
     */
    fun next(): InputMode = when (this) {
        FLICK_KANA -> QWERTY_ROMAJI
        QWERTY_ROMAJI -> FLICK_KANA
        else -> FLICK_KANA
    }
}

// ============================================================================
// InputComposer: かな組み立ての共通インターフェース
// ============================================================================

interface InputComposer {
    val currentText: String
    fun isEmpty(): Boolean
    fun deleteLast(): Boolean
    fun reset()
    fun finalizeInput()  // 保留中の状態を確定（例: ローマ字の n → ん）
}

// ============================================================================
// かな組み立て（フリック入力用）
// ============================================================================

class KanaComposer : InputComposer {
    private val buffer = StringBuilder()
    override val currentText: String get() = buffer.toString()

    override fun isEmpty(): Boolean = buffer.isEmpty()

    fun appendFlick(base: String, dir: FlickDirection) {
        val row = FLICK_TABLE[base] ?: return
        val char = when (dir) {
            FlickDirection.CENTER -> row[0]
            FlickDirection.LEFT -> row[1]
            FlickDirection.UP -> row[2]
            FlickDirection.RIGHT -> row[3]
            FlickDirection.DOWN -> row[4]
        }
        if (char.isNotEmpty()) buffer.append(char)
    }

    override fun deleteLast(): Boolean {
        if (buffer.isEmpty()) return false
        buffer.deleteCharAt(buffer.length - 1)
        return true
    }

    override fun reset() { buffer.clear() }

    /** フリック入力には保留状態がないため何もしない */
    override fun finalizeInput() {}

    companion object {
        // 各行: [center=あ段, left=い段, up=う段, right=え段, down=お段]
        private val FLICK_TABLE: Map<String, List<String>> = mapOf(
            "あ" to listOf("あ", "い", "う", "え", "お"),
            "か" to listOf("か", "き", "く", "け", "こ"),
            "さ" to listOf("さ", "し", "す", "せ", "そ"),
            "た" to listOf("た", "ち", "つ", "て", "と"),
            "な" to listOf("な", "に", "ぬ", "ね", "の"),
            "は" to listOf("は", "ひ", "ふ", "へ", "ほ"),
            "ま" to listOf("ま", "み", "む", "め", "も"),
            "や" to listOf("や", "(", "ゆ", ")", "よ"),
            "ら" to listOf("ら", "り", "る", "れ", "ろ"),
            "わ" to listOf("わ", "を", "ん", "ー", "〜"),
            "、" to listOf("、", "。", "?", "!", "…")
        )

        /** UI（長押しポップアップ）からフリック候補を取得するための公開API */
        fun flickOptionsFor(base: String): List<String>? = FLICK_TABLE[base]
    }
}

// ============================================================================
// Compose向け observable state
// ============================================================================

class KeyboardStateHolder {
    // --- 入力状態 ---
    var composingText by mutableStateOf("")
    var candidates by mutableStateOf<List<String>>(emptyList())
    var aiSuggestions by mutableStateOf<List<AISuggestion>>(emptyList())
    var aiLoading by mutableStateOf(false)
    var inputMode by mutableStateOf(InputMode.FLICK_KANA)
    var shiftState by mutableStateOf(ShiftState.OFF)

    // --- 設定値（DataStore Flow から MinimalKeyboardService が購読して更新）---
    // 注意: 初期レイアウト変更は次回 onStartInputView 起動時のみ有効。
    //       サービス起動中の inputMode はリセットしない（UX 上問題なし）。
    var theme by mutableStateOf("auto")
    var flickSensitivity by mutableStateOf(50)   // 0(鈍感)〜100(敏感)
    var longPressMs by mutableStateOf(250)        // 長押し判定ミリ秒
    var haptic by mutableStateOf(true)            // バイブレーション on/off
    var aiEnabled by mutableStateOf(true)         // AI候補バー表示 on/off

    fun reset() {
        composingText = ""
        candidates = emptyList()
        aiSuggestions = emptyList()
        aiLoading = false
        shiftState = ShiftState.OFF
        // 設定値（theme / flickSensitivity / longPressMs / haptic / aiEnabled）は
        // DataStore が管理するため reset() の対象外
    }
}
