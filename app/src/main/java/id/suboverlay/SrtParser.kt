package id.suboverlay

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

object SrtParser {
    private val timestamp = Regex(
        """(\d{2}):(\d{2}):(\d{2})[,.](\d{3})\s*-->\s*(\d{2}):(\d{2}):(\d{2})[,.](\d{3})"""
    )

    fun parse(input: String): List<SubtitleCue> {
        val normalized = input.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
        val cues = mutableListOf<SubtitleCue>()
        normalized.split(Regex("""\n\s*\n""")).forEach { block ->
            val lines = block.lines()
            val timeIndex = lines.indexOfFirst { timestamp.containsMatchIn(it) }
            if (timeIndex < 0) return@forEach
            val match = timestamp.find(lines[timeIndex]) ?: return@forEach
            val values = (1..8).map { match.groupValues[it].toLong() }
            fun toMs(i: Int): Long = values[i] * 3_600_000L +
                values[i + 1] * 60_000L + values[i + 2] * 1_000L + values[i + 3]
            val text = lines.drop(timeIndex + 1).joinToString("\n")
                .replace(Regex("<[^>]*>"), "").trim()
            if (text.isNotEmpty()) cues += SubtitleCue(toMs(0), toMs(4), text)
        }
        return cues.sortedBy { it.startMs }
    }

    fun at(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? =
        cues.lastOrNull { positionMs >= it.startMs && positionMs <= it.endMs }
}
