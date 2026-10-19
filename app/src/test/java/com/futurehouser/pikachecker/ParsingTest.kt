package com.futurehouser.pikachecker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParsingTest {

    @Test
    fun collectorNumbers() {
        assertEquals("025/165" to "25", CardRecognizer.parseNumber("025/165"))
        assertEquals("199/165" to "199", CardRecognizer.parseNumber("MEW EN 199/165 ★"))
        assertEquals("4/102" to "4", CardRecognizer.parseNumber("4/102"))
        assertEquals("TG12/TG30" to "TG12", CardRecognizer.parseNumber("TG12/TG30"))
        assertEquals("GG05/GG70" to "GG05", CardRecognizer.parseNumber("GG05 / GG70"))
        assertEquals("025/165" to "25", CardRecognizer.parseNumber("O25/165")) // OCR reads 0 as O
        assertEquals("SWSH050" to "SWSH050", CardRecognizer.parseNumber("SWSH050"))
        assertNull(CardRecognizer.parseNumber("Pikachu HP 60"))
        assertNull(CardRecognizer.parseNumber("Deal 30 damage"))
    }

    @Test
    fun cardNames() {
        assertEquals("Pikachu", CardRecognizer.cleanName("Pikachu HP60"))
        assertEquals("Charizard ex", CardRecognizer.cleanName("Charizard ex PV 330"))
        assertEquals("Mew", CardRecognizer.cleanName("Mew 70"))
        assertNull(CardRecognizer.cleanName("BASIC"))
        assertNull(CardRecognizer.cleanName("Weakness"))
        assertNull(CardRecognizer.cleanName("x2"))
        assertNull(CardRecognizer.cleanName("this Pokémon does 30 damage to each of your opponent's"))
    }

    @Test
    fun prices() {
        assertEquals(1234.56, PriceCharting.parseUsd("$1,234.56")!!, 0.001)
        assertEquals(12.0, PriceCharting.parseUsd(" $12.00 ")!!, 0.001)
        assertNull(PriceCharting.parseUsd("-"))
        assertEquals(
            "https://www.pricecharting.com/es/game/pokemon-151/pikachu-25",
            PriceCharting.toSpanish("https://www.pricecharting.com/game/pokemon-151/pikachu-25"),
        )
    }

    @Test
    fun userAgentLooksLikeChrome() {
        val webView = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Build/UQ1A.240205.004; wv) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Version/4.0 Chrome/129.0.6668.100 Mobile Safari/537.36"
        assertEquals(
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/129.0.6668.100 Mobile Safari/537.36",
            PriceCharting.browserUserAgent(webView),
        )
    }

    @Test
    fun versions() {
        assertTrue(Updater.isNewer("1.0.1", "1.0.0"))
        assertTrue(Updater.isNewer("1.10.0", "1.9.9"))
        assertFalse(Updater.isNewer("1.0.0", "1.0.0"))
        assertFalse(Updater.isNewer("0.9.0", "1.0.0"))
    }
}
