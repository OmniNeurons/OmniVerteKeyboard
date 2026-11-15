package org.futo.voiceinput.shared.gemini

// Cases ported 1:1 from desktop OmniVerte tests (test_gemini_live.py
// test_strip_hesitations, test_audio_writer_helpers.py test_capitalize_first).

import org.junit.Assert.assertEquals
import org.junit.Test

class TextCleanupTest {
    @Test
    fun stripHesitations() {
        val cases = listOf(
            "зафиксирую все три промпта, э-э, три новых стиля" to "зафиксирую все три промпта, три новых стиля",
            "Э-э, моя учётка только на чтение." to "Моя учётка только на чтение.",
            "подавали её под особенным, э, условием." to "подавали её под особенным, условием.",
            "Готово, эм." to "Готово.",
            "Ну вот, типа так, это как бы работает." to "Ну вот, типа так, это как бы работает.",
            "Эмма и Элла, эстетика, мама." to "Эмма и Элла, эстетика, мама.",
            "so, um, it works" to "so, it works",
            "Äh, das ist ähm gut." to "Das ist gut.",
            "c'est, euh, prêt" to "c'est, prêt",
            "è, ehm, pronto" to "è, pronto",
            "tornillo de 5 mm" to "tornillo de 5 mm",
            "" to "",
        )
        for ((raw, clean) in cases) assertEquals(raw, clean, TextCleanup.stripHesitations(raw))
    }

    @Test
    fun capitalizeFirst() {
        val cases = listOf(
            "после сохранения продолжай" to "После сохранения продолжай",
            "  «давай» так" to "  «Давай» так",
            "Уже с заглавной." to "Уже с заглавной.",
            "iPhone не трогаем" to "iPhone не трогаем",
            "2G включи" to "2G включи",
            "https://example.com открой" to "https://example.com открой",
            "www.example.com" to "www.example.com",
            "user@mail.ru напиши" to "user@mail.ru напиши",
            "ёлка" to "Ёлка",
            "" to "",
            "  " to "  ",
        )
        for ((raw, out) in cases) assertEquals(raw, out, TextCleanup.capitalizeFirst(raw))
    }

    @Test
    fun dictationChain() {
        assertEquals("Три новых стиля", TextCleanup.forDictation("э-э, три новых стиля", cleanFillers = true))
        assertEquals("Э-э, три новых стиля", TextCleanup.forDictation("э-э, три новых стиля", cleanFillers = false))
    }
}
