package dev.kosha.feature.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
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
    data class CategoryLine(
        val name: String,
        val budgeted: Money?,
        val actual: Money,
        /** Blank when the export covers a single month. */
        val month: String = "",
    )

    /** One month of the trend chart: bar height, and the line to compare it to. */
    data class TrendBar(val label: String, val spent: Money, val budget: Money?)

    /** One transaction line of the full ledger section. */
    data class LedgerLine(
        val date: String,
        val merchant: String,
        val category: String,
        val account: String,
        val amount: Money,
        val isCredit: Boolean,
    )

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
        /** Monthly spend against the budget line, drawn as vector, not a bitmap. */
        val trendBars: List<TrendBar> = emptyList(),
        val ledger: List<LedgerLine> = emptyList(),
        val options: PdfOptions = PdfOptions(),
        val rangeLabel: String = "",
    )

    fun write(statement: Statement, outputFile: File): File {
        val document = PdfDocument()
        try {
            // Pages are emitted only for the sections that were asked for, and
            // numbered as they are emitted — a statement with page 3 and no
            // page 2 looks like something failed to print.
            val pageNumber = PageCounter()
            if (statement.options.summary) drawSummaryPage(document, statement, pageNumber)
            if (statement.options.categoryTable || statement.options.pieChart ||
                statement.options.topMerchants
            ) {
                drawCategoryPage(document, statement, pageNumber)
            }
            if (statement.options.trendChart || statement.options.recurring) {
                drawTrendPage(document, statement, pageNumber)
            }
            if (statement.options.fullLedger) drawLedgerPages(document, statement, pageNumber)
            // A PDF with no pages cannot be opened at all, so never write one.
            if (pageNumber.used == 0) drawSummaryPage(document, statement, pageNumber)
            outputFile.outputStream().use { document.writeTo(it) }
        } finally {
            document.close()
        }
        return outputFile
    }

    private class PageCounter {
        var used = 0
        fun next(): Int = ++used
    }

    private fun drawSummaryPage(document: PdfDocument, statement: Statement, pages: PageCounter) {
        val page = document.startPage(pageInfo(pages.next()))
        val canvas = page.canvas
        var y = MARGIN + 40f

        canvas.drawText("Kosha statement", MARGIN, y, titlePaint)
        y += 28f
        val format = DateTimeFormatter.ofPattern("d MMM yyyy")
        canvas.drawText(
            statement.rangeLabel.ifBlank {
                "${format.format(statement.period.start)} — ${format.format(statement.period.endInclusive)}"
            },
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

    private fun drawCategoryPage(document: PdfDocument, statement: Statement, pages: PageCounter) {
        val page = document.startPage(pageInfo(pages.next()))
        val canvas = page.canvas
        var y = MARGIN + 40f

        canvas.drawText("Where it went", MARGIN, y, titlePaint)
        y += 34f

        if (statement.options.pieChart && statement.categories.isNotEmpty()) {
            y = drawPie(canvas, statement.categories, y)
            y += 24f
        }

        if (statement.options.categoryTable) {
            // The Month column only earns its width when the export spans more
            // than one month; otherwise every row would repeat the same value.
            val withMonth = statement.options.monthColumn &&
                statement.categories.any { it.month.isNotBlank() }
            val nameX = MARGIN
            val monthX = MARGIN + 200f
            val budgetX = if (withMonth) MARGIN + 275f else MARGIN + 260f
            val actualX = if (withMonth) MARGIN + 365f else MARGIN + 350f
            val deltaX = if (withMonth) MARGIN + 450f else MARGIN + 440f

            canvas.drawText("Category", nameX, y, labelPaint)
            if (withMonth) canvas.drawText("Month", monthX, y, labelPaint)
            canvas.drawText("Budget", budgetX, y, labelPaint)
            canvas.drawText("Actual", actualX, y, labelPaint)
            canvas.drawText("Difference", deltaX, y, labelPaint)
            y += 8f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
            y += 22f

            for (line in statement.categories) {
                if (y > PAGE_HEIGHT - MARGIN - 120f) break
                canvas.drawText(line.name.take(28), nameX, y, bodyPaint)
                if (withMonth) canvas.drawText(line.month, monthX, y, bodyPaint)
                canvas.drawText(
                    line.budgeted?.format(withPaise = false) ?: "—",
                    budgetX, y, tabularPaint,
                )
                canvas.drawText(line.actual.format(withPaise = false), actualX, y, tabularPaint)

                val delta = line.budgeted?.let { it - line.actual }
                // Amber highlight only — never red, even on paper (spec A2/G10).
                val paint = if (delta != null && delta.isNegative) amberTabularPaint else tabularPaint
                canvas.drawText(delta?.format(withPaise = false, signed = true) ?: "—", deltaX, y, paint)
                y += 20f
            }
        }

        if (statement.options.topMerchants && statement.topMerchants.isNotEmpty()) {
            y += 26f
            canvas.drawText("Top merchants", MARGIN, y, labelPaint)
            y += 22f
            for ((merchant, amount) in statement.topMerchants.take(10)) {
                if (y > PAGE_HEIGHT - MARGIN) break
                canvas.drawText(merchant.take(40), MARGIN, y, bodyPaint)
                canvas.drawText(amount.format(withPaise = false), MARGIN + 350f, y, tabularPaint)
                y += 20f
            }
        }

        document.finishPage(page)
    }

    /**
     * Category shares as a pie, with a legend carrying the figures.
     *
     * A pie alone cannot be read to the rupee — that is what the table under it
     * is for — so every slice is labelled with its share and its amount rather
     * than relying on the reader to judge angles. Greyscale-safe: the wedges
     * alternate through distinct lightnesses, so this survives a black-and-white
     * printer, which is where statements usually end up.
     */
    private fun drawPie(canvas: Canvas, categories: List<CategoryLine>, top: Float): Float {
        val slices = categories
            .filter { it.actual.paise > 0 }
            .sortedByDescending { it.actual.paise }
            .take(PIE_SLICES)
        if (slices.isEmpty()) return top

        val total = slices.sumOf { it.actual.paise }.toFloat().coerceAtLeast(1f)
        val radius = 78f
        val centreX = MARGIN + radius
        val centreY = top + radius
        val bounds = RectF(centreX - radius, centreY - radius, centreX + radius, centreY + radius)

        var startAngle = -90f
        slices.forEachIndexed { index, slice ->
            val sweep = 360f * (slice.actual.paise / total)
            canvas.drawArc(bounds, startAngle, sweep, true, piePaint(index))
            canvas.drawArc(bounds, startAngle, sweep, true, pieOutlinePaint)
            startAngle += sweep
        }

        var legendY = top + 10f
        val legendX = centreX + radius + 24f
        slices.forEachIndexed { index, slice ->
            canvas.drawRect(legendX, legendY - 8f, legendX + 10f, legendY + 2f, piePaint(index))
            canvas.drawRect(legendX, legendY - 8f, legendX + 10f, legendY + 2f, pieOutlinePaint)
            val share = (100f * slice.actual.paise / total)
            canvas.drawText(slice.name.take(22), legendX + 16f, legendY, bodyPaint)
            canvas.drawText(
                "%.0f%%  %s".format(share, slice.actual.format(withPaise = false)),
                legendX + 190f, legendY, tabularPaint,
            )
            legendY += 18f
        }

        return maxOf(centreY + radius, legendY)
    }

    /**
     * Monthly spend as columns with the budget drawn across them as a line.
     *
     * Drawn as vector rather than handed in as a bitmap: a screen-resolution
     * bitmap scaled to A4 prints soft, and the on-screen chart is sized for a
     * phone, not a page.
     */
    private fun drawTrendBars(canvas: Canvas, bars: List<TrendBar>, top: Float): Float {
        if (bars.isEmpty()) return top

        val chartHeight = 150f
        val chartWidth = PAGE_WIDTH - MARGIN * 2
        val baseline = top + chartHeight
        val budgetPaise = bars.mapNotNull { it.budget?.paise }.maxOrNull() ?: 0L
        val peak = maxOf(bars.maxOf { it.spent.paise }, budgetPaise).toFloat().coerceAtLeast(1f)

        val slot = chartWidth / bars.size
        val barWidth = (slot * 0.55f).coerceAtMost(38f)

        bars.forEachIndexed { index, bar ->
            val height = chartHeight * (bar.spent.paise / peak)
            val left = MARGIN + index * slot + (slot - barWidth) / 2f
            canvas.drawRect(left, baseline - height, left + barWidth, baseline, barPaint)
            canvas.drawText(bar.label, left, baseline + 14f, labelPaint)
            canvas.drawText(
                bar.spent.format(withPaise = false),
                left, baseline - height - 5f, smallTabularPaint,
            )
        }

        canvas.drawLine(MARGIN, baseline, PAGE_WIDTH - MARGIN, baseline, rulePaint)

        // One budget line across the whole chart, so "over or under" is a
        // single glance rather than twelve comparisons.
        if (budgetPaise > 0) {
            val budgetY = baseline - chartHeight * (budgetPaise / peak)
            canvas.drawLine(MARGIN, budgetY, PAGE_WIDTH - MARGIN, budgetY, budgetLinePaint)
            canvas.drawText(
                "Budget ${Money(budgetPaise).format(withPaise = false)}",
                MARGIN, budgetY - 5f, amberLabelPaint,
            )
        }

        return baseline + 26f
    }

    private fun drawTrendPage(document: PdfDocument, statement: Statement, pages: PageCounter) {
        val page = document.startPage(pageInfo(pages.next()))
        val canvas = page.canvas
        var y = MARGIN + 40f

        canvas.drawText("Trajectory", MARGIN, y, titlePaint)
        y += 24f

        if (statement.options.trendChart) {
            if (statement.trendBars.isNotEmpty()) {
                canvas.drawText("Monthly spend against budget", MARGIN, y, labelPaint)
                y += 18f
                y = drawTrendBars(canvas, statement.trendBars, y)
            } else {
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
            }
        }

        if (statement.options.recurring && statement.recurring.isNotEmpty()) {
            canvas.drawText("Recurring & EMIs", MARGIN, y, labelPaint)
            y += 22f
            for ((label, amount) in statement.recurring) {
                if (y > PAGE_HEIGHT - MARGIN - 20f) break
                canvas.drawText(label.take(40), MARGIN, y, bodyPaint)
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

    /**
     * Every transaction, across as many pages as it takes.
     *
     * Paginated by measuring against the page bottom rather than by assuming a
     * row count: the previous table simply stopped drawing when it ran out of
     * page, so a long period silently lost its tail with nothing to say so.
     */
    private fun drawLedgerPages(document: PdfDocument, statement: Statement, pages: PageCounter) {
        if (statement.ledger.isEmpty()) return
        val dateX = MARGIN
        val nameX = MARGIN + 62f
        val categoryX = MARGIN + 240f
        val accountX = MARGIN + 350f
        val amountX = MARGIN + 450f

        var page = document.startPage(pageInfo(pages.next()))
        var canvas = page.canvas
        var y = MARGIN + 40f
        canvas.drawText("Every transaction", MARGIN, y, titlePaint)
        y += 30f

        fun header() {
            canvas.drawText("Date", dateX, y, labelPaint)
            canvas.drawText("Name", nameX, y, labelPaint)
            canvas.drawText("Category", categoryX, y, labelPaint)
            canvas.drawText("Account", accountX, y, labelPaint)
            canvas.drawText("Amount", amountX, y, labelPaint)
            y += 8f
            canvas.drawLine(MARGIN, y, PAGE_WIDTH - MARGIN, y, rulePaint)
            y += 18f
        }
        header()

        for (line in statement.ledger) {
            if (y > PAGE_HEIGHT - MARGIN - 24f) {
                document.finishPage(page)
                page = document.startPage(pageInfo(pages.next()))
                canvas = page.canvas
                y = MARGIN + 30f
                header()
            }
            canvas.drawText(line.date, dateX, y, smallTabularPaint)
            canvas.drawText(line.merchant.take(30), nameX, y, bodyPaint)
            canvas.drawText(line.category.take(18), categoryX, y, bodyPaint)
            canvas.drawText(line.account.take(16), accountX, y, bodyPaint)
            canvas.drawText(
                (if (line.isCredit) "+" else "-") + line.amount.format(withPaise = false),
                amountX, y, smallTabularPaint,
            )
            y += 17f
        }
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
        val smallTabularPaint = Paint().apply {
            color = Color.BLACK
            textSize = 9f
            typeface = Typeface.MONOSPACE
            isAntiAlias = true
        }
        val barPaint = Paint().apply {
            color = Color.rgb(0x3B, 0x3B, 0x3B)
            isAntiAlias = true
        }
        val budgetLinePaint = Paint().apply {
            color = Color.rgb(0xD9, 0x77, 0x06)
            strokeWidth = 1.5f
            pathEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
        }
        val amberLabelPaint = Paint().apply {
            color = Color.rgb(0xD9, 0x77, 0x06)
            textSize = 9f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            isAntiAlias = true
        }
        val pieOutlinePaint = Paint().apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 1f
            isAntiAlias = true
        }

        const val PIE_SLICES = 8

        /**
         * Distinct LIGHTNESSES, not hues. Statements get printed, and printed
         * in black and white more often than not, so a hue-based palette would
         * collapse into eight identical greys on paper.
         */
        private val PIE_TONES = intArrayOf(0x22, 0x55, 0x88, 0xBB, 0x3B, 0x6E, 0xA1, 0xD4)

        fun piePaint(index: Int): Paint = Paint().apply {
            val tone = PIE_TONES[index % PIE_TONES.size]
            color = Color.rgb(tone, tone, tone)
            isAntiAlias = true
        }
    }
}
