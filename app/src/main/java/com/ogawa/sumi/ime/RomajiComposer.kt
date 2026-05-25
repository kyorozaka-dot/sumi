package com.ogawa.sumi.ime

/**
 * ローマ字 → かな 変換エンジン。
 *
 * 入力は1文字ずつ `input(c)` で受け取り、内部で
 *   - committed: 確定済みかな
 *   - pending:   未確定ローマ字（次の文字待ち）
 * を保持する。
 *
 * IME側では `currentText`（commit + pending）を composing region として表示し、
 * 候補選択や Enter で `finalizeInput()` してから commitText するのが想定。
 */
class RomajiComposer : InputComposer {

    private val committed = StringBuilder()
    private val pending = StringBuilder()

    override val currentText: String get() = committed.toString() + pending.toString()
    val committedText: String get() = committed.toString()
    val pendingRomaji: String get() = pending.toString()

    override fun isEmpty(): Boolean = committed.isEmpty() && pending.isEmpty()

    fun input(c: Char) {
        // 特殊: n' → ん（n のあとアポストロフィで強制確定）
        if (pending.toString() == "n" && c == '\'') {
            committed.append('ん')
            pending.clear()
            return
        }

        val lower = c.lowercaseChar()
        val isLetter = lower in 'a'..'z'

        if (!isLetter) {
            // ローマ字以外: 保留中の n を ん として確定してから処理
            flushPendingN()
            val punctuation = ROMAJI_TABLE[lower.toString()]
            if (punctuation != null) {
                committed.append(punctuation)
            } else {
                committed.append(c)
            }
            pending.clear()
            return
        }

        // 促音 (sokuon): 保留中が単一の子音で、同じ子音が来たら っ
        //   ただし n は撥音処理に回す
        //   l, x は小書き仮名トリガーなので促音から除外
        if (pending.length == 1 && pending[0] == lower &&
            isSokuonConsonant(lower)) {
            committed.append('っ')
            pending.clear()
            pending.append(lower)
            return
        }

        pending.append(lower)
        tryConvert()
    }

    /**
     * 確定タイミング（Enter, Space, 候補確定など）で呼び出す。
     * 保留中の "n" を ん として確定する。それ以外の未確定は残す。
     */
    override fun finalizeInput() {
        flushPendingN()
    }

    override fun deleteLast(): Boolean {
        if (pending.isNotEmpty()) {
            pending.deleteCharAt(pending.length - 1)
            return true
        }
        if (committed.isNotEmpty()) {
            committed.deleteCharAt(committed.length - 1)
            return true
        }
        return false
    }

    override fun reset() {
        committed.clear()
        pending.clear()
    }

    // ------------------------------------------------------------------------
    // private
    // ------------------------------------------------------------------------

    private fun tryConvert() {
        while (pending.isNotEmpty()) {
            val str = pending.toString()
            val match = ROMAJI_TABLE[str]

            if (match != null) {
                committed.append(match)
                pending.clear()
                return
            }

            // 延長して一致しうるなら待つ
            if (str in ROMAJI_PREFIXES) return

            // 行き詰まり: 先頭 n は ん として吐く
            if (str[0] == 'n' && str.length >= 2) {
                committed.append('ん')
                pending.deleteCharAt(0)
                continue
            }

            // それ以外: 先頭を raw として吐いて再試行
            committed.append(pending[0])
            pending.deleteCharAt(0)
        }
    }

    private fun flushPendingN() {
        if (pending.toString() == "n") {
            committed.append('ん')
            pending.clear()
        }
    }

    /** 促音トリガーとなる子音。l/x は小書き仮名用なので除外、n も除外 */
    private fun isSokuonConsonant(c: Char): Boolean =
        c in "bcdfghjkmpqrstvwyz"

    companion object {
        private val ROMAJI_TABLE: Map<String, String> = mapOf(
            // 母音
            "a" to "あ", "i" to "い", "u" to "う", "e" to "え", "o" to "お",
            // か行
            "ka" to "か", "ki" to "き", "ku" to "く", "ke" to "け", "ko" to "こ",
            "kya" to "きゃ", "kyi" to "きぃ", "kyu" to "きゅ", "kye" to "きぇ", "kyo" to "きょ",
            // さ行
            "sa" to "さ", "si" to "し", "shi" to "し", "su" to "す", "se" to "せ", "so" to "そ",
            "sha" to "しゃ", "shu" to "しゅ", "she" to "しぇ", "sho" to "しょ",
            "sya" to "しゃ", "syu" to "しゅ", "syo" to "しょ",
            // た行
            "ta" to "た", "ti" to "ち", "chi" to "ち", "tu" to "つ", "tsu" to "つ",
            "te" to "て", "to" to "と",
            "cha" to "ちゃ", "chu" to "ちゅ", "che" to "ちぇ", "cho" to "ちょ",
            "tya" to "ちゃ", "tyu" to "ちゅ", "tyo" to "ちょ",
            "tha" to "てゃ", "thi" to "てぃ", "thu" to "てゅ", "the" to "てぇ", "tho" to "てょ",
            // な行
            "na" to "な", "ni" to "に", "nu" to "ぬ", "ne" to "ね", "no" to "の",
            "nya" to "にゃ", "nyu" to "にゅ", "nyo" to "にょ",
            "nn" to "ん", "xn" to "ん",
            // は行
            "ha" to "は", "hi" to "ひ", "hu" to "ふ", "fu" to "ふ", "he" to "へ", "ho" to "ほ",
            "hya" to "ひゃ", "hyu" to "ひゅ", "hyo" to "ひょ",
            "fa" to "ふぁ", "fi" to "ふぃ", "fe" to "ふぇ", "fo" to "ふぉ",
            // ま行
            "ma" to "ま", "mi" to "み", "mu" to "む", "me" to "め", "mo" to "も",
            "mya" to "みゃ", "myu" to "みゅ", "myo" to "みょ",
            // や行
            "ya" to "や", "yu" to "ゆ", "yo" to "よ",
            // ら行
            "ra" to "ら", "ri" to "り", "ru" to "る", "re" to "れ", "ro" to "ろ",
            "rya" to "りゃ", "ryu" to "りゅ", "ryo" to "りょ",
            // わ行
            "wa" to "わ", "wo" to "を",
            // が行
            "ga" to "が", "gi" to "ぎ", "gu" to "ぐ", "ge" to "げ", "go" to "ご",
            "gya" to "ぎゃ", "gyu" to "ぎゅ", "gyo" to "ぎょ",
            // ざ行
            "za" to "ざ", "zi" to "じ", "ji" to "じ", "zu" to "ず", "ze" to "ぜ", "zo" to "ぞ",
            "zya" to "じゃ", "zyu" to "じゅ", "zyo" to "じょ",
            "ja" to "じゃ", "ju" to "じゅ", "jo" to "じょ", "je" to "じぇ",
            "jya" to "じゃ", "jyu" to "じゅ", "jyo" to "じょ",
            // だ行
            "da" to "だ", "di" to "ぢ", "du" to "づ", "de" to "で", "do" to "ど",
            "dya" to "ぢゃ", "dyu" to "ぢゅ", "dyo" to "ぢょ",
            // ば行
            "ba" to "ば", "bi" to "び", "bu" to "ぶ", "be" to "べ", "bo" to "ぼ",
            "bya" to "びゃ", "byu" to "びゅ", "byo" to "びょ",
            // ぱ行
            "pa" to "ぱ", "pi" to "ぴ", "pu" to "ぷ", "pe" to "ぺ", "po" to "ぽ",
            "pya" to "ぴゃ", "pyu" to "ぴゅ", "pyo" to "ぴょ",
            // 小書き仮名 (l/x プレフィックス)
            "la" to "ぁ", "li" to "ぃ", "lu" to "ぅ", "le" to "ぇ", "lo" to "ぉ",
            "xa" to "ぁ", "xi" to "ぃ", "xu" to "ぅ", "xe" to "ぇ", "xo" to "ぉ",
            "lya" to "ゃ", "lyu" to "ゅ", "lyo" to "ょ",
            "xya" to "ゃ", "xyu" to "ゅ", "xyo" to "ょ",
            "ltu" to "っ", "xtu" to "っ", "ltsu" to "っ", "xtsu" to "っ",
            // 記号
            "-" to "ー",
            "," to "、",
            "." to "。"
        )

        /** ROMAJI_TABLE のキーから派生する「延長可能なプレフィックス集合」 */
        private val ROMAJI_PREFIXES: Set<String> = run {
            val set = mutableSetOf<String>()
            for (key in ROMAJI_TABLE.keys) {
                for (len in 1 until key.length) set.add(key.substring(0, len))
            }
            set
        }
    }
}
