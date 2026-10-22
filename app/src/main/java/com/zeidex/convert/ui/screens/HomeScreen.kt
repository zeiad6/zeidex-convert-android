package com.zeidex.convert.ui.screens

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zeidex.convert.R
import com.zeidex.convert.model.ConversionTask
import com.zeidex.convert.model.Format
import com.zeidex.convert.model.TaskStatus
import com.zeidex.convert.ui.components.FileCard
import com.zeidex.convert.ui.theme.SuccessGreen
import com.zeidex.convert.ui.theme.ZeidexOrange
import com.zeidex.convert.ui.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val availableFormats by viewModel.availableFormats.collectAsState()
    val tasks by viewModel.tasks.collectAsState()
    val isConverting by viewModel.isConverting.collectAsState()
    val compressionQuality by viewModel.compressionQuality.collectAsState()
    val maxDimension by viewModel.maxDimension.collectAsState()

    var chosenFormat by remember { mutableStateOf<Format?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addUris(uris)
            chosenFormat = null
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Privacy Banner
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.privacy_guarantee),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // Drop / Selection Zone
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp)),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                onClick = { filePicker.launch(arrayOf("*/*")) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .background(ZeidexOrange, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Select Files",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.drop_files_here),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // Selected files list
        if (selectedFiles.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "${stringResource(R.string.pick_files)} (${selectedFiles.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Button(
                        onClick = { viewModel.clearFiles() },
                        colors = ButtonDefaults.textButtonColors()
                    ) {
                        Text(stringResource(R.string.clear_all))
                    }
                }
            }

            items(selectedFiles) { file ->
                FileCard(
                    fileItem = file,
                    onRemove = { viewModel.removeFile(file) }
                )
            }

            // Target Formats Selection
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.select_target_format),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(availableFormats) { format ->
                            val isSelected = chosenFormat == format
                            ElevatedFilterChip(
                                selected = isSelected,
                                onClick = { chosenFormat = format },
                                label = { Text(format.label, fontWeight = FontWeight.Bold) },
                                colors = FilterChipDefaults.elevatedFilterChipColors(
                                    selectedContainerColor = ZeidexOrange,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Compression Settings Card
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Tune,
                                    contentDescription = null,
                                    tint = ZeidexOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = stringResource(R.string.compression_settings),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            // Quality Slider
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.compression_quality, compressionQuality),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Slider(
                                value = compressionQuality.toFloat(),
                                onValueChange = { viewModel.setCompressionQuality(it.toInt()) },
                                valueRange = 10f..100f,
                                steps = 17,
                                colors = SliderDefaults.colors(
                                    thumbColor = ZeidexOrange,
                                    activeTrackColor = ZeidexOrange
                                )
                            )

                            // Resolution options
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                val options = listOf(
                                    null to stringResource(R.string.res_original),
                                    1920 to stringResource(R.string.res_1080p),
                                    1280 to stringResource(R.string.res_720p),
                                    854 to stringResource(R.string.res_480p)
                                )
                                options.forEach { (dim, label) ->
                                    val isSelected = maxDimension == dim
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.setMaxDimension(dim) },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = ZeidexOrange,
                                            selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                        )
                                    )
                                }
                            }

                            // Storage info
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Folder,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = stringResource(R.string.storage_folder_desc),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Button(
                        onClick = {
                            chosenFormat?.let { viewModel.startConversion(it) }
                        },
                        enabled = chosenFormat != null && !isConverting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = ZeidexOrange)
                    ) {
                        if (isConverting) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${stringResource(R.string.convert_button)} ${chosenFormat?.label?.let { "→ $it" } ?: ""}",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Conversion History
        if (tasks.isNotEmpty()) {
            val hasPaused = tasks.any { it.status == TaskStatus.PAUSED }
            val hasProcessing = tasks.any { it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }

            item {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.nav_history),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (hasPaused) {
                            Button(
                                onClick = { viewModel.resumeConversion() },
                                colors = ButtonDefaults.buttonColors(containerColor = ZeidexOrange),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.resume_all), style = MaterialTheme.typography.labelMedium)
                            }
                        } else if (hasProcessing) {
                            Button(
                                onClick = { viewModel.pauseConversion() },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.task_pause), style = MaterialTheme.typography.labelMedium)
                            }
                        }

                        IconButton(onClick = { viewModel.clearHistory() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteSweep,
                                contentDescription = "Clear History",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            items(tasks) { task ->
                TaskCard(
                    task = task,
                    onPause = { viewModel.pauseTask(task.id) },
                    onResume = { viewModel.resumeTask(task.id) }
                )
            }
        }
    }
}

@Composable
fun TaskCard(
    task: ConversionTask,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {}
) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "${task.inputFile.name} → ${task.targetFormat.label}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = when (task.status) {
                            TaskStatus.PENDING -> "Pending…"
                            TaskStatus.PROCESSING -> "Converting ${(task.progress * 100).toInt()}%"
                            TaskStatus.PAUSED -> stringResource(R.string.task_paused)
                            TaskStatus.COMPLETED -> "Completed (${task.outputFile?.name})"
                            TaskStatus.FAILED -> "Failed: ${task.error}"
                            TaskStatus.CANCELLED -> "Cancelled"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (task.status) {
                            TaskStatus.COMPLETED -> SuccessGreen
                            TaskStatus.PAUSED -> ZeidexOrange
                            TaskStatus.FAILED -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (task.status == TaskStatus.COMPLETED && task.outputFile != null) {
                        Text(
                            text = "المسار: ${task.outputFile.absolutePath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Controls based on task status
                when (task.status) {
                    TaskStatus.COMPLETED -> {
                        if (task.outputFile != null) {
                            Row {
                                IconButton(
                                    onClick = {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            task.outputFile
                                        )
                                        val mime = if (task.outputFile.extension.equals("mp4", true)) "video/mp4" else "*/*"
                                        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(uri, mime)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        context.startActivity(Intent.createChooser(viewIntent, "Open"))
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (task.outputFile.extension.equals("mp4", true)) Icons.Default.PlayArrow else Icons.Default.Folder,
                                        contentDescription = "Open",
                                        tint = SuccessGreen
                                    )
                                }

                                IconButton(
                                    onClick = {
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            task.outputFile
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "*/*"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(Intent.createChooser(shareIntent, "Share"))
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Share,
                                        contentDescription = "Share",
                                        tint = ZeidexOrange
                                    )
                                }
                            }
                        }
                    }
                    TaskStatus.PROCESSING -> {
                        IconButton(onClick = onPause) {
                            Icon(
                                imageVector = Icons.Default.Pause,
                                contentDescription = "Pause",
                                tint = ZeidexOrange
                            )
                        }
                    }
                    TaskStatus.PAUSED, TaskStatus.FAILED -> {
                        IconButton(onClick = onResume) {
                            Icon(
                                imageVector = if (task.status == TaskStatus.PAUSED) Icons.Default.PlayArrow else Icons.Default.Refresh,
                                contentDescription = "Resume",
                                tint = ZeidexOrange
                            )
                        }
                    }
                    else -> {}
                }
            }

            if (task.status == TaskStatus.PROCESSING || task.status == TaskStatus.PAUSED) {
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { task.progress },
                    modifier = Modifier.fillMaxWidth(),
                    color = if (task.status == TaskStatus.PAUSED) MaterialTheme.colorScheme.secondary else ZeidexOrange
                )
            }
        }
    }
}
