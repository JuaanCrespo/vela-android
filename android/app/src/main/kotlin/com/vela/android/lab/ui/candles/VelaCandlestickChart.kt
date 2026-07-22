package com.vela.android.lab.ui.candles

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

private val ChartBackground = Color(0xFF061421)
private val ChartGrid = Color(0xFF17384A)
private val ChartAxis = Color(0xFF87AFC0)
private val ChartMint = Color(0xFF2DE2B7)
private val ChartBearish = Color(0xFFD76A76)
private val ChartDoji = Color(0xFFFFD7AC)
private val ChartSelection = Color(0x553DB8FF)

/** Lightweight, read-only one-minute OHLC chart. Tap only changes the selected detail row. */
@Composable
fun VelaCandlestickChart(
    candles: List<CandleUiModel>,
    selectedCandle: CandleUiModel?,
    useUtc: Boolean,
    onCandleSelected: (CandleUiModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val priceAxisWidthPx = with(density) { 58.dp.toPx() }
    val plotLeftPx = with(density) { 10.dp.toPx() }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(310.dp)
            .background(ChartBackground, RoundedCornerShape(12.dp))
            .onSizeChanged { canvasSize = it }
            .pointerInput(candles, canvasSize) {
                detectTapGestures { tap ->
                    if (candles.isEmpty()) return@detectTapGestures
                    val plotWidth = canvasSize.width - plotLeftPx - priceAxisWidthPx
                    if (plotWidth <= 0f || tap.x !in plotLeftPx..(plotLeftPx + plotWidth)) {
                        return@detectTapGestures
                    }
                    val index = (((tap.x - plotLeftPx) / plotWidth) * candles.size)
                        .toInt()
                        .coerceIn(candles.indices)
                    onCandleSelected(candles[index])
                }
            }
            .semantics {
                contentDescription =
                    "Gráfico de ${candles.size} velas de un minuto. Solo lectura."
            },
    ) {
        if (candles.isEmpty()) return@Canvas
        drawCandlestickChart(
            candles = candles,
            selectedCandle = selectedCandle,
            useUtc = useUtc,
        )
    }
}

private fun DrawScope.drawCandlestickChart(
    candles: List<CandleUiModel>,
    selectedCandle: CandleUiModel?,
    useUtc: Boolean,
) {
    val plotLeft = 10.dp.toPx()
    val plotTop = 14.dp.toPx()
    val plotRight = size.width - 58.dp.toPx()
    val plotBottom = size.height - 30.dp.toPx()
    val plotWidth = (plotRight - plotLeft).coerceAtLeast(1f)
    val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
    val rawMin = candles.minOf(CandleUiModel::low)
    val rawMax = candles.maxOf(CandleUiModel::high)
    val rawRange = rawMax - rawMin
    val padding = if (rawRange > 0.0) rawRange * 0.06 else rawMax * 0.005
    val minPrice = rawMin - padding
    val maxPrice = rawMax + padding
    val priceRange = (maxPrice - minPrice).takeIf { it > 0.0 } ?: 1.0
    val slotWidth = plotWidth / candles.size
    val bodyWidth = (slotWidth * 0.58f).coerceIn(1.5.dp.toPx(), 10.dp.toPx())

    fun priceY(price: Double): Float =
        plotBottom - (((price - minPrice) / priceRange).toFloat() * plotHeight)

    val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ChartAxis.toArgb()
        textSize = 10.sp.toPx()
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.MONOSPACE,
            android.graphics.Typeface.NORMAL,
        )
    }

    repeat(5) { index ->
        val fraction = index / 4f
        val y = plotTop + (plotHeight * fraction)
        drawLine(ChartGrid, Offset(plotLeft, y), Offset(plotRight, y), strokeWidth = 1f)
        val price = maxPrice - (priceRange * fraction)
        axisPaint.textAlign = Paint.Align.LEFT
        drawContext.canvas.nativeCanvas.drawText(
            String.format(Locale.US, "%.2f", price),
            plotRight + 5.dp.toPx(),
            y + 3.dp.toPx(),
            axisPaint,
        )
    }
    repeat(5) { index ->
        val x = plotLeft + (plotWidth * index / 4f)
        drawLine(ChartGrid, Offset(x, plotTop), Offset(x, plotBottom), strokeWidth = 1f)
    }

    candles.forEachIndexed { index, candle ->
        val centerX = plotLeft + slotWidth * (index + 0.5f)
        val color = when (candle.direction) {
            CandleDirection.BULLISH -> ChartMint
            CandleDirection.BEARISH -> ChartBearish
            CandleDirection.DOJI -> ChartDoji
        }
        if (selectedCandle?.stableId == candle.stableId) {
            drawRect(
                color = ChartSelection,
                topLeft = Offset(centerX - slotWidth / 2f, plotTop),
                size = Size(slotWidth, plotHeight),
            )
        }
        drawLine(
            color = color,
            start = Offset(centerX, priceY(candle.high)),
            end = Offset(centerX, priceY(candle.low)),
            strokeWidth = 1.dp.toPx(),
        )
        val openY = priceY(candle.open)
        val closeY = priceY(candle.close)
        val bodyTop = minOf(openY, closeY)
        val bodyHeight = abs(closeY - openY)
        if (bodyHeight < 1.dp.toPx()) {
            drawLine(
                color = color,
                start = Offset(centerX - bodyWidth / 2f, bodyTop),
                end = Offset(centerX + bodyWidth / 2f, bodyTop),
                strokeWidth = 1.5.dp.toPx(),
            )
        } else {
            drawRect(
                color = color,
                topLeft = Offset(centerX - bodyWidth / 2f, bodyTop),
                size = Size(bodyWidth, bodyHeight.coerceAtLeast(1f)),
            )
        }
    }

    val lastPrice = candles.last().close
    val lastY = priceY(lastPrice)
    drawLine(
        color = ChartDoji,
        start = Offset(plotLeft, lastY),
        end = Offset(plotRight, lastY),
        strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(7f, 5f)),
    )
    axisPaint.color = ChartDoji.toArgb()
    axisPaint.textAlign = Paint.Align.LEFT
    drawContext.canvas.nativeCanvas.drawText(
        String.format(Locale.US, "%.2f", lastPrice),
        plotRight + 5.dp.toPx(),
        (lastY - 4.dp.toPx()).coerceAtLeast(plotTop + axisPaint.textSize),
        axisPaint,
    )

    val zone = if (useUtc) ZoneOffset.UTC else ZoneId.systemDefault()
    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US).withZone(zone)
    val labelIndices = listOf(0, candles.lastIndex / 2, candles.lastIndex).distinct()
    axisPaint.color = ChartAxis.toArgb()
    labelIndices.forEach { index ->
        val x = plotLeft + slotWidth * (index + 0.5f)
        axisPaint.textAlign = when (index) {
            0 -> Paint.Align.LEFT
            candles.lastIndex -> Paint.Align.RIGHT
            else -> Paint.Align.CENTER
        }
        drawContext.canvas.nativeCanvas.drawText(
            formatter.format(candles[index].timestamp),
            x.coerceIn(plotLeft, plotRight),
            size.height - 9.dp.toPx(),
            axisPaint,
        )
    }
}
