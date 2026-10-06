package com.srooyesh.seedcounter

import android.graphics.Rect
import com.google.mlkit.vision.text.Text
import kotlin.math.max
import kotlin.math.min

/**
 * Extracts packet-number candidates without assuming a fixed grouping width.
 * Examples accepted by design: 123456, 12 345 67, 1 2345, 12-34-5, 12/345/67.
 */
object NumberCandidateExtractor {
    data class Candidate(
        /** Canonical digits-only value used for validation, stability and storage. */
        val value: String,
        val score: Float,
        val source: String,
        val groups: Int = 1,
        /** Human-visible representation. SMART mode may preserve recognized separators. */
        val displayValue: String = value
    )

    private val digitRunRegex = Regex("\\d+")
    private val joinerRegex = Regex("[\\s\\-_/.:·•,;|]+")
    private val hardLetterRegex = Regex("[A-Za-z\\u0600-\\u06FF]")

    fun fromLine(line: Text.Line, minDigits: Int, maxDigits: Int): List<Candidate> {
        val text = normalizeCharacters(line.text)
        val out = mutableListOf<Candidate>()

        digitRunRegex.findAll(text).forEach { match ->
            val value = match.value
            if (value.length in minDigits..maxDigits) {
                out += Candidate(value, score(value, line, grouped = false, groups = 1), "compact", 1)
            }
        }

        groupedCandidates(text, minDigits, maxDigits).forEach { grouped ->
            out += Candidate(
                value = grouped.value,
                score = score(grouped.value, line, grouped = true, groups = grouped.groups),
                source = "grouped",
                groups = grouped.groups,
                displayValue = grouped.displayValue
            )
        }

        out += elementCandidates(line, minDigits, maxDigits)
        return out
            .filter { it.value.length in minDigits..maxDigits }
            .sortedByDescending { it.score }
            .distinctBy { it.value }
    }

    /** Pure helper for regression tests and future recognizers. */
    fun fromText(text: String, minDigits: Int = 2, maxDigits: Int = 16): List<Candidate> {
        val normalized = normalizeCharacters(text)
        val out = mutableListOf<Candidate>()
        digitRunRegex.findAll(normalized).forEach { m ->
            if (m.value.length in minDigits..maxDigits) {
                out += Candidate(m.value, 0.70f + m.value.length * 0.02f, "compact", 1)
            }
        }
        groupedCandidates(normalized, minDigits, maxDigits).forEach { grouped ->
            out += Candidate(
                value = grouped.value,
                score = 0.86f + grouped.value.length.coerceAtMost(12) / 40f,
                source = "grouped",
                groups = grouped.groups,
                displayValue = grouped.displayValue
            )
        }
        return out.sortedByDescending { it.score }.distinctBy { it.value }
    }

    private data class GroupedCandidate(
        val value: String,
        val groups: Int,
        val displayValue: String
    )

    private fun groupedCandidates(text: String, minDigits: Int, maxDigits: Int): List<GroupedCandidate> {
        val runs = digitRunRegex.findAll(text).toList()
        if (runs.size < 2) return emptyList()

        val out = mutableListOf<GroupedCandidate>()
        for (start in runs.indices) {
            var combined = ""
            var groups = 0
            var last = runs[start]
            for (index in start until runs.size) {
                val run = runs[index]
                if (index > start) {
                    val between = text.substring(last.range.last + 1, run.range.first)
                    val canJoin = between.isNotEmpty() &&
                        joinerRegex.matches(between) &&
                        !hardLetterRegex.containsMatchIn(between)
                    if (!canJoin) break
                }
                combined += run.value
                groups++
                if (combined.length > maxDigits) break
                if (combined.length >= minDigits) {
                    val segment = text.substring(runs[start].range.first, run.range.last + 1)
                    out += GroupedCandidate(
                        value = combined,
                        groups = groups,
                        displayValue = smartDisplay(segment)
                    )
                }
                last = run
            }
        }
        return out.distinctBy { it.value to it.displayValue }
    }

    private fun smartDisplay(segment: String): String {
        val cleaned = segment
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s*([,;:/._+*#|\\-])\\s*"), "$1")
            .trim()
        return cleaned.filter { it.isDigit() || it.isWhitespace() || it in ",;:/._+*#|-" }.trim()
    }

    private fun elementCandidates(line: Text.Line, minDigits: Int, maxDigits: Int): List<Candidate> {
        data class Token(val digits: String, val box: Rect)
        val tokens = line.elements.mapNotNull { element ->
            val digits = normalizeCharacters(element.text).filter(Char::isDigit)
            val box = element.boundingBox ?: line.boundingBox ?: return@mapNotNull null
            if (digits.isBlank()) null else Token(digits, box)
        }.sortedBy { it.box.left }

        if (tokens.isEmpty()) return emptyList()

        val out = mutableListOf<Candidate>()
        var current = ""
        var groups = 0
        var previous: Token? = null

        for (token in tokens) {
            val prev = previous
            val verticalOverlap = prev != null && overlapRatio(prev.box.top, prev.box.bottom, token.box.top, token.box.bottom) >= 0.35f
            val gap = if (prev == null) 0 else token.box.left - prev.box.right
            val previousCharWidth = if (prev == null) token.box.width().toFloat() / max(1, token.digits.length) else prev.box.width().toFloat() / max(1, prev.digits.length)
            val currentCharWidth = token.box.width().toFloat() / max(1, token.digits.length)
            val maxGap = max(28f, max(previousCharWidth, currentCharWidth) * 4.5f)
            val near = prev == null || (verticalOverlap && gap <= maxGap)

            if (!near) {
                current = ""
                groups = 0
            }
            current += token.digits
            groups++

            if (current.length in minDigits..maxDigits) {
                val gapPenalty = if (prev == null) 0f else (gap.coerceAtLeast(0) / maxGap).coerceIn(0f, 1f) * 0.12f
                out += Candidate(
                    value = current,
                    score = 0.78f + current.length.coerceAtMost(12) / 45f + (if (groups > 1) 0.10f else 0f) - gapPenalty,
                    source = "elements",
                    groups = groups
                )
            }
            if (current.length >= maxDigits) {
                current = current.takeLast(token.digits.length.coerceAtMost(maxDigits))
                groups = 1
            }
            previous = token
        }
        return out.distinctBy { it.value }
    }

    private fun score(value: String, line: Text.Line, grouped: Boolean, groups: Int): Float {
        val box = line.boundingBox
        val areaBoost = if (box == null) 0f else (box.width() / 1000f).coerceIn(0f, 0.65f)
        val lengthBoost = (value.length.coerceAtMost(16) / 30f)
        val groupedBoost = if (grouped) 0.18f else 0f
        val multiGroupBoost = if (groups >= 2) 0.08f else 0f
        return 0.56f + lengthBoost + groupedBoost + multiGroupBoost + areaBoost
    }

    private fun overlapRatio(top1: Int, bottom1: Int, top2: Int, bottom2: Int): Float {
        val overlap = max(0, min(bottom1, bottom2) - max(top1, top2))
        val height = max(1, min(bottom1 - top1, bottom2 - top2))
        return overlap.toFloat() / height.toFloat()
    }

    fun normalizeCharacters(text: String): String {
        val fa = "۰۱۲۳۴۵۶۷۸۹"
        val ar = "٠١٢٣٤٥٦٧٨٩"
        return buildString(text.length) {
            text.forEach { c ->
                val fi = fa.indexOf(c)
                val ai = ar.indexOf(c)
                when {
                    fi >= 0 -> append(('0'.code + fi).toChar())
                    ai >= 0 -> append(('0'.code + ai).toChar())
                    c == '—' || c == '–' || c == '−' -> append('-')
                    else -> append(c)
                }
            }
        }
    }
}
