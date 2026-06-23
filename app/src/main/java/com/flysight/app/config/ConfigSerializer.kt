package com.flysight.app.config

object ConfigSerializer {

    fun serialize(items: List<SettingItem>, original: String): String {
        val knownKeys = mutableSetOf<String>()
        val sb = StringBuilder()
        var firstSection = true

        for (item in items) {
            when (item) {
                is SettingItem.Section -> {
                    if (!firstSection) sb.append('\n')
                    firstSection = false
                    sb.append("; ==== ${item.title} ====\n")
                }
                is SettingItem.Toggle -> {
                    val v = if (item.enabled) "1" else "0"
                    knownKeys.add(item.key)
                    if (item.isAdvanced && v == item.advancedDefault) continue
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: $v\n")
                }
                is SettingItem.Choice -> {
                    val v = item.values[item.selectedIndex]
                    knownKeys.add(item.key)
                    if (item.isAdvanced && v == item.advancedDefault) continue
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: $v\n")
                }
                is SettingItem.NumberInput -> {
                    knownKeys.add(item.key)
                    if (item.value.isBlank()) continue
                    if (item.isAdvanced && item.value == item.advancedDefault) continue
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: ${item.value}\n")
                }
                is SettingItem.Slider -> {
                    val v = item.value.toString()
                    knownKeys.add(item.key)
                    if (item.isAdvanced && v == item.advancedDefault) continue
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: $v\n")
                }
                is SettingItem.CoordPicker -> {
                    item.hint?.let { sb.append("; $it\n") }
                    if (item.latRaw.isNotBlank()) {
                        sb.append("${item.latKey}: ${item.latRaw}\n")
                        knownKeys.add(item.latKey)
                    }
                    if (item.lonRaw.isNotBlank()) {
                        sb.append("${item.lonKey}: ${item.lonRaw}\n")
                        knownKeys.add(item.lonKey)
                    }
                }
                is SettingItem.AlLineList -> {
                    knownKeys.add("AL_Line")
                    knownKeys.add("AL_Units")
                    knownKeys.add("AL_Dec")
                    for (alLine in item.lines) {
                        sb.append("AL_Line: ${alLine.mode}\n")
                        sb.append("AL_Units: ${alLine.units}\n")
                        sb.append("AL_Dec: ${alLine.dec}\n")
                    }
                }
            }
        }

        // Carry over any keys from the original file not represented in the items list
        val unknown = ConfigParser.parse(original).entries.filter { it.key !in knownKeys }
        if (unknown.isNotEmpty()) {
            sb.append('\n')
            sb.append("; ==== Other ====\n")
            for ((k, v) in unknown) sb.append("$k: $v\n")
        }

        return sb.toString()
    }
}
