package com.zeidex.convert.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeidex.convert.R
import com.zeidex.convert.model.Format
import com.zeidex.convert.ui.components.RadialWheel
import com.zeidex.convert.ui.theme.ZeidexOrange
import com.zeidex.convert.ui.viewmodel.MainViewModel

@Composable
fun WheelScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val availableFormats by viewModel.availableFormats.collectAsState()
    val isConverting by viewModel.isConverting.collectAsState()

    var activeFormat by remember { mutableStateOf<Format?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.addUris(uris)
            activeFormat = null
        }
    }

    // Default formats if no file selected yet
    val displayFormats = if (availableFormats.isNotEmpty()) {
        availableFormats
    } else {
        listOf(
            Format.PNG, Format.JPG, Format.WEBP, Format.PDF,
            Format.MP4, Format.MP3, Format.ZIP, Format.TXT
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top status card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (selectedFiles.isEmpty()) {
                            stringResource(R.string.drop_files_here)
                        } else {
                            "${selectedFiles.size} ${stringResource(R.string.pick_files)}"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (selectedFiles.isEmpty()) {
                            "Select files to see dynamic target formats"
                        } else {
                            selectedFiles.first().name
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }

                OutlinedButton(
                    onClick = { filePicker.launch(arrayOf("*/*")) },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "Pick Files",
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(R.string.pick_files))
                }
            }
        }

        // Center Radial Wheel
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                RadialWheel(
                    formats = displayFormats,
                    onFormatSelected = { format ->
                        activeFormat = format
                    },
                    centerLabel = activeFormat?.label ?: "Zeidex",
                    modifier = Modifier.size(310.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                AnimatedVisibility(
                    visible = activeFormat != null,
                    enter = fadeIn(),
                    exit = fadeOut()
                ) {
                    activeFormat?.let { fmt ->
                        Card(
                            shape = CircleShape,
                            colors = CardDefaults.cardColors(containerColor = ZeidexOrange.copy(alpha = 0.15f))
                        ) {
                            Text(
                                text = "Target: ${fmt.label} (${stringResource(fmt.category.labelRes)})",
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                color = ZeidexOrange,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // Bottom Action Button
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    val fmt = activeFormat
                    if (fmt != null && selectedFiles.isNotEmpty()) {
                        viewModel.startConversion(fmt)
                    } else if (selectedFiles.isEmpty()) {
                        filePicker.launch(arrayOf("*/*"))
                    }
                },
                enabled = !isConverting && (selectedFiles.isNotEmpty() && activeFormat != null || selectedFiles.isEmpty()),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = ZeidexOrange
                )
            ) {
                if (isConverting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(24.dp)
                    )
                } else {
                    Icon(
                        imageVector = if (selectedFiles.isEmpty()) Icons.Default.Add else Icons.Default.PlayArrow,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when {
                            selectedFiles.isEmpty() -> stringResource(R.string.pick_files)
                            activeFormat != null -> "${stringResource(R.string.convert_button)} → ${activeFormat?.label}"
                            else -> stringResource(R.string.select_target_format)
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
