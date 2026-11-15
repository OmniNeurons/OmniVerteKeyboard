package org.futo.voiceinput.shared.gemini

// OmniVerte: transcript cleanup for dictation. Port of desktop OmniVerte
// services/gemini_live.strip_hesitations and services/audio_writer.capitalize_first.
// Pure (JVM-testable). `\w` is spelled out as [\p{L}\p{N}_] so it is Unicode on
// both the JVM and Android without relying on UNICODE_CHARACTER_CLASS.

object TextCleanup {
    private const val W = "[\\p{L}\\p{N}_]"

    // Pure hesitation sounds only, never a word with a meaning ("ну", "типа",
    // "eh"/"este" in Spanish, "あの" in Japanese stay). Latin "mm" is left alone:
    // it is also millimetres.
    private const val HESITATION = "(?:э+(?:-э+)*|эм+|м{2,}|хм+" +   // ru
            "|uh+|um+|erm+|hmm+" +                                  // en
            "|äh+m*|öh+m*" +                                        // de
            "|euh+" +                                               // fr
            "|ehm+)"                                                // it

    private val hesitationRe = Regex(
        "(?<!$W|-)$HESITATION(?!$W|-)(?:\\s*(?:,|\\.\\.\\.|…))?\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val spaceBeforePunct = Regex("\\s+([,.!?…])")
    private val commaBeforeEnd = Regex(",\\s*([.!?…])")
    private val doubledCommas = Regex(",(\\s*,)+")
    private val leadingJunk = Regex("^[\\s,]+")
    private val multiSpace = Regex("\\s{2,}")
    private val capitalMark = Regex("\u0000\\s*($W)")

    /**
     * Drop hesitation sounds from a VERBATIM transcript:
     * "зафиксирую все три промпта, э-э, три новых стиля" → "зафиксирую все три
     * промпта, три новых стиля". A sentence that started with one gets its
     * capital back.
     */
    fun stripHesitations(text: String): String {
        if (text.isEmpty()) return text
        // A capitalized filler started its sentence: hand the capital on to the
        // word after it (marked with \u0000, resolved below).
        var out = hesitationRe.replace(text) { m -> if (m.value[0].isUpperCase()) "\u0000" else "" }
        out = spaceBeforePunct.replace(out, "$1")        // space left before punctuation
        out = commaBeforeEnd.replace(out, "$1")          // ", ." from a trailing filler
        out = doubledCommas.replace(out, ",")
        out = leadingJunk.replace(out, "")
        out = multiSpace.replace(out, " ").trim()
        out = capitalMark.replace(out) { m -> m.groupValues[1].uppercase() }
        return out.replace("\u0000", "").trim()
    }

    /** Opening punctuation a dictated text may start with, skipped to reach the first word. */
    private const val LEADING_PUNCT = "\"'«„“‘([{¿¡—–-…"

    /**
     * Start the text with a capital letter, as a sentence would. Gemini starts a
     * take in lower case whenever it reads it as a continuation. Only a plain
     * lower-case first word is touched: one that already carries a capital
     * (iPhone), starts with a digit (2G), or looks like a link, path or address
     * stays as transcribed. (FUTO's ModelOutputSanitizer lowercases it again when
     * the text is inserted mid-sentence.)
     */
    fun capitalizeFirst(text: String): String {
        var i = 0
        while (i < text.length && (text[i].isWhitespace() || text[i] in LEADING_PUNCT)) i++
        val rest = text.substring(i)
        val word = rest.trim().split(Regex("\\s+"), limit = 2).firstOrNull().orEmpty()
        if (word.isEmpty() || !word[0].isLetter() || !word[0].isLowerCase()
            || word.any { it.isUpperCase() }
            || listOf("://", "@", "/", "\\").any { it in word }
            || word.lowercase().startsWith("www.")
        ) return text
        return text.substring(0, i) + text[i].uppercase() + text.substring(i + 1)
    }

    /** The dictation chain used after a Gemini take. */
    fun forDictation(raw: String, cleanFillers: Boolean): String {
        val cleaned = if (cleanFillers) stripHesitations(raw) else raw.trim()
        return capitalizeFirst(cleaned)
    }
}
