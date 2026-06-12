package com.flysight.app.config

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.recyclerview.widget.RecyclerView
import com.flysight.app.R

class SettingsAdapter(private val items: List<SettingItem>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    override fun getItemCount() = items.size
    override fun getItemViewType(pos: Int) = items[pos].viewType

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inf = LayoutInflater.from(parent.context)
        return when (viewType) {
            SettingItem.VIEW_SECTION -> SectionVH(inf.inflate(R.layout.item_setting_section, parent, false))
            SettingItem.VIEW_TOGGLE  -> ToggleVH(inf.inflate(R.layout.item_setting_toggle,  parent, false))
            SettingItem.VIEW_CHOICE  -> ChoiceVH(inf.inflate(R.layout.item_setting_choice,  parent, false))
            SettingItem.VIEW_COORD   -> CoordPickerVH(inf.inflate(R.layout.item_setting_coord, parent, false))
            else                     -> NumberVH(inf.inflate(R.layout.item_setting_number,  parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is SettingItem.Section     -> (holder as SectionVH).bind(item)
            is SettingItem.Toggle      -> (holder as ToggleVH).bind(item)
            is SettingItem.Choice      -> (holder as ChoiceVH).bind(item)
            is SettingItem.NumberInput -> (holder as NumberVH).bind(item)
            is SettingItem.CoordPicker -> (holder as CoordPickerVH).bind(item)
        }
    }

    class SectionVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
        fun bind(item: SettingItem.Section) { tvTitle.text = item.title }
    }

    class ToggleVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val sw: SwitchCompat  = view.findViewById(R.id.switchToggle)

        fun bind(item: SettingItem.Toggle) {
            tvLabel.text = item.label
            sw.setOnCheckedChangeListener(null)
            sw.isChecked = item.enabled
            sw.setOnCheckedChangeListener { _, checked -> item.enabled = checked }
        }
    }

    class ChoiceVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val tvValue: TextView = view.findViewById(R.id.tvValue)

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
        }
    }

    class NumberVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel: TextView = view.findViewById(R.id.tvLabel)
        private val etValue: EditText = view.findViewById(R.id.etValue)
        private val tvUnit:  TextView = view.findViewById(R.id.tvUnit)
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
            bound = item
        }
    }

    class CoordPickerVH(view: View) : RecyclerView.ViewHolder(view) {
        private val tvLabel:  TextView = view.findViewById(R.id.tvLabel)
        private val tvCoords: TextView = view.findViewById(R.id.tvCoords)

        fun bind(item: SettingItem.CoordPicker) {
            tvLabel.text  = item.label
            tvCoords.text = formatCoords(item.latRaw, item.lonRaw)
            itemView.setOnClickListener { item.onPickClicked() }
        }

        private fun formatCoords(latRaw: String, lonRaw: String): String {
            val lat = latRaw.toLongOrNull()?.let { it / 1e7 } ?: 0.0
            val lon = lonRaw.toLongOrNull()?.let { it / 1e7 } ?: 0.0
            return if (lat == 0.0 && lon == 0.0) "Not set"
                   else "%.6f°,  %.6f°".format(lat, lon)
        }
    }
}
