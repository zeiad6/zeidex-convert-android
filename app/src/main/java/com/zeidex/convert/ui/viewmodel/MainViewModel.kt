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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = ConversionEngine(application)
    private var conversionJob: Job? = null
    @Volatile
    private var isPaused = false

    private val _selectedFiles = MutableStateFlow<List<FileItem>>(emptyList())
    val selectedFiles: StateFlow<List<FileItem>> = _selectedFiles.asStateFlow()

    private val _availableFormats = MutableStateFlow<List<Format>>(emptyList())
    val availableFormats: StateFlow<List<Format>> = _availableFormats.asStateFlow()

    private val _tasks = MutableStateFlow<List<ConversionTask>>(emptyList())
    val tasks: StateFlow<List<ConversionTask>> = _tasks.asStateFlow()

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting.asStateFlow()

    private val tasksStorageFile: File
        get() = File(getApplication<Application>().filesDir, "conversion_tasks.json")

    init {
        loadTasks()
    }

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

        isPaused = false
        val newTasks = files.map { file ->
            ConversionTask(
                inputFile = file,
                targetFormat = targetFormat,
                status = TaskStatus.PENDING
            )
        }
        _tasks.value = newTasks + _tasks.value
        clearFiles()
        saveTasks()

        runTaskQueue()
    }

    fun pauseConversion() {
        isPaused = true
        _isConverting.value = false
        _tasks.value = _tasks.value.map { task ->
            if (task.status == TaskStatus.PROCESSING || task.status == TaskStatus.PENDING) {
                task.copy(status = TaskStatus.PAUSED)
            } else task
        }
        conversionJob?.cancel()
        saveTasks()
    }

    fun resumeConversion() {
        isPaused = false
        _tasks.value = _tasks.value.map { task ->
            if (task.status == TaskStatus.PAUSED) {
                task.copy(status = TaskStatus.PENDING)
            } else task
        }
        saveTasks()
        runTaskQueue()
    }

    fun resumeTask(taskId: String) {
        isPaused = false
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) task.copy(status = TaskStatus.PENDING, error = null) else task
        }
        saveTasks()
        runTaskQueue()
    }

    fun pauseTask(taskId: String) {
        _tasks.value = _tasks.value.map { task ->
            if (task.id == taskId) task.copy(status = TaskStatus.PAUSED) else task
        }
        saveTasks()
    }

    fun clearHistory() {
        _tasks.value = _tasks.value.filter { it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }
        saveTasks()
    }

    private fun runTaskQueue() {
        if (_isConverting.value) return
        conversionJob?.cancel()

        conversionJob = viewModelScope.launch {
            _isConverting.value = true

            while (!isPaused) {
                val nextTask = _tasks.value.firstOrNull { it.status == TaskStatus.PENDING } ?: break

                updateTaskStatus(nextTask.id, TaskStatus.PROCESSING)

                val result = engine.processTask(nextTask) { prog ->
                    updateTaskProgress(nextTask.id, prog)
                }

                if (isPaused) {
                    updateTaskStatus(nextTask.id, TaskStatus.PAUSED)
                } else {
                    updateTaskResult(result)
                }
                saveTasks()
            }

            _isConverting.value = false
        }
    }

    private fun updateTaskStatus(taskId: String, status: TaskStatus) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) it.copy(status = status) else it
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

    private fun saveTasks() {
        try {
            val jsonArray = JSONArray()
            _tasks.value.forEach { t ->
                val obj = JSONObject().apply {
                    put("id", t.id)
                    put("status", t.status.name)
                    put("progress", t.progress.toDouble())
                    put("error", t.error ?: "")
                    put("timestamp", t.timestamp)
                    put("outputFile", t.outputFile?.absolutePath ?: "")

                    val fileObj = JSONObject().apply {
                        put("uri", t.inputFile.uri.toString())
                        put("name", t.inputFile.name)
                        put("size", t.inputFile.size)
                        put("mimeType", t.inputFile.mimeType ?: "")
                        put("category", t.inputFile.category.name)
                        put("ext", t.inputFile.ext)
                    }
                    put("inputFile", fileObj)

                    val fmtObj = JSONObject().apply {
                        put("ext", t.targetFormat.ext)
                        put("label", t.targetFormat.label)
                        put("category", t.targetFormat.category.name)
                    }
                    put("targetFormat", fmtObj)
                }
                jsonArray.put(obj)
            }
            tasksStorageFile.writeText(jsonArray.toString())
        } catch (_: Exception) {}
    }

    private fun loadTasks() {
        try {
            if (!tasksStorageFile.exists()) return
            val text = tasksStorageFile.readText()
            if (text.isBlank()) return
            val jsonArray = JSONArray(text)
            val loaded = mutableListOf<ConversionTask>()

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                val id = obj.getString("id")
                var statusStr = obj.optString("status", "COMPLETED")
                // If the app was closed during processing, mark it as PAUSED for resumption
                if (statusStr == "PROCESSING" || statusStr == "PENDING") {
                    statusStr = "PAUSED"
                }
                val status = try { TaskStatus.valueOf(statusStr) } catch (_: Exception) { TaskStatus.COMPLETED }
                val progress = obj.optDouble("progress", 0.0).toFloat()
                val error = obj.optString("error").takeIf { it.isNotEmpty() }
                val timestamp = obj.optLong("timestamp", System.currentTimeMillis())
                val outPath = obj.optString("outputFile").takeIf { it.isNotEmpty() }
                val outFile = outPath?.let { File(it) }?.takeIf { it.exists() }

                val fileObj = obj.getJSONObject("inputFile")
                val uri = Uri.parse(fileObj.getString("uri"))
                val name = fileObj.getString("name")
                val size = fileObj.optLong("size", 0L)
                val mimeType = fileObj.optString("mimeType").takeIf { it.isNotEmpty() }
                val catStr = fileObj.optString("category", "DOCUMENT")
                val category = try { Category.valueOf(catStr) } catch (_: Exception) { Category.DOCUMENT }
                val ext = fileObj.optString("ext", "")
                val fileItem = FileItem(uri, name, size, mimeType, category, ext)

                val fmtObj = obj.getJSONObject("targetFormat")
                val targetExt = fmtObj.getString("ext")
                val label = fmtObj.getString("label")
                val fmtCatStr = fmtObj.optString("category", "DOCUMENT")
                val fmtCategory = try { Category.valueOf(fmtCatStr) } catch (_: Exception) { Category.DOCUMENT }
                val format = Format(targetExt, label, fmtCategory)

                loaded.add(
                    ConversionTask(
                        id = id,
                        inputFile = fileItem,
                        targetFormat = format,
                        status = status,
                        progress = progress,
                        outputFile = outFile,
                        error = error,
                        timestamp = timestamp
                    )
                )
            }
            _tasks.value = loaded
        } catch (_: Exception) {}
    }
}
