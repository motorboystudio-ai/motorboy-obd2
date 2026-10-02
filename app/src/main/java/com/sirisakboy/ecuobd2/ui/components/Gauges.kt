package com.sirisakboy.ecuobd2.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sirisakboy.ecuobd2.ui.theme.*
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun CircularArcGauge(
    title: String,
    value: Float,
    unit: String,
    min: Float,
    max: Float,
    redlineStart: Float? = null,
    amberStart: Float? = null,
    modifier: Modifier = Modifier,
    majorTickCount: Int = 5,
    minorTicksPerMajor: Int = 2,
    accentColor: Color = AccentCyan,
    precision: Int = 0,
    testTag: String = "gauge_$title"
) {
    val animatedValue by animateFloatAsState(
        targetValue = value.coerceIn(min, max),
        animationSpec = tween(durationMillis = 200),
        label = "gaugeValue"
    )

    Box(
        modifier = modifier
            .testTag(testTag)
            .background(DarkCard, RoundedCornerShape(16.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(16.dp))
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )

            Spacer(modifier = Modifier.height(4.dp))

            Box(
                modifier = Modifier
                    .size(150.dp)
                    .padding(6.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawGaugeArcsAndTicks(
                        animatedValue = animatedValue,
                        min = min,
                        max = max,
                        redlineStart = redlineStart,
                        amberStart = amberStart,
                        accentColor = accentColor,
                        majorTickCount = majorTickCount,
                        minorTicksPerMajor = minorTicksPerMajor
                    )
                }

                // Digital Readout at center
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    val formattedVal = if (precision == 0) {
                        animatedValue.toInt().toString()
                    } else {
                        "%.${precision}f".format(animatedValue)
                    }

                    Text(
                        text = formattedVal,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = when {
                            redlineStart != null && animatedValue >= redlineStart -> DangerRed
                            amberStart != null && animatedValue >= amberStart -> WarnAmber
                            else -> TextPrimary
                        }
                    )
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawGaugeArcsAndTicks(
    animatedValue: Float,
    min: Float,
    max: Float,
    redlineStart: Float?,
    amberStart: Float?,
    accentColor: Color,
    majorTickCount: Int,
    minorTicksPerMajor: Int
) {
    val strokeWidth = 10.dp.toPx()
    val padding = strokeWidth / 2f + 4.dp.toPx()
    val arcSize = Size(size.width - padding * 2, size.height - padding * 2)
    val topLeft = Offset(padding, padding)

    val startAngle = 140f
    val sweepTotal = 260f

    // 1. Background Track
    drawArc(
        color = GaugeTrack,
        startAngle = startAngle,
        sweepAngle = sweepTotal,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
    )

    // 2. Redline Zone Background Arc
    if (redlineStart != null && redlineStart < max) {
        val redlinePct = (redlineStart - min) / (max - min)
        val redlineAngle = startAngle + sweepTotal * redlinePct
        val redlineSweep = sweepTotal * (1f - redlinePct)
        drawArc(
            color = DangerRed.copy(alpha = 0.35f),
            startAngle = redlineAngle,
            sweepAngle = redlineSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }

    // 3. Active Value Arc
    val valuePct = ((animatedValue - min) / (max - min)).coerceIn(0f, 1f)
    val activeSweep = sweepTotal * valuePct

    val activeColor = when {
        redlineStart != null && animatedValue >= redlineStart -> DangerRed
        amberStart != null && animatedValue >= amberStart -> WarnAmber
        else -> accentColor
    }

    if (activeSweep > 0.5f) {
        drawArc(
            brush = Brush.sweepGradient(
                colors = listOf(accentColor, activeColor)
            ),
            startAngle = startAngle,
            sweepAngle = activeSweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
        )
    }

    // 4. Tick Marks
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = (size.width - padding * 2) / 2f

    val totalTicks = (majorTickCount - 1) * minorTicksPerMajor
    for (i in 0..totalTicks) {
        val frac = i.toFloat() / totalTicks
        val tickAngleDeg = startAngle + sweepTotal * frac
        val tickAngleRad = Math.toRadians(tickAngleDeg.toDouble())

        val isMajor = i % minorTicksPerMajor == 0
        val tickLen = if (isMajor) 9.dp.toPx() else 4.dp.toPx()
        val tickColor = if (isMajor) TickMajorColor else TickColor

        val outerRadius = radius - strokeWidth / 2f - 2.dp.toPx()
        val innerRadius = outerRadius - tickLen

        val startX = center.x + outerRadius * cos(tickAngleRad).toFloat()
        val startY = center.y + outerRadius * sin(tickAngleRad).toFloat()
        val endX = center.x + innerRadius * cos(tickAngleRad).toFloat()
        val endY = center.y + innerRadius * sin(tickAngleRad).toFloat()

        drawLine(
            color = tickColor,
            start = Offset(startX, startY),
            end = Offset(endX, endY),
            strokeWidth = if (isMajor) 2.dp.toPx() else 1.dp.toPx(),
            cap = StrokeCap.Round
        )
    }

    // 5. Needle pointer dot
    val needleAngleRad = Math.toRadians((startAngle + activeSweep).toDouble())
    val needleRadius = radius
    val needleX = center.x + needleRadius * cos(needleAngleRad).toFloat()
    val needleY = center.y + needleRadius * sin(needleAngleRad).toFloat()

    drawCircle(
        color = activeColor,
        radius = 5.dp.toPx(),
        center = Offset(needleX, needleY)
    )
    drawCircle(
        color = TextPrimary,
        radius = 2.dp.toPx(),
        center = Offset(needleX, needleY)
    )
}

@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    icon: ImageVector? = null,
    accentColor: Color = AccentCyan,
    statusText: String? = null,
    modifier: Modifier = Modifier,
    testTag: String = "tile_$label"
) {
    Box(
        modifier = modifier
            .testTag(testTag)
            .background(DarkCard, RoundedCornerShape(12.dp))
            .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontWeight = FontWeight.SemiBold
                )
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = label,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                if (unit.isNotEmpty()) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        modifier = Modifier.padding(bottom = 2.dp)
                    )
                }
            }

            if (statusText != null) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = statusText,
                    fontSize = 10.sp,
                    color = accentColor,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}
