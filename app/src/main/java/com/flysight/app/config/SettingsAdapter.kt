package com.flysight.app.config

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.flysight.app.R

class SettingsAdapter(private val allItems: List<SettingItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val displayedItems: MutableList<SettingItem> = computeDisplayed().toMutableList()

    private fun computeDisplayed(): List<SettingItem> {
        val result = mutableListOf<SettingItem>()
        var currentExpanded = true
        for (item in allItems) {
            if (item is SettingItem.AlLineList && item.isHidden) continue
            if (item is SettingItem.Section) {
                currentExpanded = item.isExpanded
                result.add(item)
            } else if (currentExpanded) {
                result.add(item)
            }
        }
        return result
    }

    fun setAlLineListVisible(item: SettingItem.AlLineList, visible: Boolean) {
        if (item.isHidden == !visible) return
        item.isHidden = !visible
        val inDisplayed = displayedItems.indexOfFirst { it === item }
        if (visible && inDisplayed == -1) {
            val allIdx = allItems.indexOfFirst { it === item }
            var insertPos = 0
            for (i in allIdx - 1 downTo 0) {
                val di = displayedItems.indexOfFirst { it === allItems[i] }
                if (di >= 0) { insertPos = di + 1; break }
            }
            displayedItems.add(insertPos, item)
            notifyItemInserted(insertPos)
        } else if (!visible && inDisplayed >= 0) {
            displayedItems.removeAt(inDisplayed)
            notifyItemRemoved(inDisplayed)
        }
    }

    override fun getItemCount() = displayedItems.size
    override fun getItemViewType(pos: Int) = displayedItems[pos].viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            SettingItem.VIEW_SECTION  -> SectionVH(inf.inflate(R.layout.item_setting_section,   parent, false))
            SettingItem.VIEW_TOGGLE   -> ToggleVH(inf.inflate(R.layout.item_setting_toggle,     parent, false))
            SettingItem.VIEW_CHOICE   -> ChoiceVH(inf.inflate(R.layout.item_setting_choice,     parent, false))
            SettingItem.VIEW_COORD    -> CoordPickerVH(inf.inflate(R.layout.item_setting_coord, parent, false))
            SettingItem.VIEW_SLIDER   -> SliderVH(inf.inflate(R.layout.item_setting_slider,     parent, false))
            SettingItem.VIEW_AL_LINES -> AlLineListVH(inf.inflate(R.layout.item_setting_al_lines, parent, false))
            else                      -> NumberVH(inf.inflate(R.layout.item_setting_number,     parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = displayedItems[position]) {
            is SettingItem.Section     -> (holder as SectionVH).bind(item) { toggleSection(item) }
            is SettingItem.Toggle      -> (holder as ToggleVH).bind(item)
            is SettingItem.Choice      -> (holder as ChoiceVH).bind(item)
            is SettingItem.NumberInput -> (holder as NumberVH).bind(item)
            is SettingItem.CoordPicker -> (holder as CoordPickerVH).bind(item)
            is SettingItem.Slider      -> (holder as SliderVH).bind(item)
            is SettingItem.AlLineList  -> (holder as AlLineListVH).bind(item)
        }
    }

    private fun toggleSection(section: SettingItem.Section) {
        val sectionPos = displayedItems.indexOfFirst { it === section }
        if (sectionPos == -1) return

        if (section.isExpanded) {
            section.isExpanded = false
            val firstChild = sectionPos + 1
            var count = 0
            while (firstChild + count < displayedItems.size &&
                   displayedItems[firstChild + count] !is SettingItem.Section) {
                count++
            }
            if (count > 0) {
                repeat(count) { displayedItems.removeAt(firstChild) }
                notifyItemRangeRemoved(firstChild, count)
            }
        } else {
            section.isExpanded = true
            val allSectionPos = allItems.indexOfFirst { it === section }
            val children = mutableListOf<SettingItem>()
            for (i in allSectionPos + 1 until allItems.size) {
                if (allItems[i] is SettingItem.Section) break
                val child = allItems[i]
                if (child is SettingItem.AlLineList && child.isHidden) continue
                children.add(child)
            }
            if (children.isNotEmpty()) {
                val insertPos = sectionPos + 1
                displayedItems.addAll(insertPos, children)
                notifyItemRangeInserted(insertPos, children.size)
            }
        }
        notifyItemChanged(sectionPos)
    }

    class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle:   TextView = view.findViewById(R.id.tvSectionTitle)
        private val tvChevron: TextView = view.findViewById(R.id.tvChevron)

        fun bind(item: SettingItem.Section, onToggle: () -> Unit) {
            tvTitle.text   = item.title
            tvChevron.text = if (item.isExpanded) "▼" else "▶"
            itemView.setOnClickListener { onToggle() }
        }
    }

    class ToggleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val sw: SwitchCompat  = view.findViewById(R.id.switchToggle)
        private val btnHint: TextView = view.findViewById(R.id.btnHint)

        fun bind(item: SettingItem.Toggle) {
            tvLabel.text = item.label
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = item.enabled
            sw.setOnCheckedChangeListener { _, checked -> item.enabled = checked }
            itemView.setOnClickListener { sw.toggle() }
            bindHint(btnHint, item.hint)
        }
    }

    class ChoiceVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val btnHint: TextView = view.findViewById(R.id.btnHint)

        fun bind(item: SettingItem.Choice) {
            tvLabel.text = item.label
            tvValue.text = item.options.getOrNull(item.selectedIndex) ?: ""
            itemView.setOnClickListener {
                AlertDialog.Builder(itemView.context)
                    .setTitle(item.label)
                    .setSingleChoiceItems(item.options.toTypedArray(), item.selectedIndex) { dialog, which ->
                        item.selectedIndex = which
                        tvValue.text = item.options[which]
                        item.onChanged?.invoke(which)
                        dialog.dismiss()
                    }
                    .show()
            }
            bindHint(btnHint, item.hint)
        }
    }

    class NumberVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val etValue: EditText = view.findViewById(R.id.etValue)
        private val tvUnit:  TextView = view.findViewById(R.id.tvUnit)
        private val btnHint: TextView = view.findViewById(R.id.btnHint)
        private var bound: SettingItem.NumberInput? = null

        private val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { bound?.value = s?.toString() ?: "" }
        }

        init { etValue.addTextChangedListener(watcher) }

        fun bind(item: SettingItem.NumberInput) {
            bound = null
            tvLabel.text = item.label
            etValue.inputType = if (item.isDecimal) {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED or InputType.TYPE_NUMBER_FLAG_DECIMAL
            } else {
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
            }
            etValue.setText(item.value)
            tvUnit.text = item.unit
            tvUnit.visibility = if (item.unit.isEmpty()) View.GONE else View.VISIBLE
            bindHint(btnHint, item.hint)
            bound = item
        }
    }

    class SliderVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)
        private val sbValue: SeekBar  = view.findViewById(R.id.sbValue)
        private val btnHint: TextView = view.findViewById(R.id.btnHint)

        fun bind(item: SettingItem.Slider) {
            tvLabel.text = item.label
            sbValue.max  = item.max - item.min
            sbValue.progress = (item.value - item.min).coerceIn(0, item.max - item.min)
            tvValue.text = item.value.toString()
            sbValue.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                    item.value = item.min + progress
                    tvValue.text = item.value.toString()
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
            bindHint(btnHint, item.hint)
        }
    }

    class CoordPickerVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel:  TextView = view.findViewById(R.id.tvLabel)
        private val tvCoords: TextView = view.findViewById(R.id.tvCoords)
        private val btnHint:  TextView = view.findViewById(R.id.btnHint)

        fun bind(item: SettingItem.CoordPicker) {
            tvLabel.text  = item.label
            tvCoords.text = formatCoords(item.latRaw, item.lonRaw)
            itemView.setOnClickListener { item.onPickClicked() }
            bindHint(btnHint, item.hint)
        }

        private fun formatCoords(latRaw: String, lonRaw: String): String {
            val lat = latRaw.toLongOrNull()?.let { it / 1e7 } ?: 0.0
            val lon = lonRaw.toLongOrNull()?.let { it / 1e7 } ?: 0.0
            return if (lat == 0.0 && lon == 0.0) "Not set"
                   else "%.6f°,  %.6f°".format(lat, lon)
        }
    }

    class AlLineListVH(view: View) : RecyclerView.ViewHolder(view) {
        private val btnHint:        TextView    = view.findViewById(R.id.btnHint)
        private val containerLines: LinearLayout = view.findViewById(R.id.containerLines)
        private val btnAddLine:     TextView    = view.findViewById(R.id.btnAddLine)
        private val btnRemoveLine:  TextView    = view.findViewById(R.id.btnRemoveLine)

        fun bind(item: SettingItem.AlLineList) {
            bindHint(btnHint, "Configure up to 4 data lines shown on ActiveLook glasses in Default Mode. At least one line is required.")
            renderLines(item)
            btnAddLine.setOnClickListener {
                if (item.lines.size < 4) {
                    item.lines.add(AlLine())
                    renderLines(item)
                    updateButtons(item)
                }
            }
            btnRemoveLine.setOnClickListener {
                if (item.lines.size > 1) {
                    item.lines.removeAt(item.lines.size - 1)
                    renderLines(item)
                    updateButtons(item)
                }
            }
            updateButtons(item)
        }

        private fun renderLines(item: SettingItem.AlLineList) {
            containerLines.removeAllViews()
            for ((index, line) in item.lines.withIndex()) {
                val row = LayoutInflater.from(itemView.context)
                    .inflate(R.layout.item_al_line_row, containerLines, false)
                bindLineRow(row, index + 1, line)
                containerLines.addView(row)
            }
        }

        private fun bindLineRow(view: View, lineNum: Int, line: AlLine) {
            view.findViewById<TextView>(R.id.tvLineNum).text = "Line $lineNum"

            val tvMode = view.findViewById<TextView>(R.id.tvMode)
            val modeIndex = SettingItem.AlLineList.modeVals.indexOf(line.mode.toString())
            tvMode.text = SettingItem.AlLineList.modeOpts.getOrElse(modeIndex) { "Unknown" }
            tvMode.setOnClickListener {
                AlertDialog.Builder(view.context)
                    .setTitle("Mode")
                    .setSingleChoiceItems(
                        SettingItem.AlLineList.modeOpts.toTypedArray(),
                        modeIndex.coerceAtLeast(0)
                    ) { dialog, which ->
                        line.mode = SettingItem.AlLineList.modeVals[which].toInt()
                        tvMode.text = SettingItem.AlLineList.modeOpts[which]
                        dialog.dismiss()
                    }
                    .show()
            }

            val tvUnits = view.findViewById<TextView>(R.id.tvUnits)
            tvUnits.text = if (line.units == 0) "km/h or m" else "mph or feet"
            tvUnits.setOnClickListener {
                AlertDialog.Builder(view.context)
                    .setTitle("Units")
                    .setSingleChoiceItems(
                        arrayOf("km/h or m", "mph or feet"),
                        line.units.coerceIn(0, 1)
                    ) { dialog, which ->
                        line.units = which
                        tvUnits.text = if (which == 0) "km/h or m" else "mph or feet"
                        dialog.dismiss()
                    }
                    .show()
            }

            val tvDec = view.findViewById<TextView>(R.id.tvDec)
            tvDec.text = line.dec.toString()
            tvDec.setOnClickListener {
                AlertDialog.Builder(view.context)
                    .setTitle("Decimal Places")
                    .setSingleChoiceItems(
                        arrayOf("0", "1", "2"),
                        line.dec.coerceIn(0, 2)
                    ) { dialog, which ->
                        line.dec = which
                        tvDec.text = which.toString()
                        dialog.dismiss()
                    }
                    .show()
            }
        }

        private fun updateButtons(item: SettingItem.AlLineList) {
            val canAdd    = item.lines.size < 4
            val canRemove = item.lines.size > 1
            btnAddLine.isEnabled    = canAdd
            btnAddLine.alpha        = if (canAdd) 1f else 0.35f
            btnRemoveLine.isEnabled = canRemove
            btnRemoveLine.alpha     = if (canRemove) 1f else 0.35f
        }
    }
}

private fun bindHint(btn: TextView, hint: String?) {
    if (hint == null) {
        btn.visibility = View.GONE
        return
    }
    btn.visibility = View.VISIBLE
    btn.setOnClickListener {
        AlertDialog.Builder(btn.context)
            .setMessage(hint)
            .setPositiveButton("OK", null)
            .show()
    }
}
