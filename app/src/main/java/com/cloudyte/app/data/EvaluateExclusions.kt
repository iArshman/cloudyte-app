package com.cloudyte.app.data

import com.cloudyte.app.db.LocalExclusion

object EvaluateExclusions {

    fun isExcluded(filePathOrName: String, exclusions: List<LocalExclusion>): Boolean {
        val activeRules = exclusions.filter { it.enabled }
        if (activeRules.isEmpty()) return false

        val normalized = filePathOrName.replace("\\", "/")
        val fileName = normalized.substringAfterLast('/')

        for (exclusion in activeRules) {
            val pattern = exclusion.pattern.trim()
            if (pattern.isEmpty()) continue

            if (exclusion.isRegex) {
                try {
                    val regex = Regex(pattern, RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(normalized) || regex.containsMatchIn(fileName)) {
                        return true
                    }
                } catch (_: Exception) {
                    // Invalid regex rule ignored
                }
            } else {
                val globRegex = globToRegex(pattern)
                try {
                    val regex = Regex(globRegex, RegexOption.IGNORE_CASE)
                    if (regex.matches(fileName) || regex.matches(normalized) || regex.containsMatchIn(normalized)) {
                        return true
                    }
                } catch (_: Exception) {
                    // Fallback substring check
                    if (normalized.contains(pattern, ignoreCase = true) || fileName.contains(pattern, ignoreCase = true)) {
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun globToRegex(glob: String): String {
        val sb = StringBuilder("^")
        var i = 0
        val len = glob.length
        while (i < len) {
            val c = glob[i]
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.' -> sb.append("\\.")
                '\\', '(', ')', '+', '|', '^', '$', '@', '%', '{', '}', '[', ']' -> sb.append('\\').append(c)
                else -> sb.append(c)
            }
            i++
        }
        sb.append("$")
        return sb.toString()
    }
}
