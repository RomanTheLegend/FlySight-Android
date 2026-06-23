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

    fun parseAlLines(text: String): MutableList<AlLine> {
        val result  = mutableListOf<AlLine>()
        var current: AlLine? = null
        for (line in text.lines()) {
            val stripped = line.substringBefore(';').trim()
            if (stripped.isEmpty()) continue
            val colon = stripped.indexOf(':')
            if (colon < 0) continue
            val key   = stripped.substring(0, colon).trim()
            val value = stripped.substring(colon + 1).trim()
            when (key) {
                "AL_Line"  -> { current = AlLine(mode = value.toIntOrNull() ?: 0); result.add(current) }
                "AL_Units" -> current?.units = value.toIntOrNull() ?: 1
                "AL_Dec"   -> current?.dec   = value.toIntOrNull() ?: 1
            }
        }
        return if (result.isEmpty()) mutableListOf(AlLine()) else result
    }
}
