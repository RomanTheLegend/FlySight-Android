package com.flysight.app.config

data class AlLine(
    var mode: Int  = 0,
    var units: Int = 1,
    var dec: Int   = 1
)

sealed class SettingItem {
    companion object {
        const val VIEW_SECTION  = 0
        const val VIEW_TOGGLE   = 1
        const val VIEW_CHOICE   = 2
        const val VIEW_NUMBER   = 3
        const val VIEW_COORD    = 4
        const val VIEW_SLIDER   = 5
        const val VIEW_AL_LINES = 6
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
        var onChanged: ((Int) -> Unit)? = null
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

    class AlLineList(
        val lines: MutableList<AlLine>
    ) : SettingItem() {
        override val viewType = VIEW_AL_LINES
        var isHidden: Boolean = false

        companion object {
            val modeOpts = listOf(
                "Horizontal Speed", "Vertical Speed", "Glide Ratio",
                "Inverse Glide Ratio", "Total Speed",
                "Direction to Dest.", "Distance to Dest.",
                "Direction to Bearing", "Dive Angle", "Altitude above DZ", "Course"
            )
            val modeVals = listOf("0", "1", "2", "3", "4", "5", "6", "7", "11", "12", "13")
        }
    }
}
