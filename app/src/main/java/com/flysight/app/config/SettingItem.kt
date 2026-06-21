package com.flysight.app.config

sealed class SettingItem {
    companion object {
        const val VIEW_SECTION = 0
        const val VIEW_TOGGLE  = 1
        const val VIEW_CHOICE  = 2
        const val VIEW_NUMBER  = 3
        const val VIEW_COORD   = 4
        const val VIEW_SLIDER  = 5
    }
    abstract val viewType: Int

    class Section(val title: String) : SettingItem() {
        override val viewType = VIEW_SECTION
        var isExpanded: Boolean = false
    }

    data class Toggle(
        val key: String,
        val label: String,
        var enabled: Boolean,
        val hint: String? = null,
        val isAdvanced: Boolean = false,
        val advancedDefault: String? = null
    ) : SettingItem() {
        override val viewType = VIEW_TOGGLE
    }

    data class Choice(
        val key: String,
        val label: String,
        val options: List<String>,
        val values: List<String>,
        var selectedIndex: Int,
        val hint: String? = null,
        val isAdvanced: Boolean = false,
        val advancedDefault: String? = null
    ) : SettingItem() {
        override val viewType = VIEW_CHOICE
    }

    data class NumberInput(
        val key: String,
        val label: String,
        var value: String,
        val unit: String = "",
        val isDecimal: Boolean = false,
        val hint: String? = null,
        val isAdvanced: Boolean = false,
        val advancedDefault: String? = null
    ) : SettingItem() {
        override val viewType = VIEW_NUMBER
    }

    data class Slider(
        val key: String,
        val label: String,
        val min: Int,
        val max: Int,
        var value: Int,
        val hint: String? = null,
        val isAdvanced: Boolean = false,
        val advancedDefault: String? = null
    ) : SettingItem() {
        override val viewType = VIEW_SLIDER
    }

    class CoordPicker(
        val latKey: String,
        val lonKey: String,
        val label: String,
        var latRaw: String,
        var lonRaw: String,
        val hint: String? = null,
        val onPickClicked: () -> Unit
    ) : SettingItem() {
        override val viewType = VIEW_COORD
    }
}
