package com.ogawa.sumi.settings

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.ogawa.sumi.ai.ApiKeyStorage
import kotlinx.coroutines.launch

// ============================================================================
// Activity
// ============================================================================

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = KeyboardPreferences(applicationContext)
        val apiKeyStorage = ApiKeyStorage(applicationContext)

        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Accent)) {
                val snapshot by prefs.snapshot.collectAsState(
                    initial = KeyboardPreferences.Snapshot()
                )
                SettingsScreen(
                    snapshot = snapshot,
                    apiKeyStorage = apiKeyStorage,
                    onBooleanChange = { key, value ->
                        lifecycleScope.launch { prefs.setBoolean(key, value) }
                    },
                    onStringChange = { key, value ->
                        lifecycleScope.launch { prefs.setString(key, value) }
                    },
                    onIntChange = { key, value ->
                        lifecycleScope.launch { prefs.setInt(key, value) }
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

// ============================================================================
// 設計トークン（KeyboardScreen.kt と同じカラーパレット）
// ============================================================================

private val BgPage = Color(0xFFF3F2EE)
private val BgRow = Color(0xFFFFFFFF)
private val BgRowHover = Color(0xFFFAFAF7)
private val BgToggleOff = Color(0xFFD6D4CE)
private val TextPrimary = Color(0xFF1F1F1D)
private val TextSecondary = Color(0xFF9A9A96)
private val TextDestructive = Color(0xFFA32D2D)
private val Accent = Color(0xFF7F77DD)
private val BorderSubtle = Color(0x14000000)

// ============================================================================
// SettingsScreen
// ============================================================================

@Composable
fun SettingsScreen(
    snapshot: KeyboardPreferences.Snapshot,
    apiKeyStorage: ApiKeyStorage,
    onBooleanChange: (androidx.datastore.preferences.core.Preferences.Key<Boolean>, Boolean) -> Unit,
    onStringChange: (androidx.datastore.preferences.core.Preferences.Key<String>, String) -> Unit,
    onIntChange: (androidx.datastore.preferences.core.Preferences.Key<Int>, Int) -> Unit,
    onBack: () -> Unit
) {
    var apiKeyState by remember { mutableStateOf(apiKeyStorage.getApiKey()) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    if (showApiKeyDialog) {
        ApiKeyDialog(
            currentKey = apiKeyState,
            onSave = { key ->
                apiKeyStorage.saveApiKey(key)
                apiKeyState = apiKeyStorage.getApiKey()
                showApiKeyDialog = false
            },
            onDelete = {
                apiKeyStorage.clearApiKey()
                apiKeyState = null
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BgPage)
    ) {
        TopBar(onBack = onBack)
        Column(modifier = Modifier.verticalScroll(rememberScrollState())) {

            // ---------- AI機能 ----------
            SectionHeader("AI機能", showAccentDot = true)
            Section {
                ToggleRow(
                    label = "AI候補を表示",
                    checked = snapshot.aiEnabled,
                    onChange = { onBooleanChange(KeyboardPreferences.Keys.AI_ENABLED, it) }
                )
                PickerRow(
                    label = "トーン",
                    value = toneLabel(snapshot.aiTone),
                    options = listOf("自動" to "auto", "敬語" to "polite", "カジュアル" to "casual", "フォーマル" to "formal"),
                    onSelect = { onStringChange(KeyboardPreferences.Keys.AI_TONE, it) }
                )
                PickerRow(
                    label = "候補の数",
                    value = "${snapshot.aiCount}",
                    options = listOf("1" to "1", "3" to "3", "5" to "5"),
                    onSelect = { onIntChange(KeyboardPreferences.Keys.AI_COUNT, it.toInt()) }
                )
                ToggleRow(
                    label = "オンライン処理",
                    description = "入力を外部に送信します",
                    checked = snapshot.aiAllowCloud,
                    onChange = { onBooleanChange(KeyboardPreferences.Keys.AI_ALLOW_CLOUD, it) },
                    isLast = true
                )
            }

            // ---------- 入力 ----------
            SectionHeader("入力")
            Section {
                SliderRow(
                    label = "フリック感度",
                    value = snapshot.flickSensitivity.toFloat(),
                    valueRange = 0f..100f,
                    onChange = { onIntChange(KeyboardPreferences.Keys.FLICK_SENSITIVITY, it.toInt()) }
                )
                PickerRow(
                    label = "長押し時間",
                    value = longPressLabel(snapshot.longPressMs),
                    options = listOf("短い" to "150", "標準" to "250", "長い" to "400", "とても長い" to "600"),
                    onSelect = { onIntChange(KeyboardPreferences.Keys.LONG_PRESS_MS, it.toInt()) }
                )
                ToggleRow(
                    label = "振動フィードバック",
                    checked = snapshot.haptic,
                    onChange = { onBooleanChange(KeyboardPreferences.Keys.HAPTIC, it) },
                    isLast = true
                )
            }

            // ---------- 外観 ----------
            SectionHeader("外観")
            Section {
                PickerRow(
                    label = "テーマ",
                    value = themeLabel(snapshot.theme),
                    options = listOf("自動" to "auto", "ライト" to "light", "ダーク" to "dark"),
                    onSelect = { onStringChange(KeyboardPreferences.Keys.THEME, it) }
                )
                SliderRow(
                    label = "キーボードの高さ",
                    value = snapshot.keyboardHeight.toFloat(),
                    valueRange = 40f..100f,
                    onChange = { onIntChange(KeyboardPreferences.Keys.KEYBOARD_HEIGHT, it.toInt()) },
                    isLast = true
                )
            }

            // ---------- AI設定 ----------
            SectionHeader("AI設定")
            Section {
                ApiKeyRow(
                    currentKey = apiKeyState,
                    onTap = { showApiKeyDialog = true },
                    isLast = true
                )
            }

            // ---------- プライバシー ----------
            SectionHeader("プライバシー")
            Section {
                ActionRow(label = "学習データを管理", onClick = { /* TODO */ })
                ActionRow(
                    label = "入力履歴をすべて削除",
                    destructive = true,
                    onClick = { /* TODO */ },
                    isLast = true
                )
            }

            // ---------- フッタ ----------
            Text(
                text = "すべての変換処理は端末内で完結します。\nAI候補の生成は設定で有効化できます。",
                fontSize = 11.sp,
                color = TextSecondary,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 24.dp),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
        }
    }
}

// ============================================================================
// 構成要素
// ============================================================================

@Composable
private fun TopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgRow)
            .padding(horizontal = 16.dp)
            .height(52.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 戻るボタン (chevron)
        Box(
            modifier = Modifier.size(28.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "‹", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Normal)
        }
        Spacer(modifier = Modifier.size(8.dp))
        Text(text = "設定", color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SectionHeader(label: String, showAccentDot: Boolean = false) {
    Row(
        modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        if (showAccentDot) {
            Box(
                modifier = Modifier.size(5.dp).clip(CircleShape).background(Accent)
            )
        }
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            letterSpacing = 1.2.sp
        )
    }
}

@Composable
private fun Section(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgRow)
    ) { content() }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    description: String? = null,
    isLast: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, color = TextPrimary, fontSize = 14.sp)
            if (description != null) {
                Text(
                    text = description,
                    color = TextSecondary,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
        // ToggleSwitch のクリックは親 Row が処理するので、内部 .clickable は不要
        ToggleSwitchDisplay(checked = checked)
    }
    if (!isLast) RowDivider()
}

/**
 * トグルスイッチの「見た目」だけを描画する。
 * クリック処理は親（ToggleRow）が担当するので、ここには clickable を付けない。
 */
@Composable
private fun ToggleSwitchDisplay(checked: Boolean) {
    val bg = if (checked) Accent else BgToggleOff
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 24.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .size(20.dp)
                .clip(CircleShape)
                .background(Color.White)
        )
    }
}

@Composable
private fun PickerRow(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    isLast: Boolean = false
) {
    // タップで次の選択肢に循環（モバイルでミニマル設計のための簡易実装）
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val currentIndex = options.indexOfFirst { it.first == value }
                // indexOfFirst が -1 を返した場合も (currentIndex + 1) は 0 になる
                val nextIndex = (currentIndex + 1) % options.size
                onSelect(options[nextIndex].second)
            }
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = value, color = TextSecondary, fontSize = 13.sp)
            Spacer(modifier = Modifier.size(4.dp))
            Text(text = "›", color = TextSecondary, fontSize = 16.sp)
        }
    }
    if (!isLast) RowDivider()
}

@Composable
private fun SliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    isLast: Boolean = false
) {
    // remember(value) で外部からの値変更にも追従する。
    // ユーザーがドラッグ中はその時点の value（DataStore反映前）と一致するので問題なし。
    var localValue by remember(value) { mutableFloatStateOf(value) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, color = TextPrimary, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Box(modifier = Modifier.size(width = 120.dp, height = 24.dp)) {
            Slider(
                value = localValue,
                onValueChange = { localValue = it },
                onValueChangeFinished = { onChange(localValue) },
                valueRange = valueRange,
                colors = SliderDefaults.colors(
                    thumbColor = Accent,
                    activeTrackColor = Accent,
                    inactiveTrackColor = BgToggleOff
                )
            )
        }
    }
    if (!isLast) RowDivider()
}

@Composable
private fun ActionRow(
    label: String,
    onClick: () -> Unit,
    destructive: Boolean = false,
    isLast: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (destructive) TextDestructive else TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Text(text = "›", color = TextSecondary, fontSize = 16.sp)
    }
    if (!isLast) RowDivider()
}

@Composable
private fun RowDivider() {
    Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(BorderSubtle))
}

@Composable
private fun ApiKeyRow(
    currentKey: String?,
    onTap: () -> Unit,
    isLast: Boolean = false
) {
    val displayValue = when {
        currentKey == null -> "未設定"
        currentKey.length >= 5 -> "設定済み (${currentKey.take(5)}****)"
        else -> "設定済み (****)"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onTap)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Anthropic APIキー",
            color = TextPrimary,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = displayValue, color = TextSecondary, fontSize = 13.sp)
            Spacer(modifier = Modifier.size(4.dp))
            Text(text = "›", color = TextSecondary, fontSize = 16.sp)
        }
    }
    if (!isLast) RowDivider()
}

@Composable
private fun ApiKeyDialog(
    currentKey: String?,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = BgRow,
        shape = RoundedCornerShape(12.dp),
        title = {
            Text(
                text = "Anthropic APIキー",
                color = TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (currentKey != null) {
                    Text(
                        text = "現在: ${currentKey.take(5)}****",
                        color = TextSecondary,
                        fontSize = 11.sp
                    )
                }
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text("sk-ant-...", color = TextSecondary, fontSize = 13.sp)
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(0.5.dp, BorderSubtle, RoundedCornerShape(6.dp)),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = Accent
                    ),
                    shape = RoundedCornerShape(6.dp)
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (currentKey != null) {
                    TextButton(onClick = onDelete) {
                        Text("削除", color = TextDestructive, fontSize = 13.sp)
                    }
                }
                TextButton(
                    onClick = { if (inputText.isNotBlank()) onSave(inputText.trim()) },
                    enabled = inputText.isNotBlank()
                ) {
                    Text(
                        "保存",
                        color = if (inputText.isNotBlank()) Accent else TextSecondary,
                        fontSize = 13.sp
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("キャンセル", color = TextSecondary, fontSize = 13.sp)
            }
        }
    )
}

// ============================================================================
// ラベル整形
// ============================================================================

private fun toneLabel(value: String): String = when (value) {
    "polite" -> "敬語"
    "casual" -> "カジュアル"
    "formal" -> "フォーマル"
    else -> "自動"
}

private fun longPressLabel(ms: Int): String = when {
    ms <= 150 -> "短い"
    ms <= 250 -> "標準"
    ms <= 400 -> "長い"
    else -> "とても長い"
}

private fun themeLabel(value: String): String = when (value) {
    "light" -> "ライト"
    "dark" -> "ダーク"
    else -> "自動"
}
