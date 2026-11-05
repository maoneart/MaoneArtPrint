package id.maoneart.print

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object DocumentRenderEngine {

    fun getFileName(context: Context, uri: Uri): String {
        var name = "Dokumen"
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    name = cursor.getString(nameIndex) ?: "Dokumen"
                }
            }
        } catch (_: Exception) {
            name = uri.lastPathSegment ?: "Dokumen"
        }
        return name
    }

    suspend fun loadPdfPages(
        context: Context,
        uri: Uri,
        onPageLoaded: (Int, Int) -> Unit = { _, _ -> }
    ): List<PrintablePageItem> = withContext(Dispatchers.IO) {
        val result = mutableListOf<PrintablePageItem>()
        val fileName = getFileName(context, uri)
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext emptyList()

        pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val pageCount = renderer.pageCount
                for (index in 0 until pageCount) {
                    onPageLoaded(index + 1, pageCount)
                    renderer.openPage(index).use { page ->
                        val maxPreviewEdge = 1000f
                        val scale = min(
                            maxPreviewEdge / max(1, page.width).toFloat(),
                            maxPreviewEdge / max(1, page.height).toFloat()
                        ).coerceAtLeast(0.5f)

                        val previewW = (page.width * scale).roundToInt().coerceAtLeast(100)
                        val previewH = (page.height * scale).roundToInt().coerceAtLeast(100)

                        val bitmap = Bitmap.createBitmap(previewW, previewH, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bitmap)
                        canvas.drawColor(Color.WHITE)

                        page.render(
                            bitmap,
                            null,
                            null,
                            PdfRenderer.Page.RENDER_MODE_FOR_PRINT
                        )

                        result.add(
                            PrintablePageItem(
                                id = UUID.randomUUID().toString(),
                                title = "Halaman ${index + 1}",
                                subtitle = "$fileName • Hal ${index + 1} dari $pageCount",
                                fileName = fileName,
                                sourceType = SourceFileType.PDF_PAGE,
                                uri = uri,
                                pdfPageIndex = index,
                                previewBitmap = bitmap,
                                selected = true,
                                rotationDegrees = 0
                            )
                        )
                    }
                }
            }
        }
        result
    }

    suspend fun loadImageUri(
        context: Context,
        uri: Uri,
        indexLabel: Int = 1
    ): PrintablePageItem? = withContext(Dispatchers.IO) {
        val fileName = getFileName(context, uri)
        val boundsOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, boundsOpts)
        }
        val origW = boundsOpts.outWidth
        val origH = boundsOpts.outHeight
        if (origW <= 0 || origH <= 0) return@withContext null

        var sampleSize = 1
        val maxPreviewDim = 1200
        while (origW / sampleSize > maxPreviewDim || origH / sampleSize > maxPreviewDim) {
            sampleSize *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }

        val rawBitmap = context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOpts)
        } ?: return@withContext null

        // Composite onto pure white background so transparent PNGs print cleanly
        val cleanBitmap = Bitmap.createBitmap(rawBitmap.width, rawBitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cleanBitmap)
        canvas.drawColor(Color.WHITE)
        canvas.drawBitmap(rawBitmap, 0f, 0f, null)
        if (rawBitmap != cleanBitmap) {
            rawBitmap.recycle()
        }

        val ext = fileName.substringAfterLast('.', "IMG").uppercase(Locale.ROOT)
        PrintablePageItem(
            id = UUID.randomUUID().toString(),
            title = fileName,
            subtitle = "$ext • ${origW}×${origH} px",
            fileName = fileName,
            sourceType = SourceFileType.IMAGE,
            uri = uri,
            pdfPageIndex = indexLabel - 1,
            previewBitmap = cleanBitmap,
            selected = true,
            rotationDegrees = 0
        )
    }

    fun createQuickTextPage(title: String, bodyText: String): PrintablePageItem {
        val width = 900
        val height = 1272 // A4 ratio
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 34f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }

        val margin = 56f
        canvas.drawText(title.ifBlank { "Catatan / Dokumen Teks" }, margin, margin + 36f, headerPaint)

        val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.LTGRAY
            strokeWidth = 2f
        }
        canvas.drawLine(margin, margin + 56f, width - margin, margin + 56f, linePaint)

        val contentWidth = (width - margin * 2).toInt()
        val staticLayout = StaticLayout.Builder
            .obtain(bodyText, 0, bodyText.length, bodyPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(6f, 1.15f)
            .build()

        canvas.save()
        canvas.translate(margin, margin + 84f)
        staticLayout.draw(canvas)
        canvas.restore()

        return PrintablePageItem(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Catatan Teks" },
            subtitle = "Teks / Struk Siap Cetak",
            fileName = "Catatan_MaoneArt.txt",
            sourceType = SourceFileType.QUICK_TEXT,
            uri = null,
            pdfPageIndex = 0,
            previewBitmap = bitmap,
            selected = true,
            rotationDegrees = 0
        )
    }

    fun createTestPrintPage(printerName: String): PrintablePageItem {
        val width = 1000
        val height = 1414
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        canvas.drawRect(40f, 40f, width - 40f, height - 40f, borderPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 40f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 24f
            typeface = Typeface.MONOSPACE
        }

        canvas.drawText("MAONEART PRINT — EPSON USB OTG TEST", 75f, 115f, titlePaint)
        val dateStr = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale("id", "ID")).format(Date())
        canvas.drawText("Printer : $printerName", 75f, 165f, subPaint)
        canvas.drawText("Waktu   : $dateStr", 75f, 200f, subPaint)
        canvas.drawText("Koneksi : Direct USB OTG Host Mode (ESC/P-R & ESC/P2)", 75f, 235f, subPaint)

        // CMYK Nozzle & Color Test Bars
        val colors = listOf(
            Color.rgb(0, 174, 239) to "CYAN (C)",
            Color.rgb(236, 0, 140) to "MAGENTA (M)",
            Color.rgb(255, 242, 0) to "YELLOW (Y)",
            Color.rgb(0, 0, 0) to "BLACK (K)",
            Color.rgb(220, 38, 38) to "RED (M+Y)",
            Color.rgb(22, 163, 74) to "GREEN (C+Y)",
            Color.rgb(37, 99, 235) to "BLUE (C+M)"
        )

        var topY = 290f
        val barFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }

        colors.forEach { (col, name) ->
            barFillPaint.color = col
            canvas.drawRect(75f, topY, 640f, topY + 52f, barFillPaint)
            canvas.drawRect(75f, topY, 640f, topY + 52f, borderPaint.apply { strokeWidth = 2f })
            canvas.drawText(name, 665f, topY + 34f, labelPaint)
            topY += 72f
        }

        // Nozzle Staircase Pattern Check
        topY += 30f
        canvas.drawText("POLA CEK NOZZLE HEAD (GARIS PRESISI):", 75f, topY, labelPaint)
        topY += 25f
        val nozzlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = 3f
            style = Paint.Style.STROKE
        }
        val nozzleColors = listOf(Color.BLACK, Color.rgb(0, 174, 239), Color.rgb(236, 0, 140), Color.rgb(234, 179, 8))
        nozzleColors.forEachIndexed { cIdx, nCol ->
            nozzlePaint.color = nCol
            val startX = 75f + (cIdx * 210f)
            for (step in 0 until 12) {
                val x1 = startX + (step * 14f)
                val y1 = topY + ((step % 4) * 14f)
                canvas.drawLine(x1, y1, x1 + 12f, y1, nozzlePaint)
            }
        }

        topY += 110f
        canvas.drawText("STATUS KALIBRASI: SIAP CETAK DOKUMEN PDF, JPG, PNG & STRUK.", 75f, topY, labelPaint)

        return PrintablePageItem(
            id = UUID.randomUUID().toString(),
            title = "Halaman Tes Kalibrasi Epson",
            subtitle = "Test Print CMYK & Nozzle Pattern",
            fileName = "Epson_OTG_TestPage.pdf",
            sourceType = SourceFileType.QUICK_TEXT,
            previewBitmap = bitmap,
            selected = true
        )
    }

    /**
     * Renders a selected page item at full print resolution according to PrintJobConfig.
     */
    suspend fun renderFinalPageBitmapForPrint(
        context: Context,
        item: PrintablePageItem,
        config: PrintJobConfig,
        targetWidthPx: Int,
        targetHeightPx: Int
    ): Bitmap = withContext(Dispatchers.IO) {
        // 1. Obtain high-resolution source bitmap
        val rawSource: Bitmap = when (item.sourceType) {
            SourceFileType.PDF_PAGE -> {
                val uri = item.uri
                if (uri != null) {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        PdfRenderer(pfd).use { renderer ->
                            if (item.pdfPageIndex in 0 until renderer.pageCount) {
                                renderer.openPage(item.pdfPageIndex).use { page ->
                                    val renderScale = min(
                                        targetWidthPx.toFloat() / max(1, page.width),
                                        targetHeightPx.toFloat() / max(1, page.height)
                                    ).coerceIn(1f, 3.5f)
                                    val bmpW = (page.width * renderScale).roundToInt().coerceAtLeast(200)
                                    val bmpH = (page.height * renderScale).roundToInt().coerceAtLeast(200)
                                    val pdfBmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                                    Canvas(pdfBmp).drawColor(Color.WHITE)
                                    page.render(pdfBmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                    pdfBmp
                                }
                            } else item.previewBitmap
                        }
                    } ?: item.previewBitmap
                } else item.previewBitmap
            }

            SourceFileType.IMAGE -> {
                val uri = item.uri
                if (uri != null) {
                    val bOpts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, bOpts)
                    }
                    var sample = 1
                    val maxTarget = max(targetWidthPx, targetHeightPx)
                    while (bOpts.outWidth / sample > maxTarget * 1.5 && bOpts.outHeight / sample > maxTarget * 1.5) {
                        sample *= 2
                    }
                    val dOpts = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it, null, dOpts)
                    } ?: item.previewBitmap
                } else item.previewBitmap
            }

            SourceFileType.QUICK_TEXT -> item.previewBitmap
        }

        // 2. Apply user rotation if needed
        val rotatedSource = if (item.rotationDegrees % 360 != 0) {
            val matrix = Matrix().apply { postRotate(item.rotationDegrees.toFloat()) }
            Bitmap.createBitmap(rawSource, 0, 0, rawSource.width, rawSource.height, matrix, true)
        } else {
            rawSource
        }

        // 3. Create final page canvas matching paper pixel dimensions
        val pageWidth = if (config.orientation == PageOrientation.LANDSCAPE) {
            max(targetWidthPx, targetHeightPx)
        } else {
            min(targetWidthPx, targetHeightPx)
        }
        val pageHeight = if (config.orientation == PageOrientation.LANDSCAPE) {
            min(targetWidthPx, targetHeightPx)
        } else {
            max(targetWidthPx, targetHeightPx)
        }

        val finalPage = Bitmap.createBitmap(pageWidth, pageHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(finalPage)
        canvas.drawColor(Color.WHITE)

        // 4. Prepare Color/Grayscale & Brightness/Contrast Paint
        val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val colorMatrix = ColorMatrix()
        if (config.colorMode == ColorModeOption.BLACK_WHITE) {
            colorMatrix.setSaturation(0f)
        }
        if (config.brightnessBoost != 0 || config.contrastBoost != 0) {
            val contrast = 1f + (config.contrastBoost / 50f)
            val brightness = config.brightnessBoost * 2.5f
            val bcMatrix = ColorMatrix(
                floatArrayOf(
                    contrast, 0f, 0f, 0f, brightness,
                    0f, contrast, 0f, 0f, brightness,
                    0f, 0f, contrast, 0f, brightness,
                    0f, 0f, 0f, 1f, 0f
                )
            )
            colorMatrix.postConcat(bcMatrix)
        }
        drawPaint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        // 5. Place content according to ScaleFitMode
        when (config.scaleMode) {
            ScaleFitMode.PAS_FOTO_3X4, ScaleFitMode.PAS_FOTO_4X6 -> {
                // Render 4-up Pas Foto Grid (3x4 cm or 4x6 cm) at top of paper with crop marks
                val photoWmm = if (config.scaleMode == ScaleFitMode.PAS_FOTO_3X4) 28f else 38f
                val photoHmm = if (config.scaleMode == ScaleFitMode.PAS_FOTO_3X4) 38f else 56f
                val pxPerMm = pageWidth.toFloat() / config.paperSize.widthMm
                val cellW = (photoWmm * pxPerMm).roundToInt()
                val cellH = (photoHmm * pxPerMm).roundToInt()
                val gap = (4f * pxPerMm).roundToInt()
                val startX = (12f * pxPerMm).roundToInt()
                val startY = (12f * pxPerMm).roundToInt()

                val cutLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.LTGRAY
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f
                    pathEffect = DashPathEffect(floatArrayOf(8f, 6f), 0f)
                }

                for (col in 0 until 4) {
                    val left = startX + col * (cellW + gap)
                    if (left + cellW > pageWidth - startX) break
                    val dstRect = RectF(left.toFloat(), startY.toFloat(), (left + cellW).toFloat(), (startY + cellH).toFloat())
                    drawCenterCropBitmap(canvas, rotatedSource, dstRect, drawPaint)
                    canvas.drawRect(dstRect, cutLinePaint)
                }
            }

            ScaleFitMode.FILL_PAGE -> {
                val margin = (pageWidth * 0.025f)
                val dstRect = RectF(margin, margin, pageWidth - margin, pageHeight - margin)
                drawCenterCropBitmap(canvas, rotatedSource, dstRect, drawPaint)
            }

            ScaleFitMode.MARGINLESS -> {
                val dstRect = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
                drawCenterFitBitmap(canvas, rotatedSource, dstRect, drawPaint)
            }

            ScaleFitMode.FIT_PAGE -> {
                // Safe 3mm physical margin (~3% of width)
                val marginX = pageWidth * 0.028f
                val marginY = pageHeight * 0.024f
                val dstRect = RectF(marginX, marginY, pageWidth - marginX, pageHeight - marginY)
                drawCenterFitBitmap(canvas, rotatedSource, dstRect, drawPaint)
            }
        }

        if (rotatedSource != rawSource && rotatedSource != item.previewBitmap) {
            rotatedSource.recycle()
        }
        if (rawSource != item.previewBitmap) {
            rawSource.recycle()
        }

        finalPage
    }

    private fun drawCenterFitBitmap(canvas: Canvas, src: Bitmap, dstBounds: RectF, paint: Paint) {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = min(dstBounds.width() / srcW, dstBounds.height() / srcH)
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = dstBounds.left + (dstBounds.width() - drawW) / 2f
        val top = dstBounds.top + (dstBounds.height() - drawH) / 2f
        val targetRect = RectF(left, top, left + drawW, top + drawH)
        canvas.drawBitmap(src, null, targetRect, paint)
    }

    private fun drawCenterCropBitmap(canvas: Canvas, src: Bitmap, dstBounds: RectF, paint: Paint) {
        val srcW = src.width.toFloat()
        val srcH = src.height.toFloat()
        val scale = max(dstBounds.width() / srcW, dstBounds.height() / srcH)
        val cropW = (dstBounds.width() / scale).roundToInt().coerceAtMost(src.width)
        val cropH = (dstBounds.height() / scale).roundToInt().coerceAtMost(src.height)
        val srcLeft = ((src.width - cropW) / 2).coerceAtLeast(0)
        val srcTop = ((src.height - cropH) / 2).coerceAtLeast(0)
        val srcRect = Rect(srcLeft, srcTop, srcLeft + cropW, srcTop + cropH)
        canvas.drawBitmap(src, srcRect, dstBounds, paint)
    }
}
