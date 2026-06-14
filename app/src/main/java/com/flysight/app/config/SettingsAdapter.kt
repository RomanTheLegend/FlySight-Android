package com.flysight.app.config

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
            if (item is SettingItem.Section) {
                currentExpanded = item.isExpanded
                result.add(item)
            } else if (currentExpanded) {
                result.add(item)
            }
        }
        return result
    }

    override fun getItemCount() = displayedItems.size
    override fun getItemViewType(pos: Int) = displayedItems[pos].viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            SettingItem.VIEW_SECTION -> SectionVH(inf.inflate(R.layout.item_setting_section, parent, false))
            SettingItem.VIEW_TOGGLE  -> ToggleVH(inf.inflate(R.layout.item_setting_toggle,  parent, false))
            SettingItem.VIEW_CHOICE  -> ChoiceVH(inf.inflate(R.layout.item_setting_choice,  parent, false))
            SettingItem.VIEW_COORD   -> CoordPickerVH(inf.inflate(R.layout.item_setting_coord,  parent, false))
            SettingItem.VIEW_SLIDER  -> SliderVH(inf.inflate(R.layout.item_setting_slider,  parent, false))
            else                     -> NumberVH(inf.inflate(R.layout.item_setting_number,   parent, false))
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
                children.add(allItems[i])
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
