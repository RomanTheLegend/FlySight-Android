package com.flysight.app.config

object ConfigParser {
    fun parse(text: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (line in text.lines()) {
            val stripped = line.substringBefore(';').trim()
            if (stripped.isEmpty()) continue
            val colon = stripped.indexOf(':')
            if (colon < 0) continue
            val key   = stripped.substring(0, colon).trim()
            val value = stripped.substring(colon + 1).trim()
            if (key.isNotEmpty()) map.putIfAbsent(key, value)
        }
        return map
    }
}
