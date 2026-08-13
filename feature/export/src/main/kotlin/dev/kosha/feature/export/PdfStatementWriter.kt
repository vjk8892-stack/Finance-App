package dev.kosha.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.kosha.core.common.Money
import dev.kosha.core.common.Period
import java.io.File
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PDF statement (spec G10): A4 portrait, three pages —
 *  1. period header + Pulse summary + weather sentence
 *  2. category table (budgeted vs actual vs delta) + top merchants
 *  3. trend chart bitmap + recurring list + on-device footer
 *
 * Uses Android's native PdfDocument (spec B1: no PDF SDK licensing or size).
 * Vault data is structurally absent — this writer is never handed any.
 */
@Singleton
class PdfStatementWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class CategoryLine(val name: String, val budgeted: Money?, val actual: Money)

    data class Statement(
        val period: Period,
        val weatherSentence: String,
        val income: Money,
        val expense: Money,
        val savingsGap: Money,
        val healthScore: Int?,
        val categories: List<CategoryLine>,
        val topMerchants: List<Pair<String, Money>>,
        val recurring: List<Pair<String, Money>>,
        /** Rendered at 2x for print sharpness (spec G10). */
        val trendChart: Bitmap?,
    )

    fun write(statement: Statement, outputFile: File): File {
        val document = PdfDocument()
        try {
            drawSummaryPage(document, statement)
            drawCategoryPage(document, statement)
            drawTrendPage(document, statement)
            outputFile.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return outputFile
    }

    private fun drawSummaryPage(document: PdfDocument, statement: Statement) {
        val page = document.startPage(pageInfo(1))
        val canvas = page.canvas
        var y = MARGIN + 40f

        canvas.drawText("Kosha statement", MARGIN, y, titlePaint)
        y += 28f
        val format = DateTimeFormatter.ofPattern("d MMM yyyy")
        canvas.drawText(
            "${format.format(statement.period.start)} — ${format.format(statement.period.endInclusive)}",
            MARGIN, y, mutedPaint,
        )
        y += 44f

        canvas.drawText(statement.weatherSentence, MARGIN, y, serifPaint)
        y += 48f

        drawAmountBlock(canvas, "Income", statement.income, MARGIN, y)
        drawAmountBlock(canvas, "Spent", statement.expense, MARGIN + 170f, y)
        drawAmountBlock(canvas, "Savings gap", statement.savingsGap, MARGIN + 340f, y)
        y += 70f

        statement.healthScore?.let { score ->
            canvas.drawText("Financial health", MARGIN, y, labelPaint)
            y += 26f
            canvas.drawText(score.toString(), MARGIN, y, bigNumberPaint)
            y += 10f
        }

        document.finishPage(page)
    }

    private fun drawCategoryPage(document: PdfDocument, statement: Statement) {
        val page = document.startPage(pageInfo(2))
        val canvas = page.canvas
        var y = MARGIN + 40f

        canvas.drawText("Where it went", MARGIN, y, titlePaint)
        y += 34f

        canvas.drawText("Category", MARGIN, y, labelPaint)
        canvas.drawText("Budget", MARGIN + 260f, y, labelPaint)
        canvas.drawText("Actual", MARGIN + 350f, y, labelPaint)
        canvas.drawText("Difference", MARGIN + 440f, y, labelPaint)
        y += 8f
        canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
        y += 22f

        statement.categories.forEach { line ->
            canvas.drawText(line.name, MARGIN, y, bodyPaint)
            canvas.drawText(
                line.budgeted?.format(withPaise = false) ?: "—",
                MARGIN + 260f, y, tabularPaint,
            )
            canvas.drawText(line.actual.format(withPaise = false), MARGIN + 350f, y, tabularPaint)

            val delta = line.budgeted?.let { it - line.actual }
            // Amber highlight only — never red, even on paper (spec A2/G10).
            val paint = if (delta != null && delta.isNegative) amberTabularPaint else tabularPaint
            canvas.drawText(delta?.format(withPaise = false, signed = true) ?: "—", MARGIN + 440f, y, paint)
            y += 20f
            if (y > PAGE_HEIGHT - MARGIN - 120f) return@forEach
        }

        y += 26f
        canvas.drawText("Top merchants", MARGIN, y, labelPaint)
        y += 22f
        statement.topMerchants.take(10).forEach { (merchant, amount) ->
            canvas.drawText(merchant, MARGIN, y, bodyPaint)
            canvas.drawText(amount.format(withPaise = false), MARGIN + 350f, y, tabularPaint)
            y += 20f
        }

        document.finishPage(page)
    }

    private fun drawTrendPage(document: PdfDocument, statement: Statement) {
        val page = document.startPage(pageInfo(3))
        val canvas = page.canvas
        var y = MARGIN + 40f

        canvas.drawText("Trajectory", MARGIN, y, titlePaint)
        y += 24f

        statement.trendChart?.let { bitmap ->
            val targetWidth = PAGE_WIDTH - MARGIN * 2
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                targetWidth.toInt(),
                (bitmap.height * targetWidth / bitmap.width).toInt(),
                true,
            )
            canvas.drawBitmap(scaled, MARGIN, y, null)
            y += scaled.height + 30f
        }

        if (statement.recurring.isNotEmpty()) {
            canvas.drawText("Recurring & EMIs", MARGIN, y, labelPaint)
            y += 22f
            statement.recurring.forEach { (label, amount) ->
                canvas.drawText(label, MARGIN, y, bodyPaint)
                canvas.drawText(amount.format(withPaise = false), MARGIN + 350f, y, tabularPaint)
                y += 20f
            }
        }

        canvas.drawText(
            "Generated on-device by Kosha — data never left this phone.",
            MARGIN,
            PAGE_HEIGHT - MARGIN,
            footerPaint,
        )
        document.finishPage(page)
    }

    private fun drawAmountBlock(canvas: Canvas, label: String, amount: Money, x: Float, y: Float) {
        canvas.drawText(label, x, y, labelPaint)
        canvas.drawText(amount.format(withPaise = false), x, y + 26f, amountPaint)
    }

    private fun pageInfo(number: Int) =
        PdfDocument.PageInfo.Builder(PAGE_WIDTH.toInt(), PAGE_HEIGHT.toInt(), number).create()

    private companion object {
        // A4 at 72dpi.
        const val PAGE_WIDTH = 595f
        const val PAGE_HEIGHT = 842f
        const val MARGIN = 48f

        val titlePaint = Paint().apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
        val serifPaint = Paint().apply {
            color = Color.DKGRAY
            textSize = 14f
            typeface = Typeface.SERIF
            isAntiAlias = true
        }
        val labelPaint = Paint().apply {
            color = Color.GRAY
            textSize = 10f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
        val bodyPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val tabularPaint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val amberTabularPaint = Paint().apply {
            color = Color.rgb(0xD9, 0x77, 0x06)
            textSize = 11f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val amountPaint = Paint().apply {
            color = Color.BLACK
            textSize = 18f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val bigNumberPaint = Paint().apply {
            color = Color.BLACK
            textSize = 32f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val mutedPaint = Paint().apply {
            color = Color.GRAY
            textSize = 12f
            isAntiAlias = true
        }
        val footerPaint = Paint().apply {
            color = Color.GRAY
            textSize = 9f
            isAntiAlias = true
        }
        val rulePaint = Paint().apply {
            color = Color.LTGRAY
            strokeWidth = 1f
        }
    }
}
