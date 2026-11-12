package io.github.ari1re.kanjilock

/** One piece of an example sentence: the Japanese surface text and the romaji shown under it ("" = none). */
data class Token(val surface: String, val romaji: String) {
    val hasKanji: Boolean get() = surface.any(::isKanji)
}

fun isKanji(c: Char): Boolean = c in '一'..'鿿' || c in '㐀'..'䶿' || c == '々' || c == '〆'

fun isKana(c: Char): Boolean = c in 'ぁ'..'ゖ' || c in 'ァ'..'ヺ' || c == 'ー' || c == 'ゝ' || c == 'ゞ'

private const val PUNCT = "。、！？「」『』（）・…〜～!?,.()\"'：:；;【】［］ 　\n"

/** Parses the built-in dictionary's sentence format: `毎日[mainichi] 水[mizu] を[o] 飲みます[nomimasu] 。` */
object TokenParser {
    private val re = Regex("""([^\s\[\]]+)(?:\[([^\]]*)])?""")

    fun parse(s: String): List<Token> = re.findAll(s).map { m ->
        Token(m.groupValues[1], m.groupValues[2].trim())
    }.toList()
}

/** Kana → Hepburn romaji (good enough for readings; it can't know when は is the particle "wa"). */
object Kana {
    private val digraphs = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo", "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho", "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo", "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo", "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo", "ぢゃ" to "ja", "ぢゅ" to "ju", "ぢょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo", "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "しぇ" to "she", "じぇ" to "je", "ちぇ" to "che", "てぃ" to "ti", "でぃ" to "di", "とぅ" to "tu",
        "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo", "うぃ" to "wi", "うぇ" to "we", "うぉ" to "wo",
        "ゔぁ" to "va", "ゔぃ" to "vi", "ゔぇ" to "ve", "ゔぉ" to "vo", "つぁ" to "tsa",
    )
    private val mono = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'ゐ' to "i", 'ゑ' to "e", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        'ゔ' to "vu", 'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
        'ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo", 'ゎ' to "wa", 'ゕ' to "ka", 'ゖ' to "ke",
    )

    private fun toHiragana(s: String) = buildString {
        for (c in s) append(if (c in 'ァ'..'ヶ') c - 0x60 else c)
    }

    fun toRomaji(input: String): String {
        val s = toHiragana(input)
        val out = StringBuilder()
        var i = 0
        var geminate = false
        while (i < s.length) {
            val c = s[i]
            var piece: String? = null
            if (i + 1 < s.length) piece = digraphs[s.substring(i, i + 2)]
            val step = if (piece != null) 2 else 1
            if (piece == null) piece = mono[c]
            when {
                c == 'っ' -> { geminate = true; i++; continue }
                c == 'ー' -> {
                    val last = out.lastOrNull { it in "aeiou" }
                    if (last != null) out.append(last)
                    i++; continue
                }
                piece == null -> { out.append(c); i++; geminate = false; continue }
            }
            if (geminate) {
                out.append(if (piece.startsWith("ch")) 't' else piece[0])
                geminate = false
            }
            out.append(piece)
            i += step
        }
        return out.toString()
    }
}

/**
 * Anki furigana syntax: `私[わたし]は 日本語[にほんご]を 勉強[べんきょう]する`.
 * A reading in [brackets] applies to the text since the previous space (or start).
 */
object Furigana {
    private val ruby = Regex("""([^\s\[\]]+)\[([^\]]*)]""")

    /** Text with readings removed: 私は日本語を勉強する */
    fun plain(s: String): String = collapseJpSpaces(ruby.replace(s) { it.groupValues[1] })

    /** Reading with kanji swapped for their furigana: わたしはにほんごをべんきょうする */
    fun kana(s: String): String = collapseJpSpaces(ruby.replace(s) { it.groupValues[2] })

    fun hasRuby(s: String) = ruby.containsMatchIn(s)

    private fun collapseJpSpaces(s: String): String {
        val sb = StringBuilder()
        for (i in s.indices) {
            val c = s[i]
            if (c == ' ') {
                val prev = s.getOrNull(i - 1)
                val next = s.getOrNull(i + 1)
                if (prev != null && next != null && (isJp(prev) || isJp(next))) continue
            }
            sb.append(c)
        }
        return sb.toString().trim()
    }

    private fun isJp(c: Char) = isKana(c) || isKanji(c) || c in "。、！？「」『』（）・…"

    /** Splits a (possibly furigana-annotated) sentence into ruby tokens with romaji. */
    fun tokens(s: String): List<Token> {
        val out = mutableListOf<Token>()
        var last = 0
        for (m in ruby.findAll(s)) {
            plainTokens(s.substring(last, m.range.first), out)
            val reading = m.groupValues[2].trim()
            out += Token(m.groupValues[1], if (reading.all { isKana(it) }) Kana.toRomaji(reading) else "")
            last = m.range.last + 1
        }
        plainTokens(s.substring(last), out)
        return out
    }

    private fun plainTokens(seg: String, out: MutableList<Token>) {
        val run = StringBuilder()
        fun flush() {
            if (run.isEmpty()) return
            val text = run.toString()
            run.clear()
            // Keep chunks short so ruby rows can wrap.
            text.chunked(6).forEach { chunk ->
                val romaji = when {
                    chunk == "は" -> "wa"
                    chunk == "へ" -> "e"
                    chunk.all { isKana(it) } -> Kana.toRomaji(chunk)
                    else -> ""
                }
                out += Token(chunk, romaji)
            }
        }
        for (c in seg) {
            if (c in PUNCT) {
                flush()
                if (!c.isWhitespace()) out += Token(c.toString(), "")
            } else {
                // a particle glued after kana/kanji text still reads fine; split only on kana↔kanji boundaries
                if (run.isNotEmpty() && isKanji(run.last()) != isKanji(c)) flush()
                run.append(c)
            }
        }
        flush()
    }
}
