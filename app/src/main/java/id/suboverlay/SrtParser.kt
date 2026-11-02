package id.suboverlay

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

object SrtParser {
    // Accepts SRT and WebVTT timestamps, with optional hours and 1-3 millisecond digits.
    private val timestamp = Regex(
        """(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(?:(\d{1,2}):)?(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val tags = Regex("""<[^>]*>""")

    fun parse(input: String): List<SubtitleCue> {
        val normalized = input.removePrefix("\uFEFF")
            .replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val cues = mutableListOf<SubtitleCue>()
        var i = 0

        while (i < lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty() || line.startsWith("WEBVTT", true) ||
                line.startsWith("NOTE", true) || line.startsWith("STYLE", true) ||
                line.startsWith("REGION", true)) {
                i++
                continue
            }

            // A cue identifier may appear immediately before the timing line.
            val timingIndex = when {
                timestamp.containsMatchIn(line) -> i
                i + 1 < lines.size && timestamp.containsMatchIn(lines[i + 1]) -> i + 1
                else -> -1
            }
            if (timingIndex < 0) {
                i++
                continue
            }

            val match = timestamp.find(lines[timingIndex])
            if (match == null) {
                i = timingIndex + 1
                continue
            }
            val g = match.groupValues
            fun toMs(hour: String, minute: String, second: String, milli: String): Long {
                val h = hour.takeIf { it.isNotEmpty() }?.toLongOrNull() ?: 0L
                val m = minute.toLong()
                val s = second.toLong()
                val ms = milli.padEnd(3, '0').take(3).toLong()
                return h * 3_600_000L + m * 60_000L + s * 1_000L + ms
            }
            val start = toMs(g[1], g[2], g[3], g[4])
            val end = toMs(g[5], g[6], g[7], g[8])

            val textLines = mutableListOf<String>()
            i = timingIndex + 1
            while (i < lines.size && lines[i].isNotBlank()) {
                textLines += lines[i].trim()
                i++
            }
            val text = textLines.joinToString("\n")
                .replace(tags, "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim()
            if (text.isNotEmpty() && end >= start) cues += SubtitleCue(start, end, text)
            i++
        }
        return cues.sortedBy { it.startMs }
    }

    fun at(cues: List<SubtitleCue>, positionMs: Long): SubtitleCue? =
        cues.lastOrNull { positionMs >= it.startMs && positionMs <= it.endMs }
}
