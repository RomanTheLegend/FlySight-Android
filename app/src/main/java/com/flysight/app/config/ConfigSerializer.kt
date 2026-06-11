package com.flysight.app.config

object ConfigSerializer {

    fun collectValues(items: List<SettingItem>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (item in items) {
            when (item) {
                is SettingItem.Toggle      -> map[item.key] = if (item.enabled) "1" else "0"
                is SettingItem.Choice      -> map[item.key] = item.values[item.selectedIndex]
                is SettingItem.NumberInput -> if (item.value.isNotBlank()) map[item.key] = item.value
                else                       -> Unit
            }
        }
        return map
    }

    fun serialize(original: String, updates: Map<String, String>): String {
        var result = original
        for ((key, value) in updates) {
            val regex = Regex(
                "^(${Regex.escape(key)}\\s*:\\s*)\\S+",
                RegexOption.MULTILINE
            )
            result = regex.replace(result) { mr -> mr.groupValues[1] + value }
        }
        return result
    }
}
