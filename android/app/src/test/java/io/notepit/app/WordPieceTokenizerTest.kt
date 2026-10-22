package io.notepit.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class WordPieceTokenizerTest {
    private val vocabulary = listOf(
        "[PAD]", "[UNK]", "[CLS]", "[SEP]", "[MASK]", "hello", "world", "cafe", ",", "!",
        "un", "##aff", "##able", "中", "文", "—", "i", "a", "##a"
    )

    private fun assertTokens(text: String, expected: List<String>, maxTokens: Int = 128) {
        val result = WordPieceTokenizer(vocabulary, maxTokens).encode(text)
        val all = listOf("[CLS]") + expected + "[SEP]"
        assertEquals(all.size, result.length)
        assertArrayEquals(all.map { vocabulary.indexOf(it).toLong() }.toLongArray(), result.ids.copyOf(result.length))
        assertArrayEquals(LongArray(maxTokens - result.length), result.ids.copyOfRange(result.length, maxTokens))
    }

    @Test fun lowercasesAndStripsCanonicalAccents() {
        assertTokens("HÉLLO,\tCAFE\u0301!", listOf("hello", ",", "cafe", "!"))
        assertTokens("İ", listOf("i"))
    }

    @Test fun cleansControlsAndTreatsUnicodeSpaceAsWhitespace() {
        assertTokens("hel\u0000lo\u200d\u00a0world\n", listOf("hello", "world"))
    }

    @Test fun splitsPunctuationAndChineseCodePoints() {
        assertTokens("hello—world!中文", listOf("hello", "—", "world", "!", "中", "文"))
    }

    @Test fun greedilyUsesContinuationPieces() {
        assertTokens("unaffable", listOf("un", "##aff", "##able"))
    }

    @Test fun emitsOneUnknownForAnUnsegmentableWholeWord() {
        assertTokens("unaffax", listOf("[UNK]"))
        assertTokens("unaffax", listOf("[UNK]"), maxTokens = 4)
        assertTokens("😃", listOf("[UNK]"))
    }

    @Test fun respectsWordPieceHundredCodePointLimit() {
        assertTokens("a".repeat(100), listOf("a") + List(99) { "##a" })
        assertTokens("a".repeat(101), listOf("[UNK]"))
    }

    @Test fun preservesLiteralBertSpecialTokens() {
        assertTokens("[CLS]Hello[MASK]", listOf("[CLS]", "hello", "[MASK]"))
    }

    @Test fun reservesFinalSeparatorWhenTruncating() {
        assertTokens("hello world cafe", listOf("hello", "world"), maxTokens = 4)
    }

    @Test fun clearsPaddingWhenReusingTokenStorage() {
        val tokenizer = WordPieceTokenizer(vocabulary, maxTokens = 8)
        val ids = LongArray(8)
        tokenizer.encodeInto("hello world cafe", ids)
        val length = tokenizer.encodeInto("hello", ids)
        assertEquals(3, length)
        assertArrayEquals(longArrayOf(2, 5, 3, 0, 0, 0, 0, 0), ids)
    }

    @Test fun handlesEmptyAndBoundedUtf16Input() {
        assertTokens("", emptyList())
        val result = WordPieceTokenizer(vocabulary, maxTextChars = 6).encode("hello😃world")
        assertArrayEquals(longArrayOf(2, 5, 3), result.ids.copyOf(result.length))
    }
}
