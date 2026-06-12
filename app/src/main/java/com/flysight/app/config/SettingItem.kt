package com.flysight.app.config

sealed class SettingItem {
    companion object {
        const val VIEW_SECTION = 0
        const val VIEW_TOGGLE  = 1
        const val VIEW_CHOICE  = 2
        const val VIEW_NUMBER  = 3
        const val VIEW_COORD   = 4
    }
    abstract val viewType: Int

    data class Section(val title: String) : SettingItem() {
        override val viewType = VIEW_SECTION
    }

    data class Toggle(
        val key: String,
        val label: String,
        var enabled: Boolean
    ) : SettingItem() {
        override val viewType = VIEW_TOGGLE
    }

    data class Choice(
        val key: String,
        val label: String,
        val options: List<String>,
        val values: List<String>,
        var selectedIndex: Int
    ) : SettingItem() {
        override val viewType = VIEW_CHOICE
    }

    data class NumberInput(
        val key: String,
        val label: String,
        var value: String,
        val unit: String = "",
        val isDecimal: Boolean = false
    ) : SettingItem() {
        override val viewType = VIEW_NUMBER
    }

    class CoordPicker(
        val latKey: String,
        val lonKey: String,
        val label: String,
        var latRaw: String,
        var lonRaw: String,
        val onPickClicked: () -> Unit
    ) : SettingItem() {
        override val viewType = VIEW_COORD
    }
}
