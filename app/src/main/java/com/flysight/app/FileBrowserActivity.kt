package com.flysight.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.flysight.app.ble.BleState
import com.flysight.app.ble.DirEntry
import com.flysight.app.databinding.ActivityFileBrowserBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class FileBrowserActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFileBrowserBinding
    private lateinit var fileAdapter: FileListAdapter

    // Stack of paths; "" = root
    private val pathStack = ArrayDeque<String>()
    private val currentPath get() = pathStack.lastOrNull() ?: ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFileBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)
        supportActionBar?.title = "Files"

        val ble = (application as FlySightApp).bleManager

        fileAdapter = FileListAdapter { entry ->
            val entryPath = if (currentPath.isEmpty()) entry.name else "$currentPath/${entry.name}"
            when {
                entry.isDirectory -> {
                    pathStack.addLast(entryPath)
                    loadDirectory(ble)
                }
                entry.name.equals("CONFIG.TXT", ignoreCase = true) -> {
                    startActivity(Intent(this, ConfigActivity::class.java))
                }
                else -> {
                    startActivity(Intent(this, FileViewActivity::class.java).apply {
                        putExtra(FileViewActivity.EXTRA_NAME, entry.name)
                        putExtra(FileViewActivity.EXTRA_PATH, entryPath)
                    })
                }
            }
        }

        binding.recyclerFiles.layoutManager = LinearLayoutManager(this)
        binding.recyclerFiles.addItemDecoration(
            DividerItemDecoration(this, DividerItemDecoration.VERTICAL)
        )
        binding.recyclerFiles.adapter = fileAdapter

        binding.btnBack.setOnClickListener {
            pathStack.removeLast()
            loadDirectory(ble)
        }

        binding.btnDisconnect.setOnClickListener {
            ble.disconnect()
            finish()
        }

        lifecycleScope.launch {
            ble.state.collectLatest { state ->
                if (state == BleState.Disconnected) {
                    finish()
                }
            }
        }

        pathStack.addLast("")
        loadDirectory(ble)
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        if (pathStack.size > 1) {
            pathStack.removeLast()
            loadDirectory((application as FlySightApp).bleManager)
        } else {
            super.onBackPressed()
        }
    }

    private fun loadDirectory(ble: com.flysight.app.ble.BleManager) {
        binding.tvPath.text = if (currentPath.isEmpty()) "/" else "/$currentPath"
        binding.btnBack.visibility = if (pathStack.size > 1) View.VISIBLE else View.GONE
        binding.tvEmpty.visibility = View.GONE
        setLoading(true)

        lifecycleScope.launch {
            try {
                val entries = ble.listDir(currentPath)
                fileAdapter.update(entries)
                binding.tvEmpty.visibility = if (entries.isEmpty()) View.VISIBLE else View.GONE
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
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.recyclerFiles.visibility = if (loading) View.GONE else View.VISIBLE
    }
}

class FileListAdapter(
    private val onClick: (DirEntry) -> Unit
) : RecyclerView.Adapter<FileListAdapter.VH>() {

    private var items = listOf<DirEntry>()

    fun update(list: List<DirEntry>) { items = list; notifyDataSetChanged() }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvName:    TextView = view.findViewById(R.id.tvName)
        val tvMeta:    TextView = view.findViewById(R.id.tvMeta)
        val tvChevron: TextView = view.findViewById(R.id.tvChevron)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = VH(
        LayoutInflater.from(parent.context).inflate(R.layout.item_file, parent, false)
    )

    override fun getItemCount() = items.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.tvName.text = entry.name
        if (entry.isDirectory) {
            holder.tvName.setTextColor(0xFF1565C0.toInt())
            holder.tvMeta.visibility = View.GONE
            holder.tvChevron.visibility = View.VISIBLE
        } else {
            holder.tvName.setTextColor(0xFF222222.toInt())
            holder.tvMeta.text = formatSize(entry.size)
            holder.tvMeta.visibility = View.VISIBLE
            holder.tvChevron.visibility = View.GONE
        }
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
        bytes >= 1_024     -> "%.1f KB".format(bytes / 1_024.0)
        else               -> "$bytes B"
    }
}
