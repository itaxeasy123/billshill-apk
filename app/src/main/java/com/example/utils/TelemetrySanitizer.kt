package com.example.utils

import java.nio.charset.StandardCharsets

object TelemetrySanitizer {

    // Regex pattern matching HTML/XML tag syntax tokens and script/code injection sequences
    private val TAG_SYNTAX_REGEX = Regex("<[^>]*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_TOKEN_REGEX = Regex("(?i)script|javascript:|data:|vbscript:", RegexOption.IGNORE_CASE)

    /**
     * Sanitizes plain text application data streams by stripping structural tag syntax tokens
     * and potential code execution/HTML injection sequences.
     */
    fun sanitizePayload(rawText: String): String {
        if (rawText.isBlank()) return ""

        // 1. Strip structural HTML/XML tag syntax tokens
        var sanitized = TAG_SYNTAX_REGEX.replace(rawText, "")

        // 2. Neutralize dangerous script scheme tokens
        sanitized = SCRIPT_TOKEN_REGEX.replace(sanitized) { match ->
            "[neutralized_${match.value}]"
        }

        return sanitized.trim()
    }

    /**
     * Enforces a strict payload threshold restriction (max 10 KB / 10,240 bytes)
     * to prevent payload injection attacks.
     *
     * @param text Input text string
     * @param maxBytes Maximum allowed size in bytes (default 10 KB = 10,240 bytes)
     * @return Truncated text safely bounded within the specified byte limit
     */
    fun enforceMaxPayloadSize(text: String, maxBytes: Int = 10240): String {
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size <= maxBytes) return text

        // Safely truncate string to fit within maxBytes threshold
        var substring = text
        while (substring.toByteArray(StandardCharsets.UTF_8).size > maxBytes && substring.isNotEmpty()) {
            substring = substring.substring(0, substring.length - (bytes.size - maxBytes).coerceAtLeast(10))
        }
        return "$substring\n[TRUNCATED: Exceeded 10KB payload limit]"
    }
}
