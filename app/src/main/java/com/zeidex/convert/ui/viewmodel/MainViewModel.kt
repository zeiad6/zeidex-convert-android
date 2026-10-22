package com.zeidex.convert.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeidex.convert.engine.ConversionEngine
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.ConversionTask
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.model.Format
import com.zeidex.convert.model.TaskStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ConversionEngine(application)

    private val _selectedFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val selectedFiles: StateFlow<List<FileItem>> = _selectedFiles.asStateFlow()

    private val _availableFormats = MutableStateFlow<List<Format>>(emptyList())
    val availableFormats: StateFlow<List<Format>> = _availableFormats.asStateFlow()

    private val _tasks = MutableStateFlow<List<ConversionTask>>(emptyList())
    val tasks: StateFlow<List<ConversionTask>> = _tasks.asStateFlow()

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    fun addUris(uris: List<Uri>) {
        val context = getApplication<Application>()
        val newItems = uris.mapNotNull { uri ->
            try {
                var name = "file"
                var size = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIndex != -1) name = cursor.getString(nameIndex) ?: "file"
                        if (sizeIndex != -1) size = cursor.getLong(sizeIndex)
                    }
                }
                val mimeType = context.contentResolver.getType(uri)
                val ext = name.substringAfterLast('.', "")
                val category = Format.detectCategory(ext, mimeType)

                FileItem(uri, name, size, mimeType, category, ext)
            } catch (e: Exception) {
                null
            }
        }

        val updated = _selectedFiles.value + newItems
        _selectedFiles.value = updated
        updateAvailableFormats(updated)
    }

    fun removeFile(item: FileItem) {
        val updated = _selectedFiles.value.filter { it.uri != item.uri }
        _selectedFiles.value = updated
        updateAvailableFormats(updated)
    }

    fun clearFiles() {
        _selectedFiles.value = emptyList()
        _availableFormats.value = emptyList()
    }

    private fun updateAvailableFormats(files: List<FileItem>) {
        if (files.isEmpty()) {
            _availableFormats.value = emptyList()
            return
        }
        val first = files.first()
        _availableFormats.value = Format.targetsFor(first.category, first.ext)
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                uri?.let { addUris(listOf(it)) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.let { addUris(it) }
            }
        }
    }

    fun startConversion(targetFormat: Format) {
        val files = _selectedFiles.value
        if (files.isEmpty()) return

        viewModelScope.launch {
            _isConverting.value = true
            val newTasks = files.map { file ->
                ConversionTask(
                    inputFile = file,
                    targetFormat = targetFormat,
                    status = TaskStatus.PROCESSING
                )
            }
            _tasks.value = newTasks + _tasks.value

            newTasks.forEach { task ->
                val result = engine.processTask(task) { prog ->
                    updateTaskProgress(task.id, prog)
                }
                updateTaskResult(result)
            }
            _isConverting.value = false
            clearFiles()
        }
    }

    private fun updateTaskProgress(taskId: String, progress: Float) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(progress = progress) else it
        }
    }

    private fun updateTaskResult(task: ConversionTask) {
        _tasks.value = _tasks.value.map {
            if (it.id == task.id) task else it
        }
    }
}
