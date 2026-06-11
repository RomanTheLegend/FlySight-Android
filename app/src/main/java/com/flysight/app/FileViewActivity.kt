package com.flysight.app

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.flysight.app.databinding.ActivityFileViewBinding

class FileViewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "file_name"
        const val EXTRA_PATH = "file_path"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityFileViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val name = intent.getStringExtra(EXTRA_NAME) ?: "Unknown"
        val path = intent.getStringExtra(EXTRA_PATH) ?: ""

        supportActionBar?.title = name
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        binding.tvFileName.text = name
        binding.tvFilePath.text = "/$path"
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
