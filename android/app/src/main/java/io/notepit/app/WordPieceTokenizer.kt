package io.notepit.app

import java.text.Normalizer
import java.util.Locale

/** BERT uncased BasicTokenizer + greedy WordPiece, matching the pinned MiniLM vocabulary. */
internal class WordPieceTokenizer(
    vocabulary: List<String>,
    private val maxTokens: Int = 128,
    private val maxTextChars: Int = 8192
) {
    private val vocabulary = vocabulary.withIndex().associate { it.value to it.index.toLong() }
    private val unknown = requireNotNull(this.vocabulary["[UNK]"])
    private val cls = requireNotNull(this.vocabulary["[CLS]"])
    private val sep = requireNotNull(this.vocabulary["[SEP]"])
    private val pad = requireNotNull(this.vocabulary["[PAD]"])

    init {
        require(maxTokens >= 2 && maxTextChars > 0)
    }

    data class Tokens(val ids: LongArray, val length: Int)

    fun encode(text: String): Tokens {
        val ids = LongArray(maxTokens)
        return Tokens(ids, encodeInto(text, ids))
    }

    /** Caller-owned storage allows the inference session to reuse its token IDs. */
    fun encodeInto(text: String, ids: LongArray): Int {
        require(ids.size == maxTokens)
        ids.fill(pad)
        var count = 1
        ids[0] = cls
        var end = minOf(text.length, maxTextChars)
        if (end < text.length && end > 0 && Character.isHighSurrogate(text[end - 1])) end--
        val bounded = text.substring(0, end)

        fun appendBasic(segment: String) {
            val clean = StringBuilder(segment.length)
            var offset = 0
            while (offset < segment.length) {
                val cp = segment.codePointAt(offset)
                offset += Character.charCount(cp)
                when {
                    cp == 0 || cp == 0xfffd -> Unit
                    isWhitespace(cp) -> clean.append(' ')
                    isControl(cp) -> Unit
                    isChinese(cp) || isPunctuation(cp) -> clean.append(' ').appendCodePoint(cp).append(' ')
                    else -> clean.appendCodePoint(cp)
                }
            }
            val normalized = Normalizer.normalize(clean.toString().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            val token = StringBuilder()
            fun appendToken() {
                if (token.isEmpty() || count >= maxTokens - 1) { token.setLength(0); return }
                count = appendWordPieces(token.toString(), ids, count)
                token.setLength(0)
            }
            offset = 0
            while (offset < normalized.length && count < maxTokens - 1) {
                val cp = normalized.codePointAt(offset)
                offset += Character.charCount(cp)
                if (Character.getType(cp) == Character.NON_SPACING_MARK.toInt()) continue
                if (isWhitespace(cp)) appendToken() else token.appendCodePoint(cp)
            }
            appendToken()
        }

        var start = 0
        for (special in SPECIAL.findAll(bounded)) {
            if (count >= maxTokens - 1) break
            appendBasic(bounded.substring(start, special.range.first))
            if (count < maxTokens - 1) ids[count++] = vocabulary[special.value] ?: unknown
            start = special.range.last + 1
        }
        if (count < maxTokens - 1) appendBasic(bounded.substring(start))
        ids[count++] = sep
        return count
    }

    private fun appendWordPieces(token: String, ids: LongArray, initialCount: Int): Int {
        if (token.codePointCount(0, token.length) > 100) {
            ids[initialCount] = unknown
            return initialCount + 1
        }
        var count = initialCount
        var start = 0
        while (start < token.length) {
            var end = token.length
            var match: Long? = null
            while (end > start) {
                val candidate = (if (start == 0) "" else "##") + token.substring(start, end)
                match = vocabulary[candidate]
                if (match != null) break
                end = token.offsetByCodePoints(end, -1)
            }
            if (match == null) {
                ids.fill(pad, initialCount, count)
                ids[initialCount] = unknown
                return initialCount + 1
            }
            if (count < maxTokens - 1) ids[count++] = match
            start = end
        }
        return count
    }

    private fun isWhitespace(cp: Int): Boolean = cp == 32 || cp == 9 || cp == 10 || cp == 13 ||
        Character.getType(cp) == Character.SPACE_SEPARATOR.toInt()

    private fun isControl(cp: Int): Boolean = Character.getType(cp).let {
        it == Character.CONTROL.toInt() || it == Character.FORMAT.toInt()
    }

    private fun isPunctuation(cp: Int): Boolean = cp in 33..47 || cp in 58..64 || cp in 91..96 || cp in 123..126 ||
        Character.getType(cp).let {
            it == Character.CONNECTOR_PUNCTUATION.toInt() || it == Character.DASH_PUNCTUATION.toInt() ||
                it == Character.START_PUNCTUATION.toInt() || it == Character.END_PUNCTUATION.toInt() ||
                it == Character.INITIAL_QUOTE_PUNCTUATION.toInt() || it == Character.FINAL_QUOTE_PUNCTUATION.toInt() ||
                it == Character.OTHER_PUNCTUATION.toInt()
        }

    private fun isChinese(cp: Int): Boolean = cp in 0x4e00..0x9fff || cp in 0x3400..0x4dbf ||
        cp in 0x20000..0x2a6df || cp in 0x2a700..0x2b73f || cp in 0x2b740..0x2b81f ||
        cp in 0x2b820..0x2ceaf || cp in 0xf900..0xfaff || cp in 0x2f800..0x2fa1f

    companion object {
        private val SPECIAL = Regex("\\[(?:UNK|SEP|PAD|CLS|MASK)]")
    }
}
