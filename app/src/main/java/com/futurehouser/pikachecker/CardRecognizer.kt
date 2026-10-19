package com.futurehouser.pikachecker

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import java.text.Normalizer

/** A Pokémon TCG card read from one camera frame. Boxes are in PreviewView coordinates. */
data class CardDetection(
    val name: String,
    /** Collector number as printed, e.g. "025/165", "TG12/TG30", "SWSH050". */
    val printedNumber: String,
    /** Number as PriceCharting writes it after '#', e.g. "25", "TG12", "SWSH050". */
    val searchNumber: String,
    val nameBox: Rect,
    val numberBox: Rect,
) {
    val key: String get() = "${name.lowercase()}#$searchNumber"
}

object CardRecognizer {

    // 025/165, 4/102, TG12/TG30, GG05/GG70, SV045/SV094, 199/165
    private val numberRegex = Regex("""(?<![A-Z0-9])([A-Z]{0,3}\s?\d{1,3})\s?/\s?([A-Z]{0,3}\s?\d{2,3})(?![0-9])""")
    // Black star promos: SWSH050, SM210, XY124, BW95, SVP 085, "SVP EN 085"
    private val promoRegex = Regex("""(?<![A-Z0-9])(SWSH|SVP|SM|XY|BW|DP|HGSS)\s?(?:EN\s?)?(\d{2,3})(?![0-9])""")

    private val stopWords = setOf(
        "basic", "basico", "básico", "stage", "fase", "etapa", "evolves", "evoluciona", "pokemon", "pokémon",
        "trainer", "entrenador", "item", "objeto", "supporter", "partidario", "stadium", "estadio", "energy",
        "energia", "energía", "weakness", "debilidad", "resistance", "resistencia", "retreat", "retirada",
        "illus", "nintendo", "creatures", "gamefreak", "ability", "habilidad", "tera", "hp", "pv", "ps",
        "put", "pon", "this", "este", "esta", "your", "tu", "tus", "rule", "regla", "from", "de",
    )

    fun recognize(text: Text): CardDetection? {
        val lines = text.textBlocks.flatMap { it.lines }.filter { it.boundingBox != null }
        if (lines.isEmpty()) return null

        val number = findNumber(lines) ?: return null
        val (printed, search, numberBox) = number

        val name = findName(lines, numberBox) ?: return null
        return CardDetection(name.first, printed, search, name.second, numberBox)
    }

    private fun findNumber(lines: List<Text.Line>): Triple<String, String, Rect>? {
        for (line in lines.sortedByDescending { it.boundingBox!!.bottom }) {
            val (printed, search) = parseNumber(line.text) ?: continue
            return Triple(printed, search, line.boundingBox!!)
        }
        return null
    }

    /** Returns (printed number, PriceCharting number) found in an OCR line, e.g. "025/165" -> ("025/165", "25"). */
    fun parseNumber(text: String): Pair<String, String>? {
        val raw = text.uppercase().replace('O', '0').replace('\\', '/').replace('|', '/')
        numberRegex.findAll(raw).forEach { m ->
            val left = m.groupValues[1].replace(" ", "")
            val right = m.groupValues[2].replace(" ", "")
            val leftDigits = left.filter(Char::isDigit)
            val rightDigits = right.filter(Char::isDigit)
            val prefix = left.takeWhile { it.isLetter() }
            // Plain numbers: card number should be plausible vs. set size (secret rares can exceed it).
            if (prefix.isEmpty() && leftDigits.toInt() > rightDigits.toInt() * 2 + 30) return@forEach
            val search = if (prefix.isEmpty()) leftDigits.toInt().toString() else prefix + leftDigits
            return "$left/$right" to search
        }
        promoRegex.find(raw)?.let { m ->
            val code = m.groupValues[1] + m.groupValues[2]
            return code to code
        }
        return null
    }

    /**
     * The card name is the largest-font text near the top of the card, above the number.
     * Score candidate lines by text height, favouring lines higher up.
     */
    private fun findName(lines: List<Text.Line>, numberBox: Rect): Pair<String, Rect>? {
        val candidates = lines.mapNotNull { line ->
            val box = line.boundingBox!!
            if (box.bottom >= numberBox.top) return@mapNotNull null
            val cleaned = cleanName(line.text) ?: return@mapNotNull null
            cleaned to box
        }
        if (candidates.isEmpty()) return null
        val top = candidates.minOf { it.second.top }
        val bottom = numberBox.top
        val span = (bottom - top).coerceAtLeast(1)
        return candidates.maxByOrNull { (_, box) ->
            val upperness = 1.0 - (box.top - top).toDouble() / span // 1 at the top, 0 near the number
            box.height() * (0.6 + upperness)
        }
    }

    private val hpSuffix = Regex("""\s*(HP|PV|PS|KP)\s*\d+.*$""", RegexOption.IGNORE_CASE)
    private val trailingDigits = Regex("""\s*\d+\s*$""")
    private val allowed = Regex("""[^\p{L}\s'.\-♀♂]""")

    fun cleanName(raw: String): String? {
        var s = raw.trim()
        s = hpSuffix.replace(s, "")
        s = trailingDigits.replace(s, "")
        s = allowed.replace(s, " ").replace(Regex("\\s+"), " ").trim()
        val words = s.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 4) return null
        val letters = s.count { it.isLetter() }
        if (letters < 3) return null
        val lowered = words.map { strip(it.lowercase()) }
        if (lowered.all { it in stopWords }) return null
        if (lowered.first() in stopWords) return null
        // Names start with a capital letter (Pokémon or trainer names).
        if (!s.first().isUpperCase()) return null
        return s
    }

    private fun strip(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
}
