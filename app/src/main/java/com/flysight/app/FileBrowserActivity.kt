package com.flysight.app

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flysight.app.ble.BleState
import com.flysight.app.ble.CONFIG_PATH
import com.flysight.app.ble.DirEntry
import com.flysight.app.databinding.ActivityFileBrowserBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

sealed class FileBrowserItem {
    data class Header(val title: String) : FileBrowserItem()
    data class Entry(val dirEntry: DirEntry) : FileBrowserItem()
}

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var fileAdapter: FileListAdapter

    private val pathStack = ArrayDeque<String>()
    private val currentPath get() = pathStack.lastOrNull() ?: ""

    private val datePattern = Regex("""\d{2}-\d{2}-\d{2}""")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val ble = (application as FlySightApp).bleManager

        binding.btnHeaderBack.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pathStack.size > 1) {
                    pathStack.removeLast()
                    loadDirectory(ble)
                } else {
                    finish()
                }
            }
        })

        fileAdapter = FileListAdapter(
            onClick = { entry ->
                val entryPath = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
                when {
                    entry.isDirectory -> {
                        pathStack.addLast(entryPath)
                        loadDirectory(ble)
                    }
                    entry.name.equals(CONFIG_PATH, ignoreCase = true) -> {
                        startActivity(Intent(this, ConfigActivity::class.java))
                    }
                    else -> {
                        startActivity(Intent(this, FileViewActivity::class.java).apply {
                            putExtra(FileViewActivity.EXTRA_NAME, entry.name)
                            putExtra(FileViewActivity.EXTRA_PATH, entryPath)
                            putExtra(FileViewActivity.EXTRA_SIZE, entry.size)
                        })
                    }
                }
            },
            onLongClick = { entry, anchor ->
                val entryPath = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
                val popup = PopupMenu(this, anchor)
                popup.menu.add(0, 0, 0, "Delete")
                popup.setOnMenuItemClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("Delete folder")
                        .setMessage("Delete \"${entry.name}\" and all its contents?")
                        .setPositiveButton("Delete") { _, _ ->
                            val progress = showDeletingDialog()
                            lifecycleScope.launch {
                                try {
                                    ble.deleteRecursive(entryPath)
                                    progress.dismiss()
                                    loadDirectory(ble)
                                } catch (e: Exception) {
                                    progress.dismiss()
                                    Toast.makeText(
                                        this@FileBrowserActivity,
                                        "Delete failed: ${e.message}",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                    true
                }
                popup.show()
            }
        )

        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.adapter = fileAdapter

        binding.btnDisconnect.setOnClickListener {
            ble.disconnect()
            finish()
        }

        lifecycleScope.launch {
            ble.state.collectLatest { state ->
                if (state == BleState.Disconnected) finish()
            }
        }

        lifecycleScope.launch {
            ble.batteryLevel.collectLatest { level ->
                if (level >= 0) {
                    binding.tvBattery.text = "$level%"
                    binding.batteryChip.visibility = android.view.View.VISIBLE
                } else {
                    binding.batteryChip.visibility = android.view.View.GONE
                }
            }
        }

        pathStack.addLast("")
        loadDirectory(ble)
    }

    private fun showDeletingDialog(): AlertDialog {
        val dp = resources.displayMetrics.density
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (20 * dp).toInt())
            addView(ProgressBar(this@FileBrowserActivity))
            addView(TextView(this@FileBrowserActivity).apply {
                text = "Deleting…"
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.marginStart = (16 * dp).toInt() }
            })
        }
        return AlertDialog.Builder(this)
            .setView(view)
            .setCancelable(false)
            .show()
    }

    private fun loadDirectory(ble: com.flysight.app.ble.BleManager) {
        binding.tvPath.text = if (currentPath.isEmpty()) "/ root" else "/$currentPath"
        binding.btnHeaderBack.visibility = View.VISIBLE
        binding.tvEmpty.visibility = View.GONE
        setLoading(true)

        lifecycleScope.launch {
            try {
                val all = ble.listDir(currentPath)

                val trackCsv = all.firstOrNull { it.name.equals("TRACK.CSV", ignoreCase = true) }
                if (trackCsv != null) {
                    val trackPath = if (currentPath.isEmpty()) trackCsv.name
                                    else "$currentPath/${trackCsv.name}"
                    pathStack.removeLast()
                    setLoading(false)
                    startActivity(Intent(this@FileBrowserActivity, FileViewActivity::class.java).apply {
                        putExtra(FileViewActivity.EXTRA_NAME, trackCsv.name)
                        putExtra(FileViewActivity.EXTRA_PATH, trackPath)
                        putExtra(FileViewActivity.EXTRA_SIZE, trackCsv.size)
                    })
                    return@launch
                }

                val dateFolders    = all.filter { it.isDirectory && datePattern.matches(it.name) }
                val specialFolders = all.filter { it.isDirectory && !datePattern.matches(it.name) }
                val files          = all.filter { !it.isDirectory &&
                    it.name.equals(CONFIG_PATH, ignoreCase = true) }

                val items = buildList<FileBrowserItem> {
                    if (dateFolders.isNotEmpty()) {
                        add(FileBrowserItem.Header("FOLDERS"))
                        dateFolders.forEach { add(FileBrowserItem.Entry(it)) }
                    }
                    if (specialFolders.isNotEmpty()) {
                        add(FileBrowserItem.Header("SPECIAL FOLDERS"))
                        specialFolders.forEach { add(FileBrowserItem.Entry(it)) }
                    }
                    if (files.isNotEmpty()) {
                        add(FileBrowserItem.Header("FILES"))
                        files.forEach { add(FileBrowserItem.Entry(it)) }
                    }
                }

                fileAdapter.update(items)
                binding.tvEmpty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
            } catch (e: Exception) {
                Toast.makeText(
                    this@FileBrowserActivity,
                    "Error listing directory: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                setLoading(false)
            }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.progressBar.visibility    = if (loading) View.VISIBLE else View.GONE
        binding.recyclerFiles.visibility  = if (loading) View.GONE else View.VISIBLE
    }
}

class FileListAdapter(
    private val onClick: (DirEntry) -> Unit,
    private val onLongClick: ((DirEntry, View) -> Unit)? = null
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY  = 1
    }

    private var items = listOf<FileBrowserItem>()

    fun update(list: List<FileBrowserItem>) { items = list; notifyDataSetChanged() }

    override fun getItemViewType(position: Int) = when (items[position]) {
        is FileBrowserItem.Header -> TYPE_HEADER
        is FileBrowserItem.Entry  -> TYPE_ENTRY
    }

    inner class HeaderVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tvSectionTitle)
    }

    inner class EntryVH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:    TextView  = view.findViewById(R.id.tvName)
        val tvMeta:    TextView  = view.findViewById(R.id.tvMeta)
        val tvChevron: TextView  = view.findViewById(R.id.tvChevron)
        val ivIcon:    ImageView = view.findViewById(R.id.ivIcon)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderVH(inflater.inflate(R.layout.item_file_section, parent, false))
            else        -> EntryVH(inflater.inflate(R.layout.item_file, parent, false))
        }
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is FileBrowserItem.Header -> (holder as HeaderVH).tvTitle.text = item.title
            is FileBrowserItem.Entry  -> bindEntry(holder as EntryVH, item.dirEntry)
        }
    }

    private fun bindEntry(holder: EntryVH, entry: DirEntry) {
        holder.tvName.text = entry.name
        if (entry.isDirectory) {
            holder.tvChevron.visibility = View.VISIBLE
            holder.tvMeta.visibility    = View.GONE
            holder.ivIcon.setImageResource(when {
                entry.name.equals("AUDIO", ignoreCase = true) -> R.drawable.ic_folder_audio
                entry.name.equals("TEMP",  ignoreCase = true) -> R.drawable.ic_folder_temp
                else                                           -> R.drawable.ic_folder
            })
        } else {
            holder.tvChevron.visibility = View.GONE
            holder.tvMeta.text          = formatSize(entry.size)
            holder.tvMeta.visibility    = View.VISIBLE
            holder.ivIcon.setImageResource(R.drawable.ic_file_config)
        }
        holder.itemView.setOnClickListener { onClick(entry) }
        if (entry.isDirectory && onLongClick != null) {
            holder.itemView.setOnLongClickListener { view ->
                onLongClick.invoke(entry, view)
                true
            }
        } else {
            holder.itemView.setOnLongClickListener(null)
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%.1f KB".format(bytes / 1_024.0)
        else               -> "$bytes B"
    }
}
