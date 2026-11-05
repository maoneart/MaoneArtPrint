package id.maoneart.print

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import kotlin.math.min
import kotlin.math.roundToInt

object EpsonUsbProtocolEngine {

    /**
     * Resolves which protocol to use when AUTO is selected based on USB printer metadata.
     */
    fun resolveActiveProtocol(
        configured: EpsonDriverProtocol,
        printer: ConnectedUsbPrinter?,
        paperSize: PaperSizeOption
    ): EpsonDriverProtocol {
        if (configured != EpsonDriverProtocol.AUTO) return configured
        if (paperSize == PaperSizeOption.THERMAL_58) return EpsonDriverProtocol.ESCPOS_58
        if (paperSize == PaperSizeOption.THERMAL_80) return EpsonDriverProtocol.ESCPOS_80

        val nameUpper = (printer?.displayName ?: "").uppercase()
        return when {
            nameUpper.contains("TM-") || nameUpper.contains("THERMAL") || nameUpper.contains("POS") -> {
                EpsonDriverProtocol.ESCPOS_80
            }
            nameUpper.contains("LX-") || nameUpper.contains("LQ-") -> {
                EpsonDriverProtocol.ESCP2
            }
            else -> {
                // Default for Epson L120, L121, L1110, L3110, L3210, L3250, L3150, L805, EcoTank, Stylus
                EpsonDriverProtocol.ESCPR
            }
        }
    }

    /**
     * Target pixel dimensions (width, height) for a given protocol, paper size, and quality.
     */
    fun getTargetPixelDimensions(
        protocol: EpsonDriverProtocol,
        paperSize: PaperSizeOption
    ): Pair<Int, Int> {
        return when (protocol) {
            EpsonDriverProtocol.ESCPOS_58 -> 384 to (384 * (paperSize.heightMm / paperSize.widthMm)).roundToInt()
            EpsonDriverProtocol.ESCPOS_80 -> 576 to (576 * (paperSize.heightMm / paperSize.widthMm)).roundToInt()
            EpsonDriverProtocol.ESCPR, EpsonDriverProtocol.ESCP2, EpsonDriverProtocol.AUTO -> {
                // 360 DPI standard Epson raster grid (360 dots per 25.4 mm)
                val dpi = 360f
                val wPx = ((paperSize.widthMm / 25.4f) * dpi).roundToInt()
                val hPx = ((paperSize.heightMm / 25.4f) * dpi).roundToInt()
                wPx to hPx
            }
        }
    }

    /**
     * Sends a complete ESC/P-R job header (Exit Packet Mode + Remote Mode + ESCPRJ + 'job ' command).
     */
    fun buildEscprJobStart(
        pageWidthPx: Int,
        pageHeightPx: Int,
        config: PrintJobConfig
    ): ByteArray {
        val out = ByteArrayOutputStream(256)

        // 1. Exit IEEE 1284.4 Packet Mode & Reset Printer
        out.write(byteArrayOf(0x00, 0x00, 0x00))
        out.write("\u001b\u0001@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @

        // 2. Enter Remote Mode & PM
        out.write(
            byteArrayOf(
                0x1B, 0x28, 0x52, 0x08, 0x00, 0x00,
                'R'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(),
                'O'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte()
            )
        )
        // PM command
        out.write(byteArrayOf('P'.code.toByte(), 'M'.code.toByte(), 0x02, 0x00, 0x00, 0x00))
        // Exit Remote Mode: ESC 0x00 0x00 0x00
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))

        // 3. Enter ESC/P-R Mode: ESC ( R 07 00 00 ESCPRJ
        out.write(
            byteArrayOf(
                0x1B, 0x28, 0x52, 0x07, 0x00, 0x00,
                'E'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(),
                'P'.code.toByte(), 'R'.code.toByte(), 'J'.code.toByte()
            )
        )

        // 4. Send 'job ' command (Class 'j', 21 parameter bytes)
        val margin = if (config.scaleMode == ScaleFitMode.MARGINLESS) 0 else 42 // ~3mm at 360dpi
        val printableW = (pageWidthPx - margin * 2).coerceAtLeast(100)
        val printableH = (pageHeightPx - margin * 2).coerceAtLeast(100)

        val jobParams = ByteArrayOutputStream(21)
        writeInt32BE(jobParams, pageWidthPx)
        writeInt32BE(jobParams, pageHeightPx)
        writeInt16BE(jobParams, margin)
        writeInt16BE(jobParams, margin)
        writeInt32BE(jobParams, printableW)
        writeInt32BE(jobParams, printableH)
        jobParams.write(0x01) // 360x360 DPI input resolution
        jobParams.write(0x01) // Bi-directional printing
        jobParams.write(if (config.colorMode == ColorModeOption.BLACK_WHITE) 0x01 else 0x00) // 0=RGB, 1=Mono
        val qualityCode = when (config.quality) {
            PrintQualityOption.DRAFT -> 0x00
            PrintQualityOption.NORMAL -> 0x01
            PrintQualityOption.HIGH -> 0x02
        }
        jobParams.write(qualityCode)
        val mediaType = if (config.paperSize == PaperSizeOption.PHOTO_4R || config.paperSize == PaperSizeOption.PHOTO_5R) {
            0x0B // Photo Paper
        } else {
            0x00 // Plain Paper
        }
        jobParams.write(mediaType)

        writeEscprCommand(out, 'j', "job ", jobParams.toByteArray())
        return out.toByteArray()
    }

    fun buildEscprPageStart(): ByteArray {
        val out = ByteArrayOutputStream(32)
        writeEscprCommand(out, 'p', "sttp", ByteArray(0))
        return out.toByteArray()
    }

    fun buildEscprPageEnd(hasNextPage: Boolean): ByteArray {
        val out = ByteArrayOutputStream(32)
        writeEscprCommand(out, 'p', "endp", byteArrayOf(if (hasNextPage) 0x01 else 0x00))
        return out.toByteArray()
    }

    fun buildEscprJobEnd(): ByteArray {
        val out = ByteArrayOutputStream(32)
        writeEscprCommand(out, 'j', "endj", ByteArray(0))
        out.write(byteArrayOf(0x1B, 0x40)) // Reset printer
        return out.toByteArray()
    }

    /**
     * Streams a full page Bitmap using ESC/P-R 'dsnd' scanlines in batches so USB transfers are fast and cancellable.
     */
    suspend fun streamEscprPageBitmap(
        bitmap: Bitmap,
        config: PrintJobConfig,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        sendChunk: suspend (ByteArray) -> Boolean
    ): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val isMono = config.colorMode == ColorModeOption.BLACK_WHITE
        val rowPixels = IntArray(width)
        val batchBuffer = ByteArrayOutputStream(32768)

        for (y in 0 until height) {
            if (isCancelled()) return false

            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)

            // Trim pure white pixels on left & right of scanline
            var firstNonWhite = -1
            var lastNonWhite = -1
            for (x in 0 until width) {
                val c = rowPixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                if (r < 252 || g < 252 || b < 252) {
                    if (firstNonWhite == -1) firstNonWhite = x
                    lastNonWhite = x
                }
            }

            if (firstNonWhite != -1 && lastNonWhite >= firstNonWhite) {
                val activeWidth = lastNonWhite - firstNonWhite + 1
                val compressedLine = if (isMono) {
                    encodeMonoRleLine(rowPixels, firstNonWhite, activeWidth)
                } else {
                    encodeRgbRleLine(rowPixels, firstNonWhite, activeWidth)
                }

                // Build 'dsnd' parameter payload: xOffset(2 BE) + yOffset(2 BE) + compress(1) + dataLen(2 BE) + data
                val paramLen = 7 + compressedLine.size
                // Write ESC 'd' + length(4 LE) + "dsnd" + params
                batchBuffer.write(0x1B)
                batchBuffer.write('d'.code)
                writeInt32LE(batchBuffer, paramLen)
                batchBuffer.write('d'.code)
                batchBuffer.write('s'.code)
                batchBuffer.write('n'.code)
                batchBuffer.write('d'.code)

                writeInt16BE(batchBuffer, firstNonWhite)
                writeInt16BE(batchBuffer, y)
                batchBuffer.write(0x01) // RLE compression enabled
                writeInt16BE(batchBuffer, compressedLine.size)
                batchBuffer.write(compressedLine)

                if (batchBuffer.size() >= 16384) {
                    if (!sendChunk(batchBuffer.toByteArray())) return false
                    batchBuffer.reset()
                }
            }

            if (y % 40 == 0 || y == height - 1) {
                onProgress((y + 1).toFloat() / height.toFloat())
            }
        }

        if (batchBuffer.size() > 0) {
            if (!sendChunk(batchBuffer.toByteArray())) return false
            batchBuffer.reset()
        }
        return true
    }

    /**
     * Streams a full page Bitmap using Universal Epson ESC/P2 Raster Graphics (`ESC i`) for legacy/standard mode.
     */
    suspend fun streamEscp2PageBitmap(
        bitmap: Bitmap,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        sendChunk: suspend (ByteArray) -> Boolean
    ): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerLine = (width + 7) / 8

        val initOut = ByteArrayOutputStream(128)
        initOut.write(byteArrayOf(0x00, 0x00, 0x00))
        initOut.write("\u001b\u0001@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))
        initOut.write(byteArrayOf(0x1B, 0x40)) // ESC @ Initialize
        // ESC ( G 1 0 1 -> Select Graphics Mode
        initOut.write(byteArrayOf(0x1B, 0x28, 0x47, 0x01, 0x00, 0x01))
        // ESC ( U 1 0 10 -> Set unit to 1/360 inch (3600 / 360 = 10)
        initOut.write(byteArrayOf(0x1B, 0x28, 0x55, 0x01, 0x00, 0x0A))
        // ESC U 0 -> Bi-directional printing
        initOut.write(byteArrayOf(0x1B, 0x55, 0x00))
        if (!sendChunk(initOut.toByteArray())) return false

        val rowPixels = IntArray(width)
        val errCurr = FloatArray(width + 2)
        val errNext = FloatArray(width + 2)
        val lineBitBytes = ByteArray(bytesPerLine)
        val batchOut = ByteArrayOutputStream(16384)
        var pendingBlankLines = 0

        for (y in 0 until height) {
            if (isCancelled()) return false
            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)
            lineBitBytes.fill(0)
            var hasBlackPixel = false

            for (x in 0 until width) {
                val c = rowPixels[x]
                val r = (c shr 16) and 0xFF
                val g = (c shr 8) and 0xFF
                val b = c and 0xFF
                val lum = (0.299f * r + 0.587f * g + 0.114f * b) + errCurr[x + 1]
                val isBlack = lum < 140f
                val quantized = if (isBlack) 0f else 255f
                val err = lum - quantized

                errCurr[x + 2] += err * (7f / 16f)
                errNext[x] += err * (3f / 16f)
                errNext[x + 1] += err * (5f / 16f)
                errNext[x + 2] += err * (1f / 16f)

                if (isBlack && (r < 250 || g < 250 || b < 250)) {
                    hasBlackPixel = true
                    val byteIdx = x shr 3
                    val bitMask = 0x80 shr (x and 7)
                    lineBitBytes[byteIdx] = (lineBitBytes[byteIdx].toInt() or bitMask).toByte()
                }
            }

            // Shift error buffers
            for (i in errCurr.indices) {
                errCurr[i] = errNext[i]
                errNext[i] = 0f
            }

            if (!hasBlackPixel) {
                pendingBlankLines++
            } else {
                if (pendingBlankLines > 0) {
                    // ESC ( v 2 0 nL nH -> Relative vertical position advance
                    batchOut.write(
                        byteArrayOf(
                            0x1B, 0x28, 0x76, 0x02, 0x00,
                            (pendingBlankLines and 0xFF).toByte(),
                            ((pendingBlankLines shr 8) and 0xFF).toByte()
                        )
                    )
                    pendingBlankLines = 0
                }

                val rleBits = encodePackBits1Bit(lineBitBytes)
                // ESC i color(0=Black) compress(1=RLE) bits(1) bytesL bytesH linesL(1) linesH(0) data
                batchOut.write(
                    byteArrayOf(
                        0x1B, 0x69,
                        0x00, // Black
                        0x01, // RLE compressed
                        0x01, // 1 bit per pixel
                        (bytesPerLine and 0xFF).toByte(),
                        ((bytesPerLine shr 8) and 0xFF).toByte(),
                        0x01, 0x00
                    )
                )
                batchOut.write(rleBits)
                batchOut.write(0x0D) // Carriage Return
                pendingBlankLines = 1 // Advance 1 line for next row

                if (batchOut.size() >= 16384) {
                    if (!sendChunk(batchOut.toByteArray())) return false
                    batchOut.reset()
                }
            }

            if (y % 40 == 0 || y == height - 1) {
                onProgress((y + 1).toFloat() / height.toFloat())
            }
        }

        // Flush remaining data + Form Feed (0x0C) + Reset (ESC @)
        batchOut.write(0x0C)
        batchOut.write(byteArrayOf(0x1B, 0x40))
        return sendChunk(batchOut.toByteArray())
    }

    /**
     * Streams a Bitmap to an Epson Thermal POS Printer using ESC/POS `GS v 0` raster bands.
     */
    suspend fun streamEscPosThermalBitmap(
        bitmap: Bitmap,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        sendChunk: suspend (ByteArray) -> Boolean
    ): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerRow = (width + 7) / 8

        // Trim bottom pure-white space on thermal receipts so it doesn't waste roll paper
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var effectiveHeight = height
        while (effectiveHeight > 40) {
            val rowOffset = (effectiveHeight - 1) * width
            var rowBlank = true
            for (x in 0 until width) {
                val c = pixels[rowOffset + x]
                if (((c shr 16) and 0xFF) < 245 || ((c shr 8) and 0xFF) < 245 || (c and 0xFF) < 245) {
                    rowBlank = false
                    break
                }
            }
            if (rowBlank) effectiveHeight-- else break
        }
        effectiveHeight = min(height, effectiveHeight + 24)

        // Initialize ESC @
        if (!sendChunk(byteArrayOf(0x1B, 0x40))) return false

        val bandHeight = 64
        val errCurr = FloatArray(width + 2)
        val errNext = FloatArray(width + 2)

        var y = 0
        while (y < effectiveHeight) {
            if (isCancelled()) return false
            val currentBandH = min(bandHeight, effectiveHeight - y)
            val bandOut = ByteArrayOutputStream(8 + bytesPerRow * currentBandH)

            // GS v 0 m xL xH yL yH
            bandOut.write(
                byteArrayOf(
                    0x1D, 0x76, 0x30, 0x00,
                    (bytesPerRow and 0xFF).toByte(),
                    ((bytesPerRow shr 8) and 0xFF).toByte(),
                    (currentBandH and 0xFF).toByte(),
                    ((currentBandH shr 8) and 0xFF).toByte()
                )
            )

            for (by in 0 until currentBandH) {
                val rowY = y + by
                val rowOffset = rowY * width
                val rowBytes = ByteArray(bytesPerRow)

                for (x in 0 until width) {
                    val c = pixels[rowOffset + x]
                    val r = (c shr 16) and 0xFF
                    val g = (c shr 8) and 0xFF
                    val b = c and 0xFF
                    val lum = (0.299f * r + 0.587f * g + 0.114f * b) + errCurr[x + 1]
                    val isDark = lum < 145f
                    val quantized = if (isDark) 0f else 255f
                    val err = lum - quantized

                    errCurr[x + 2] += err * (7f / 16f)
                    errNext[x] += err * (3f / 16f)
                    errNext[x + 1] += err * (5f / 16f)
                    errNext[x + 2] += err * (1f / 16f)

                    if (isDark) {
                        val byteIdx = x shr 3
                        val bitMask = 0x80 shr (x and 7)
                        rowBytes[byteIdx] = (rowBytes[byteIdx].toInt() or bitMask).toByte()
                    }
                }

                for (i in errCurr.indices) {
                    errCurr[i] = errNext[i]
                    errNext[i] = 0f
                }

                bandOut.write(rowBytes)
            }

            if (!sendChunk(bandOut.toByteArray())) return false
            y += currentBandH
            onProgress(y.toFloat() / effectiveHeight.toFloat())
        }

        // Feed 4 lines & Partial Cut (GS V 66 0)
        val finishCmd = byteArrayOf(0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00)
        return sendChunk(finishCmd)
    }

    /**
     * Encodes an RGB scanline segment using ESC/P-R 3-byte pixel PackBits RLE.
     */
    private fun encodeRgbRleLine(pixels: IntArray, startOffset: Int, length: Int): ByteArray {
        val out = ByteArrayOutputStream(length * 3)
        var i = 0
        while (i < length) {
            var runLen = 1
            val baseColor = pixels[startOffset + i] and 0xFFFFFF
            while (i + runLen < length && runLen < 128 && (pixels[startOffset + i + runLen] and 0xFFFFFF) == baseColor) {
                runLen++
            }

            if (runLen >= 2) {
                // Repeat run: header = (257 - runLen)
                out.write((257 - runLen) and 0xFF)
                out.write((baseColor shr 16) and 0xFF)
                out.write((baseColor shr 8) and 0xFF)
                out.write(baseColor and 0xFF)
                i += runLen
            } else {
                // Literal run up to 128 pixels
                var litLen = 1
                while (i + litLen < length && litLen < 128) {
                    val curr = pixels[startOffset + i + litLen] and 0xFFFFFF
                    val nextSame = (i + litLen + 1 < length) && ((pixels[startOffset + i + litLen + 1] and 0xFFFFFF) == curr)
                    if (nextSame) break
                    litLen++
                }
                out.write((litLen - 1) and 0xFF)
                for (k in 0 until litLen) {
                    val c = pixels[startOffset + i + k]
                    out.write((c shr 16) and 0xFF)
                    out.write((c shr 8) and 0xFF)
                    out.write(c and 0xFF)
                }
                i += litLen
            }
        }
        return out.toByteArray()
    }

    /**
     * Encodes a Monochrome Grayscale scanline segment using ESC/P-R 1-byte PackBits RLE.
     */
    private fun encodeMonoRleLine(pixels: IntArray, startOffset: Int, length: Int): ByteArray {
        val grays = ByteArray(length)
        for (idx in 0 until length) {
            val c = pixels[startOffset + idx]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            grays[idx] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
        }
        return encodePackBits1Bit(grays)
    }

    private fun encodePackBits1Bit(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        val len = data.size
        while (i < len) {
            var runLen = 1
            val b = data[i]
            while (i + runLen < len && runLen < 128 && data[i + runLen] == b) {
                runLen++
            }
            if (runLen >= 2) {
                out.write((257 - runLen) and 0xFF)
                out.write(b.toInt() and 0xFF)
                i += runLen
            } else {
                var litLen = 1
                while (i + litLen < len && litLen < 128) {
                    val curr = data[i + litLen]
                    val nextSame = (i + litLen + 1 < len) && (data[i + litLen + 1] == curr)
                    if (nextSame) break
                    litLen++
                }
                out.write((litLen - 1) and 0xFF)
                out.write(data, i, litLen)
                i += litLen
            }
        }
        return out.toByteArray()
    }

    private fun writeEscprCommand(out: ByteArrayOutputStream, cmdClass: Char, cmdName: String, params: ByteArray) {
        out.write(0x1B)
        out.write(cmdClass.code)
        writeInt32LE(out, params.size)
        val nameBytes = cmdName.toByteArray(Charsets.US_ASCII)
        out.write(nameBytes, 0, 4)
        if (params.isNotEmpty()) {
            out.write(params)
        }
    }

    private fun writeInt32LE(out: ByteArrayOutputStream, value: Int) {
        out.write(value and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 24) and 0xFF)
    }

    private fun writeInt32BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 24) and 0xFF)
        out.write((value shr 16) and 0xFF)
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }

    private fun writeInt16BE(out: ByteArrayOutputStream, value: Int) {
        out.write((value shr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}
