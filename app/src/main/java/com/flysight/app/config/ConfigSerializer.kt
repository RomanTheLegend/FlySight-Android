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
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: ${if (item.enabled) "1" else "0"}\n")
                    knownKeys.add(item.key)
                }
                is SettingItem.Choice -> {
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: ${item.values[item.selectedIndex]}\n")
                    knownKeys.add(item.key)
                }
                is SettingItem.NumberInput -> {
                    if (item.value.isNotBlank()) {
                        item.hint?.let { sb.append("; $it\n") }
                        sb.append("${item.key}: ${item.value}\n")
                        knownKeys.add(item.key)
                    }
                }
                is SettingItem.Slider -> {
                    item.hint?.let { sb.append("; $it\n") }
                    sb.append("${item.key}: ${item.value}\n")
                    knownKeys.add(item.key)
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
