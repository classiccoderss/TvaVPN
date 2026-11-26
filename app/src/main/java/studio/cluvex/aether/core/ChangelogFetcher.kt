package studio.cluvex.aether.core

import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the project's CHANGELOG.md from GitHub and splits it per version.
 *
 * The page people can open in a browser is [CHANGELOG_PAGE_URL]; the app reads
 * the same file's raw text ([CHANGELOG_RAW_URL]) so it can show the changes
 * in-app while an update is offered, and the list of changed files in About.
 *
 * ## File format this parser expects
 *
 * Each version is a `## <version> - <date>` block, written twice: an English
 * block (ASCII digits) and a Persian block (Persian digits). Inside a block,
 * a `### Files` (Persian: `### فایل‌ها`) section lists one changed path per
 * bullet:
 *
 *     ### Files
 *     - app/src/main/java/studio/cluvex/aether/ui/AboutPanel.kt
 *     - app/src/main/res/values/strings.xml
 *
 * That section is what About > Changelog shows (paths only). Everything else in
 * the block is the human-readable release notes shown on the update page.
 */
object ChangelogFetcher {

    const val CHANGELOG_PAGE_URL = "https://github.com/tohidbareza/TvaVPN/blob/main/CHANGELOG.md"
    private const val CHANGELOG_RAW_URL = "https://raw.githubusercontent.com/tohidbareza/TvaVPN/main/CHANGELOG.md"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_BYTES = 512 * 1024

    data class Entry(
        /** Normalised to ASCII digits, e.g. "1.7.0". */
        val version: String,
        /** The heading as written, e.g. "1.7.0 - 2026-10-30". */
        val heading: String,
        /** Release notes with the Files section removed. */
        val notes: String,
        /** Changed file paths, exactly as listed. */
        val files: List<String>,
    )

    /** Blocking; call off the main thread. Null when the file cannot be fetched. */
    fun fetch(persian: Boolean): List<Entry>? = runCatching {
        val conn = (URL(CHANGELOG_RAW_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "tvaVPN")
        }
        try {
            if (conn.responseCode != 200) return@runCatching null
            val text = conn.inputStream.use { it.readNBytesCapped(MAX_BYTES) }
            parse(text, persian)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCapped(max: Int): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8 * 1024)
        while (out.size() < max) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    private val PERSIAN_DIGIT = Regex("[۰-۹]")
    private val FILES_HEADER = Regex("^###\\s*(files|changed files|فایل.*)\\s*$", RegexOption.IGNORE_CASE)

    /** Pure; no I/O. Keeps only the blocks written in the requested language. */
    fun parse(text: String, persian: Boolean): List<Entry> {
        val blocks = mutableListOf<Pair<String, MutableList<String>>>()
        for (line in text.lines()) {
            if (line.startsWith("## ")) {
                blocks += line.removePrefix("## ").trim() to mutableListOf()
            } else if (blocks.isNotEmpty()) {
                blocks.last().second += line
            }
        }
        return blocks
            .filter { (heading, _) -> PERSIAN_DIGIT.containsMatchIn(heading) == persian }
            .map { (heading, body) ->
                val notes = StringBuilder()
                val files = mutableListOf<String>()
                var inFiles = false
                for (raw in body) {
                    val line = raw.trimEnd()
                    if (line.startsWith("###")) inFiles = FILES_HEADER.matches(line.trim())
                    if (line.trim() == "---") continue
                    if (inFiles) {
                        if (line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ")) {
                            val path = line.trimStart().drop(2).trim().trim('`').trim()
                            if (path.isNotEmpty()) files += path
                        }
                    } else {
                        notes.appendLine(line)
                    }
                }
                Entry(
                    version = normalizeVersion(heading.substringBefore(" ")),
                    heading = heading,
                    notes = notes.toString().trim(),
                    files = files,
                )
            }
    }

    fun normalizeVersion(raw: String): String {
        val sb = StringBuilder()
        for (c in raw.trim().removePrefix("v").removePrefix("V")) {
            sb.append(
                when (c) {
                    in '۰'..'۹' -> '0' + (c - '۰')
                    in '٠'..'٩' -> '0' + (c - '٠')
                    '٫' -> '.'
                    else -> c
                },
            )
        }
        return sb.toString()
    }

    /** True when [a] is a strictly newer version than [b] ("1.7.0" > "1.6.0"). */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = normalizeVersion(v).split(".", "-", "+").mapNotNull { it.toIntOrNull() }
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Entries strictly newer than [current], newest first. */
    fun newerThan(entries: List<Entry>, current: String): List<Entry> =
        entries.filter { isNewer(it.version, current) }

    /** Strips Markdown emphasis/code marks so the notes read as plain text. */
    fun plain(md: String): String =
        md.replace("**", "").replace("`", "").replace(Regex("(?m)^###\\s*"), "").trim()
}
