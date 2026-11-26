package studio.cluvex.aether.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangelogFetcherTest {

    private val sample = """
# Changelog

## 1.7.0 - 2026-10-30

### Added
- **Thing.** Details.

### Files
- app/src/main/java/A.kt
- `app/src/main/res/values/strings.xml`

---

## 1.6.0 - 2026-10-29

### Changed
- Old.

---

## ۱٫۷٫۰ - ۱۴۰۵/۰۸/۰۸

### اضافه شد
- چیزی.

### فایل‌ها
- app/src/main/java/A.kt
""".trimIndent()

    @Test fun englishBlocksOnly() {
        val e = ChangelogFetcher.parse(sample, persian = false)
        assertEquals(listOf("1.7.0", "1.6.0"), e.map { it.version })
        assertEquals(
            listOf("app/src/main/java/A.kt", "app/src/main/res/values/strings.xml"),
            e[0].files,
        )
        assertFalse(e[0].notes.contains("A.kt"))
        assertTrue(e[0].notes.contains("Thing"))
    }

    @Test fun persianBlocksNormalizeVersion() {
        val e = ChangelogFetcher.parse(sample, persian = true)
        assertEquals(listOf("1.7.0"), e.map { it.version })
        assertEquals(listOf("app/src/main/java/A.kt"), e[0].files)
    }

    @Test fun newerThanFiltersCurrent() {
        val e = ChangelogFetcher.parse(sample, persian = false)
        assertEquals(listOf("1.7.0"), ChangelogFetcher.newerThan(e, "1.6.0").map { it.version })
        assertTrue(ChangelogFetcher.newerThan(e, "1.7.0").isEmpty())
    }
}
