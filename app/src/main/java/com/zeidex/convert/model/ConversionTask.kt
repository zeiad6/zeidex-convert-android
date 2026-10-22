package com.zeidex.convert.model

import java.io.File

enum class TaskStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED
}

data class ConversionTask(
    val id: String = java.util.UUID.randomUUID().toString(),
    val inputFile: FileItem,
    val targetFormat: Format,
    val status: TaskStatus = TaskStatus.PENDING,
    val progress: Float = 0f,
    val outputFile: File? = null,
    val error: String? = null,
    val timestamp: Long = System.currentTimeMillis()
)
