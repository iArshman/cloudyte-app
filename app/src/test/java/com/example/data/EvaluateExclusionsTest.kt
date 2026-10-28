package com.example.data

import com.example.db.LocalExclusion
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EvaluateExclusionsTest {

    @Test
    fun testGlobMatching() {
        val rules = listOf(
            LocalExclusion(id = 1, pattern = "*.tmp", isRegex = false, enabled = true),
            LocalExclusion(id = 2, pattern = ".DS_Store", isRegex = false, enabled = true),
            LocalExclusion(id = 3, pattern = "cache/*", isRegex = false, enabled = true)
        )

        assertTrue(EvaluateExclusions.isExcluded("temp_file.tmp", rules))
        assertTrue(EvaluateExclusions.isExcluded("/folder/path/.DS_Store", rules))
        assertTrue(EvaluateExclusions.isExcluded("cache/data.bin", rules))

        assertFalse(EvaluateExclusions.isExcluded("document.pdf", rules))
        assertFalse(EvaluateExclusions.isExcluded("image.png", rules))
    }

    @Test
    fun testRegexMatching() {
        val rules = listOf(
            LocalExclusion(id = 1, pattern = ".*_backup_\\d+\\.zip", isRegex = true, enabled = true)
        )

        assertTrue(EvaluateExclusions.isExcluded("my_backup_2026.zip", rules))
        assertFalse(EvaluateExclusions.isExcluded("my_backup_abc.zip", rules))
    }

    @Test
    fun testDisabledRuleIgnored() {
        val rules = listOf(
            LocalExclusion(id = 1, pattern = "*.tmp", isRegex = false, enabled = false)
        )

        assertFalse(EvaluateExclusions.isExcluded("temp_file.tmp", rules))
    }
}
