package com.zeidex.convert.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.zeidex.convert.model.Format
import com.zeidex.convert.ui.theme.ZeidexOrange
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun RadialWheel(
    formats: List<Format>,
    onFormatSelected: (Format) -> Unit,
    modifier: Modifier = Modifier,
    centerLabel: String = "Zeidex"
) {
    if (formats.isEmpty()) return

    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceColor = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurface

    var selectedIndex by remember { mutableStateOf<Int?>(null) }
    val scaleAnim = remember { Animatable(0.9f) }

    LaunchedEffect(Unit) {
        scaleAnim.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy)
        )
    }

    Box(
        modifier = modifier
            .size(320.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(formats) {
                    detectTapGestures { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        val dist = sqrt((dx * dx + dy * dy).toDouble())

                        val outerRadius = size.width / 2f
                        val innerRadius = outerRadius * 0.42f

                        if (dist in innerRadius..outerRadius) {
                            var angle = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI
                            if (angle < 0) angle += 360.0
                            val sweepAngle = 360.0 / formats.size
                            val index = (angle / sweepAngle).toInt() % formats.size
                            selectedIndex = index
                            onFormatSelected(formats[index])
                        }
                    }
                }
                .pointerInput(formats) {
                    detectDragGestures(
                        onDragEnd = {
                            selectedIndex?.let { idx ->
                                if (idx in formats.indices) onFormatSelected(formats[idx])
                            }
                            selectedIndex = null
                        }
                    ) { change, _ ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = change.position.x - center.x
                        val dy = change.position.y - center.y
                        val dist = sqrt((dx * dx + dy * dy).toDouble())

                        val outerRadius = size.width / 2f
                        val innerRadius = outerRadius * 0.42f

                        if (dist in innerRadius..outerRadius) {
                            var angle = atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI
                            if (angle < 0) angle += 360.0
                            val sweepAngle = 360.0 / formats.size
                            selectedIndex = (angle / sweepAngle).toInt() % formats.size
                        } else {
                            selectedIndex = null
                        }
                    }
                }
        ) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val outerRadius = (size.width / 2f) * scaleAnim.value
            val innerRadius = outerRadius * 0.44f
            val count = formats.size
            val sweep = 360f / count

            for (i in 0 until count) {
                val startAngle = i * sweep
                val isSelected = selectedIndex == i

                val sliceColor = when {
                    isSelected -> primaryColor
                    i % 2 == 0 -> surfaceColor
                    else -> surfaceColor.copy(alpha = 0.85f)
                }

                // Draw sector arc
                drawArc(
                    color = sliceColor,
                    startAngle = startAngle,
                    sweepAngle = sweep - 1.5f,
                    useCenter = false,
                    topLeft = Offset(center.x - outerRadius, center.y - outerRadius),
                    size = Size(outerRadius * 2, outerRadius * 2),
                    style = Stroke(width = outerRadius - innerRadius, cap = StrokeCap.Butt)
                )

                // Draw format text in the sector
                val midAngleRad = (startAngle + sweep / 2f) * PI / 180.0
                val textRadius = (innerRadius + outerRadius) / 2f
                val tx = (center.x + textRadius * cos(midAngleRad)).toFloat()
                val ty = (center.y + textRadius * sin(midAngleRad)).toFloat()

                val textPaint = android.graphics.Paint().apply {
                    color = if (isSelected) android.graphics.Color.WHITE else android.graphics.Color.DKGRAY
                    textSize = 34f
                    textAlign = android.graphics.Paint.Align.CENTER
                    isFakeBoldText = true
                    isAntiAlias = true
                }
                drawContext.canvas.nativeCanvas.drawText(
                    formats[i].label,
                    tx,
                    ty + 12f,
                    textPaint
                )
            }

            // Draw center hub (The Orange Orb)
            drawCircle(
                color = ZeidexOrange,
                radius = innerRadius * 0.92f,
                center = center
            )

            // Draw hub label
            val hubPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 36f
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
                isAntiAlias = true
            }
            drawContext.canvas.nativeCanvas.drawText(
                centerLabel,
                center.x,
                center.y + 12f,
                hubPaint
            )
        }
    }
}
