package io.github.ari1re.kanjilock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TextTest {
    @Test fun parsesDictionaryTokens() {
        val t = TokenParser.parse("毎日[mainichi] 日本語[nihongo] を[o] 勉強します[benkyou shimasu] 。")
        assertEquals(5, t.size)
        assertEquals(Token("勉強します", "benkyou shimasu"), t[3])
        assertEquals(Token("。", ""), t[4])
        assertTrue(t[0].hasKanji)
        assertTrue(!t[2].hasKanji)
    }

    @Test fun kanaToRomaji() {
        assertEquals("nihongo", Kana.toRomaji("にほんご"))
        assertEquals("kyou", Kana.toRomaji("きょう"))
        assertEquals("gakkou", Kana.toRomaji("がっこう"))
        assertEquals("matcha", Kana.toRomaji("まっちゃ"))
        assertEquals("koohii", Kana.toRomaji("コーヒー"))
        assertEquals("shashin", Kana.toRomaji("しゃしん"))
        assertEquals("fairu", Kana.toRomaji("ファイル"))
    }

    @Test fun furiganaPlainAndKana() {
        val s = "私[わたし]は 日本語[にほんご]を 勉強[べんきょう]する"
        assertEquals("私は日本語を勉強する", Furigana.plain(s))
        assertEquals("わたしはにほんごをべんきょうする", Furigana.kana(s))
        assertEquals("I like it", Furigana.plain("I like it"))
    }

    @Test fun furiganaTokens() {
        val t = Furigana.tokens("私[わたし]は 日本語[にほんご]を 勉強[べんきょう]する。")
        assertEquals(
            listOf(
                Token("私", "watashi"), Token("は", "wa"), Token("日本語", "nihongo"), Token("を", "o"),
                Token("勉強", "benkyou"), Token("する", "suru"), Token("。", ""),
            ),
            t,
        )
    }
}
