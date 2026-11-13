package com.zeidex.convert.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.zeidex.convert.R
import com.zeidex.convert.engine.ImageConverter
import com.zeidex.convert.engine.MotionPhotoExtractor
import com.zeidex.convert.engine.PdfConverter
import com.zeidex.convert.engine.QrEngine
import com.zeidex.convert.engine.VideoEnhancer
import com.zeidex.convert.model.Category
import com.zeidex.convert.model.FileItem
import com.zeidex.convert.service.ConversionForegroundService
import com.zeidex.convert.ui.theme.SuccessGreen
import com.zeidex.convert.ui.theme.ZeidexOrange
import com.zeidex.convert.ui.viewmodel.MainViewModel
import com.zeidex.convert.util.StorageManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabTitles = listOf(
        stringResource(R.string.tool_qr),
        stringResource(R.string.tool_motion),
        stringResource(R.string.tool_pdf_merge),
        stringResource(R.string.tool_enhance)
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        PrimaryTabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = ZeidexOrange
        ) {
            tabTitles.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = {
                        Text(
                            text = title,
                            fontWeight = if (selectedTab == index) FontWeight.Bold else FontWeight.Normal,
                            maxLines = 1
                        )
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            when (selectedTab) {
                0 -> QrToolView()
                1 -> MotionPhotoToolView()
                2 -> PdfToolView()
                3 -> EnhanceToolView()
            }
        }
    }
}

@Composable
fun QrToolView() {
    val context = LocalContext.current
    var qrText by remember { mutableStateOf("https://zeidexlink.zeidexsoft.com/zeidex") }
    var qrBitmap by remember {
        mutableStateOf<Bitmap?>(
            try {
                QrEngine.generateQrBitmap("https://zeidexlink.zeidexsoft.com/zeidex")
            } catch (e: Exception) {
                null
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        OutlinedTextField(
            value = qrText,
            onValueChange = {
                qrText = it
                if (it.isNotBlank()) {
                    try {
                        qrBitmap = QrEngine.generateQrBitmap(it)
                    } catch (_: Exception) {}
                }
            },
            label = { Text("Enter Text or URL") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        )

        qrBitmap?.let { bmp ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.padding(8.dp)
            ) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "QR Code",
                    modifier = Modifier
                        .size(260.dp)
                        .padding(16.dp)
                )
            }
        }

        Button(
            onClick = {
                if (qrText.isNotBlank()) {
                    val file = QrEngine.saveQrToFile(context, qrText, "qr_${System.currentTimeMillis()}.png")
                    val saved = StorageManager.saveToPhoneStorage(context, file, specialName = "QR")
                    ConversionForegroundService.showCompletionNotification(
                        context = context,
                        title = "Zeidex Convert • رمز QR",
                        message = "تم حفظ رمز QR في:\n${saved.absolutePath}",
                        file = saved
                    )
                    Toast.makeText(context, "Saved to ${saved.absolutePath}", Toast.LENGTH_SHORT).show()

                    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", saved)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(Intent.createChooser(shareIntent, "Share QR Code"))
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = ZeidexOrange)
        ) {
            Icon(imageVector = Icons.Default.QrCode, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Save & Share QR Code", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun MotionPhotoToolView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFileItem by remember { mutableStateOf<FileItem?>(null) }
    var extractedVideo by remember { mutableStateOf<File?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var name = "motion_photo.jpg"
            var size = 0L
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
            selectedFileItem = FileItem(
                uri = uri,
                name = name,
                size = size,
                mimeType = "image/*",
                category = Category.IMAGE,
                ext = name.substringAfterLast('.', "jpg")
            )
            extractedVideo = null
            errorMessage = null
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            onClick = { picker.launch(arrayOf("*/*")) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Movie,
                    contentDescription = null,
                    tint = ZeidexOrange,
                    modifier = Modifier.size(44.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = selectedFileItem?.name ?: stringResource(R.string.tool_motion),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Pixel & Samsung Motion Photo (XMP) or Apple Live Photo (.livp / HEIC / MOV)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (selectedFileItem != null) {
            Button(
                onClick = {
                    val item = selectedFileItem ?: return@Button
                    isProcessing = true
                    errorMessage = null
                    scope.launch {
                        try {
                            val outDir = StorageManager.getSubfolder(context, specialName = "LivePhotos")
                            val videoFile = withContext(Dispatchers.IO) {
                                MotionPhotoExtractor.extractMotionVideo(context, item, outDir)
                            }
                            extractedVideo = videoFile
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage ?: "Failed to extract video: No embedded video found."
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZeidexOrange)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Extract MP4 Video", fontWeight = FontWeight.Bold)
                }
            }
        }

        errorMessage?.let { err ->
            Text(text = err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        extractedVideo?.let { video ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = stringResource(R.string.live_photo_saved),
                        fontWeight = FontWeight.Bold,
                        color = SuccessGreen,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "${video.name} • ${video.length() / 1024} KB",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "المسار: ${video.absolutePath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", video)
                                val playIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "video/mp4")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(playIntent, "Play Video"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) {
                            Icon(imageVector = Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.play_video))
                        }

                        OutlinedButton(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", video)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "video/mp4"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Video"))
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = null, tint = ZeidexOrange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PdfToolView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedImages by remember { mutableStateOf<List<FileItem>>(emptyList()) }
    var mergedPdf by remember { mutableStateOf<File?>(null) }
    var isMerging by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            selectedImages = uris.map { uri ->
                var name = "image.jpg"
                var size = 0L
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cursor.moveToFirst()) {
                        if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                        if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                    }
                }
                FileItem(uri, name, size, "image/*", Category.IMAGE, name.substringAfterLast('.', "jpg"))
            }
            mergedPdf = null
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            onClick = { imagePicker.launch(arrayOf("image/*")) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.PictureAsPdf,
                    contentDescription = null,
                    tint = ZeidexOrange,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = if (selectedImages.isEmpty()) stringResource(R.string.tool_pdf_merge) else "${selectedImages.size} Images Selected",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Combine multiple images into one multi-page PDF document",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (selectedImages.isNotEmpty()) {
            Button(
                onClick = {
                    isMerging = true
                    scope.launch {
                        try {
                            val outDir = StorageManager.getSubfolder(context, Category.PDF)
                            val pdf = withContext(Dispatchers.IO) {
                                PdfConverter.imagesToPdf(context, selectedImages, outDir, "merged_${System.currentTimeMillis()}.pdf")
                            }
                            val saved = StorageManager.saveToPhoneStorage(context, pdf, Category.PDF)
                            ConversionForegroundService.showCompletionNotification(
                                context = context,
                                title = "Zeidex Convert • تم إنشاء ملف PDF",
                                message = "تم حفظ ملف PDF في:\n${saved.absolutePath}",
                                file = saved
                            )
                            mergedPdf = saved
                        } catch (e: Exception) {
                            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                        } finally {
                            isMerging = false
                        }
                    }
                },
                enabled = !isMerging,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZeidexOrange)
            ) {
                if (isMerging) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(imageVector = Icons.Default.PictureAsPdf, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Merge to PDF", fontWeight = FontWeight.Bold)
                }
            }
        }

        mergedPdf?.let { pdf ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("PDF Created Successfully!", fontWeight = FontWeight.Bold, color = SuccessGreen)
                    Text(pdf.name, style = MaterialTheme.typography.bodySmall)
                    Text("المسار: ${pdf.absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, "application/pdf")
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(viewIntent, "Open PDF"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) {
                            Text(stringResource(R.string.open_file))
                        }

                        OutlinedButton(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = "application/pdf"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share PDF"))
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = ZeidexOrange)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EnhanceToolView() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedItem by remember { mutableStateOf<FileItem?>(null) }
    var isVideo by remember { mutableStateOf(false) }

    // Video options
    var videoMode by remember { mutableStateOf(VideoEnhancer.RemuxMode.FAST) }

    // Image options
    var sharpen by remember { mutableStateOf(true) }
    var autoContrast by remember { mutableStateOf(true) }
    var upscale2x by remember { mutableStateOf(false) }
    var grayscale by remember { mutableStateOf(false) }
    var targetImgExt by remember { mutableStateOf("jpg") }
    var quality by remember { mutableFloatStateOf(90f) }
    // Crop and size presets from the Home screen's power tools, in the same terms: the crop is
    // centred, and a size preset either caps the width or halves both sides.
    var cropRatio by remember { mutableStateOf<String?>(null) }
    var sizePreset by remember { mutableStateOf("original") }

    var processedFile by remember { mutableStateOf<File?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            var name = "media_file"
            var size = 0L
            var mime: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIdx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIdx != -1) name = cursor.getString(nameIdx) ?: name
                    if (sizeIdx != -1) size = cursor.getLong(sizeIdx)
                }
            }
            mime = context.contentResolver.getType(uri) ?: ""
            val ext = name.substringAfterLast('.', "")
            val cat = when {
                mime.startsWith("video/") || ext.lowercase() in listOf("mp4", "mkv", "mov", "avi", "3gp", "webm") -> Category.VIDEO
                else -> Category.IMAGE
            }
            isVideo = (cat == Category.VIDEO)
            selectedItem = FileItem(uri, name, size, mime, cat, ext)
            processedFile = null
            errorMessage = null
            progress = 0f
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            onClick = { picker.launch(arrayOf("image/*", "video/*", "*/*")) }
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tune,
                    contentDescription = null,
                    tint = ZeidexOrange,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = selectedItem?.name ?: stringResource(R.string.tool_enhance),
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = if (isVideo) stringResource(R.string.video_enhance_title) else stringResource(R.string.image_enhance_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (selectedItem != null) {
            if (isVideo) {
                // Video Enhancement Controls
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.video_enhance_title), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = videoMode == VideoEnhancer.RemuxMode.FAST,
                            onClick = { videoMode = VideoEnhancer.RemuxMode.FAST },
                            label = { Text(stringResource(R.string.video_remux_fast)) }
                        )
                        FilterChip(
                            selected = videoMode == VideoEnhancer.RemuxMode.STRIP_METADATA,
                            onClick = { videoMode = VideoEnhancer.RemuxMode.STRIP_METADATA },
                            label = { Text(stringResource(R.string.video_strip_metadata)) }
                        )
                    }
                    Text(
                        text = stringResource(R.string.video_remux_note),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // Image Enhancement Controls
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.image_enhance_title), fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = sharpen,
                            onClick = { sharpen = !sharpen },
                            label = { Text(stringResource(R.string.sharpen_details)) }
                        )
                        FilterChip(
                            selected = autoContrast,
                            onClick = { autoContrast = !autoContrast },
                            label = { Text(stringResource(R.string.auto_contrast)) }
                        )
                        FilterChip(
                            selected = upscale2x,
                            onClick = { upscale2x = !upscale2x },
                            label = { Text(stringResource(R.string.upscale_2x)) }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = grayscale,
                            onClick = { grayscale = !grayscale },
                            label = { Text(stringResource(R.string.tool_grayscale)) }
                        )

                        listOf("jpg", "png", "webp", "bmp").forEach { fmt ->
                            FilterChip(
                                selected = targetImgExt == fmt,
                                onClick = { targetImgExt = fmt },
                                label = { Text(fmt.uppercase()) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.tool_crop_ratio),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            null to stringResource(R.string.crop_original),
                            "1:1" to stringResource(R.string.crop_square),
                            "16:9" to stringResource(R.string.crop_16_9),
                            "4:3" to stringResource(R.string.crop_4_3)
                        ).forEach { (ratio, label) ->
                            FilterChip(
                                selected = cropRatio == ratio,
                                onClick = { cropRatio = ratio },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Text(
                        text = stringResource(R.string.tool_scale),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            "original" to stringResource(R.string.res_original),
                            "1920" to stringResource(R.string.res_1920px),
                            "1080" to stringResource(R.string.res_1080px),
                            "half" to stringResource(R.string.scale_half)
                        ).forEach { (preset, label) ->
                            FilterChip(
                                selected = sizePreset == preset,
                                onClick = { sizePreset = preset },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }

                    Column {
                        Text("Quality: ${quality.toInt()}%", style = MaterialTheme.typography.bodySmall)
                        Slider(
                            value = quality,
                            onValueChange = { quality = it },
                            valueRange = 50f..100f
                        )
                    }
                }
            }

            if (isProcessing) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = ZeidexOrange
                )
            }

            Button(
                onClick = {
                    val item = selectedItem ?: return@Button
                    isProcessing = true
                    errorMessage = null
                    scope.launch {
                        try {
                            if (isVideo) {
                                val outDir = StorageManager.getSubfolder(context, Category.VIDEO)
                                val res = withContext(Dispatchers.IO) {
                                    VideoEnhancer.remuxVideo(
                                        context = context,
                                        input = item,
                                        outputDir = outDir,
                                        mode = videoMode
                                    ) { p -> progress = p }
                                }
                                processedFile = res
                            } else {
                                val outDir = StorageManager.getSubfolder(context, Category.IMAGE)
                                val res = withContext(Dispatchers.IO) {
                                    ImageConverter.convertImage(
                                        context = context,
                                        input = item,
                                        targetExt = targetImgExt,
                                        outputDir = outDir,
                                        quality = quality.toInt(),
                                        grayscale = grayscale,
                                        sharpen = sharpen,
                                        autoEnhance = autoContrast,
                                        upscale2x = upscale2x,
                                        maxWidth = if (sizePreset == "original" || sizePreset == "half") null else sizePreset.toInt(),
                                        scalePercent = if (sizePreset == "half") 50 else null,
                                        cropRatio = cropRatio,
                                        stripMetadata = true
                                    )
                                }
                                processedFile = res
                            }
                        } catch (e: Exception) {
                            errorMessage = e.localizedMessage ?: "Enhancement failed: ${e.message}"
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                enabled = !isProcessing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = ZeidexOrange)
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(24.dp))
                } else {
                    Icon(imageVector = Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.enhance_button), fontWeight = FontWeight.Bold)
                }
            }
        }

        errorMessage?.let { err ->
            Text(text = err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        processedFile?.let { file ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SuccessGreen.copy(alpha = 0.15f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Enhancement Complete!", fontWeight = FontWeight.Bold, color = SuccessGreen)
                    Text("${file.name} • ${file.length() / 1024} KB", style = MaterialTheme.typography.bodySmall)
                    Text("المسار: ${file.absolutePath}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val mime = if (isVideo) "video/mp4" else "image/*"
                                val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                                    setDataAndType(uri, mime)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(Intent.createChooser(viewIntent, "Open Media"))
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                        ) {
                            Text(stringResource(R.string.open_file))
                        }

                        OutlinedButton(
                            onClick = {
                                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                                val mime = if (isVideo) "video/mp4" else "image/*"
                                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                    type = mime
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(Intent.createChooser(shareIntent, "Share Media"))
                            },
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Share, contentDescription = "Share", tint = ZeidexOrange)
                        }
                    }
                }
            }
        }
    }
}
