package id.maoneart.print

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.Calendar
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

object EpsonUsbProtocolEngine {

    /**
     * Resolves which protocol to use when AUTO is selected based on USB IEEE-1284 Device ID & model name.
     */
    fun resolveActiveProtocol(
        configured: EpsonDriverProtocol,
        printer: ConnectedUsbPrinter?,
        paperSize: PaperSizeOption
    ): EpsonDriverProtocol {
        if (configured != EpsonDriverProtocol.AUTO) return configured
        if (paperSize == PaperSizeOption.THERMAL_58) return EpsonDriverProtocol.ESCPOS_58
        if (paperSize == PaperSizeOption.THERMAL_80) return EpsonDriverProtocol.ESCPOS_80

        val cmdUpper = (printer?.ieee1284Cmd ?: "").uppercase()
        val nameUpper = (printer?.displayName ?: "").uppercase()

        if (nameUpper.contains("TM-") || nameUpper.contains("THERMAL") || nameUpper.contains("POS")) {
            return EpsonDriverProtocol.ESCPOS_80
        }

        // If we read IEEE 1284 CMD string from the printer firmware over USB control transfer:
        if (cmdUpper.isNotEmpty()) {
            if (cmdUpper.contains("ESCPR")) {
                return EpsonDriverProtocol.ESCPR
            }
            if (cmdUpper.contains("ESCPL2")) {
                return EpsonDriverProtocol.ESCP2
            }
        }

        // Fallback model-based detection for older ESCPL2-only models vs ESC/P-R models:
        val escpl2OnlyModels = listOf("L110", "L120", "L210", "L300", "L350", "L355", "L550", "L555", "LX-", "LQ-")
        if (escpl2OnlyModels.any { nameUpper.contains(it) }) {
            return EpsonDriverProtocol.ESCP2
        }

        return EpsonDriverProtocol.ESCPR
    }

    /**
     * Target printable pixel dimensions (printableWidth, printableHeight) at 360 DPI.
     */
    fun getTargetPixelDimensions(
        protocol: EpsonDriverProtocol,
        paperSize: PaperSizeOption,
        borderless: Boolean = false
    ): Pair<Int, Int> {
        return when (protocol) {
            EpsonDriverProtocol.ESCPOS_58 -> 384 to (384 * (paperSize.heightMm / paperSize.widthMm)).roundToInt()
            EpsonDriverProtocol.ESCPOS_80 -> 576 to (576 * (paperSize.heightMm / paperSize.widthMm)).roundToInt()
            EpsonDriverProtocol.ESCPR, EpsonDriverProtocol.ESCP2, EpsonDriverProtocol.AUTO -> {
                val dpi = 360.0
                val mm2in = 1.0 / 25.4
                val paperW = ceil(paperSize.widthMm * mm2in * dpi).toInt()
                val paperH = ceil(paperSize.heightMm * mm2in * dpi).toInt()
                val margin = if (borderless) 0 else floor(3.0 * mm2in * dpi).toInt() // 42 dots (3mm)
                val printableW = (paperW - margin * 2).coerceAtLeast(100)
                val printableH = (paperH - margin * 2).coerceAtLeast(100)
                printableW to printableH
            }
        }
    }

    /**
     * Builds the REMOTE1 session initialization sequence shared by ESC/P-R and ESCPL2.
     * Matches real Epson L3110 USB capture (TI, JS, JH, HD, PP, Exit REMOTE1).
     */
    private fun writeRemote1JobSetup(out: ByteArrayOutputStream, jobName: String = "ESCPRLib") {
        // Enter REMOTE1 mode: ESC ( R 08 00 00 REMOTE1
        out.write(
            byteArrayOf(
                0x1B, 0x28, 0x52, 0x08, 0x00, 0x00,
                'R'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(),
                'O'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte()
            )
        )

        // 1. TI (Time Init): "TI" + len(8 LE) + resp(0) + YYYY(BE16) + MM + DD + hh + mm + ss
        val cal = Calendar.getInstance()
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)
        writeRemote1Cmd(
            out,
            "TI",
            byteArrayOf(
                ((year shr 8) and 0xFF).toByte(),
                (year and 0xFF).toByte(),
                month.toByte(),
                day.toByte(),
                hour.toByte(),
                minute.toByte(),
                second.toByte()
            )
        )

        // 2. JS (Job Start): "JS" + len(4 LE) + resp(0) + 00 00 00
        writeRemote1Cmd(out, "JS", byteArrayOf(0x00, 0x00, 0x00))

        // 3. JH (Job Header): "JH" + len + resp(0) + jobType(0) + jobId(4B BE=0) + "ESCPRLib"
        val nameBytes = jobName.toByteArray(Charsets.US_ASCII)
        val jhData = ByteArray(5 + nameBytes.size)
        System.arraycopy(nameBytes, 0, jhData, 5, nameBytes.size)
        writeRemote1Cmd(out, "JH", jhData)

        // 4. HD (Hardware Device): "HD" + len(3 LE) + resp(0) + 03 04 (4 = Linux/Android)
        writeRemote1Cmd(out, "HD", byteArrayOf(0x03, 0x04))

        // 5. PP (Paper Path): "PP" + len(3 LE) + resp(0) + 01 FF (Rear/Auto paper feed)
        writeRemote1Cmd(out, "PP", byteArrayOf(0x01, 0xFF.toByte()))

        // Exit REMOTE1 mode: ESC 0x00 0x00 0x00
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))
    }

    private fun writeRemote1JobTeardown(out: ByteArrayOutputStream) {
        out.write(
            byteArrayOf(
                0x1B, 0x28, 0x52, 0x08, 0x00, 0x00,
                'R'.code.toByte(), 'E'.code.toByte(), 'M'.code.toByte(),
                'O'.code.toByte(), 'T'.code.toByte(), 'E'.code.toByte(), '1'.code.toByte()
            )
        )
        // LD (Load Defaults)
        writeRemote1Cmd(out, "LD", ByteArray(0))
        // JE (Job End)
        writeRemote1Cmd(out, "JE", ByteArray(0))
        // Exit REMOTE1 mode
        out.write(byteArrayOf(0x1B, 0x00, 0x00, 0x00))
    }

    private fun writeRemote1Cmd(out: ByteArrayOutputStream, cmd2Char: String, data: ByteArray, response: Int = 0) {
        val codeBytes = cmd2Char.toByteArray(Charsets.US_ASCII)
        out.write(codeBytes, 0, 2)
        val lenPlusResp = data.size + 1
        out.write(lenPlusResp and 0xFF)
        out.write((lenPlusResp shr 8) and 0xFF)
        out.write(response and 0xFF)
        if (data.isNotEmpty()) {
            out.write(data)
        }
    }

    /**
     * Builds the exact ESC/P-R Job Start sequence verified from Epson L3110 USB capture & python-epson:
     * 1. Exit Packet Mode (`@EJL 1284.4`) + `ESC @`
     * 2. `REMOTE1` setup (`TI`, `JS`, `JH`, `HD`, `PP`, Exit)
     * 3. Enter ESC/P-R Raster Mode: `ESC ( R 06 00 00 ESCPR` (6 bytes, NOT ESCPRJ!)
     * 4. `setq` (Class 'q', 9 bytes: mtid, mqid, cm, brightness, contrast, saturation, cp=0, paletteLen=0)
     * 5. `setj` (Class 'j', 22 bytes: paperW, paperH, topMargin, leftMargin, printableW, printableH, ir=0 (360dpi), pd=0)
     */
    fun buildEscprJobStart(
        config: PrintJobConfig
    ): ByteArray {
        val out = ByteArrayOutputStream(512)

        // 1. Exit IEEE 1284.4 Packet Mode & Initialize Printer
        out.write(byteArrayOf(0x00, 0x00, 0x00))
        out.write("\u001b\u0001@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @

        // 2. REMOTE1 Handshake
        writeRemote1JobSetup(out, "ESCPRLib")

        // 3. Enter ESC/P-R Raster Mode: ESC ( R 06 00 00 ESCPR
        out.write(
            byteArrayOf(
                0x1B, 0x28, 0x52, 0x06, 0x00, 0x00,
                'E'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(),
                'P'.code.toByte(), 'R'.code.toByte()
            )
        )

        // 4. Send 'setq' (Class 'q', length = 9 bytes)
        val mtid: Byte = if (config.paperSize == PaperSizeOption.PHOTO_4R || config.paperSize == PaperSizeOption.PHOTO_5R) {
            0x0B // PGPHOTO (Glossy Photo Paper)
        } else {
            0x00 // PLAIN Paper
        }
        val mqid: Byte = when (config.quality) {
            PrintQualityOption.DRAFT -> 0x00
            PrintQualityOption.NORMAL -> 0x01
            PrintQualityOption.HIGH -> 0x02
        }
        val cm: Byte = if (config.colorMode == ColorModeOption.BLACK_WHITE) 0x01 else 0x00
        val setqParams = byteArrayOf(
            mtid,
            mqid,
            cm,
            0x00, // brightness
            0x00, // contrast
            0x00, // saturation
            0x00, // cp = CP.FULLCOLOR (24-bit RGB, 3 bytes per pixel)
            0x00, 0x00 // palette length = 0 (uint16 BE)
        )
        writeEscprCommand(out, 'q', "setq", setqParams)

        // 5. Send 'setj' (Class 'j', length = 22 bytes: >LLHHLLBB)
        val dpi = 360.0
        val mm2in = 1.0 / 25.4
        val wMm = if (config.orientation == PageOrientation.LANDSCAPE) {
            maxOf(config.paperSize.widthMm, config.paperSize.heightMm)
        } else {
            minOf(config.paperSize.widthMm, config.paperSize.heightMm)
        }
        val hMm = if (config.orientation == PageOrientation.LANDSCAPE) {
            minOf(config.paperSize.widthMm, config.paperSize.heightMm)
        } else {
            maxOf(config.paperSize.widthMm, config.paperSize.heightMm)
        }

        val paperWidth = ceil(wMm * mm2in * dpi).toInt()
        val paperHeight = ceil(hMm * mm2in * dpi).toInt()
        val margin = if (config.scaleMode == ScaleFitMode.MARGINLESS) 0 else floor(3.0 * mm2in * dpi).toInt() // 42 dots
        val printableWidth = (paperWidth - margin * 2).coerceAtLeast(100)
        val printableHeight = (paperHeight - margin * 2).coerceAtLeast(100)

        val setjParams = ByteArrayOutputStream(22)
        writeInt32BE(setjParams, paperWidth)
        writeInt32BE(setjParams, paperHeight)
        writeInt16BE(setjParams, margin) // marginTop
        writeInt16BE(setjParams, margin) // marginLeft
        writeInt32BE(setjParams, printableWidth)
        writeInt32BE(setjParams, printableHeight)
        setjParams.write(0x00) // ir = 0 (360 DPI! Note: 1 is 720 DPI)
        setjParams.write(0x00) // pd = 0 (Bi-directional)

        writeEscprCommand(out, 'j', "setj", setjParams.toByteArray())
        return out.toByteArray()
    }

    /**
     * Starts an ESC/P-R page (`sttp` + `setn`).
     */
    fun buildEscprPageStart(pageNumber: Int = 1): ByteArray {
        val out = ByteArrayOutputStream(32)
        writeEscprCommand(out, 'p', "sttp", ByteArray(0))
        writeEscprCommand(out, 'p', "setn", byteArrayOf(pageNumber.coerceIn(1, 99).toByte()))
        return out.toByteArray()
    }

    /**
     * Ends an ESC/P-R page (`endp` with remaining pages count).
     */
    fun buildEscprPageEnd(remainingPages: Int): ByteArray {
        val out = ByteArrayOutputStream(32)
        writeEscprCommand(out, 'p', "endp", byteArrayOf(remainingPages.coerceIn(0, 99).toByte()))
        return out.toByteArray()
    }

    /**
     * Ends an ESC/P-R job (`endj` + `ESC @` + `REMOTE1 LD/JE`).
     */
    fun buildEscprJobEnd(): ByteArray {
        val out = ByteArrayOutputStream(128)
        writeEscprCommand(out, 'j', "endj", ByteArray(0))
        out.write(byteArrayOf(0x1B, 0x40)) // ESC @
        writeRemote1JobTeardown(out)
        return out.toByteArray()
    }

    /**
     * Streams a full page Bitmap using ESC/P-R `dsnd` scanlines.
     * Note: Since `cp = 0` (CP.FULLCOLOR) in `setq`, each pixel is always 3 bytes (R, G, B)
     * compressed with 3-byte PackBits RLE (`encodeRgbRleLine`), both in Color and Grayscale modes.
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
        val rowPixels = IntArray(width)
        val batchBuffer = ByteArrayOutputStream(32768)

        for (y in 0 until height) {
            if (isCancelled()) return false

            bitmap.getPixels(rowPixels, 0, width, 0, y, width, 1)

            // Find first and last non-white pixel on this row
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
                val compressedLine = encodeRgbRleLine(rowPixels, firstNonWhite, activeWidth)

                // 'dsnd' payload: xOffset(2 BE) + yOffset(2 BE) + cmode(1 = RLE) + dataLen(2 BE) + data
                val paramLen = 7 + compressedLine.size
                batchBuffer.write(0x1B)
                batchBuffer.write('d'.code)
                writeInt32LE(batchBuffer, paramLen)
                batchBuffer.write('d'.code)
                batchBuffer.write('s'.code)
                batchBuffer.write('n'.code)
                batchBuffer.write('d'.code)

                writeInt16BE(batchBuffer, firstNonWhite)
                writeInt16BE(batchBuffer, y)
                batchBuffer.write(0x01) // cmode = 1 (RLE compressed)
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
     * Streams a full page Bitmap using Epson ESCPL2 / ESC/P2 Extended Raster (`ESC ( D` + `ESC i`)
     * for Epson L110, L120, L210, L300, L350, Stylus, etc.
     * Matches `epson/escp.py` in `ezrec/python-epson`.
     */
    suspend fun streamEscp2PageBitmap(
        bitmap: Bitmap,
        config: PrintJobConfig,
        isCancelled: () -> Boolean,
        onProgress: (Float) -> Unit,
        sendChunk: suspend (ByteArray) -> Boolean
    ): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val bytesPerLine = (width + 7) / 8

        val dpi = 360.0
        val mm2in = 1.0 / 25.4
        val wMm = if (config.orientation == PageOrientation.LANDSCAPE) {
            maxOf(config.paperSize.widthMm, config.paperSize.heightMm)
        } else {
            minOf(config.paperSize.widthMm, config.paperSize.heightMm)
        }
        val hMm = if (config.orientation == PageOrientation.LANDSCAPE) {
            minOf(config.paperSize.widthMm, config.paperSize.heightMm)
        } else {
            maxOf(config.paperSize.widthMm, config.paperSize.heightMm)
        }

        val paperW = ceil(wMm * mm2in * dpi).toInt()
        val paperH = ceil(hMm * mm2in * dpi).toInt()
        val margin = if (config.scaleMode == ScaleFitMode.MARGINLESS) 0 else floor(3.0 * mm2in * dpi).toInt()
        val yTop = margin
        val yBottom = (paperH - margin).coerceAtLeast(yTop + 100)

        val initOut = ByteArrayOutputStream(256)
        // 1. Exit packet mode
        initOut.write(byteArrayOf(0x00, 0x00, 0x00))
        initOut.write("\u001b\u0001@EJL 1284.4\n@EJL     \n".toByteArray(Charsets.US_ASCII))

        // 2. REMOTE1 setup
        writeRemote1JobSetup(initOut, "ESCPLib")

        // 3. ESC @ Initialize printer
        initOut.write(byteArrayOf(0x1B, 0x40))

        // 4. ESC ( G 01 00 01 -> Enter Graphics Mode
        initOut.write(byteArrayOf(0x1B, 0x28, 0x47, 0x01, 0x00, 0x01))

        // 5. ESC ( U 01 00 0A -> Set unit to 1/360 inch (3600 / 360 = 10)
        initOut.write(byteArrayOf(0x1B, 0x28, 0x55, 0x01, 0x00, 0x0A))

        // 6. ESC U 00 -> Bi-directional printing
        initOut.write(byteArrayOf(0x1B, 0x55, 0x00))

        // 7. ESC ( K 02 00 00 [cm: 1=Mono, 2=Color]
        val escpColorMode: Byte = if (config.colorMode == ColorModeOption.BLACK_WHITE) 0x01 else 0x02
        initOut.write(byteArrayOf(0x1B, 0x28, 0x4B, 0x02, 0x00, 0x00, escpColorMode))

        // 8. ESC ( D 04 00 A0 05 04 04 -> Set Raster Resolution (base=1440, v=1440/360=4, h=1440/360=4)
        // Crucial for 'ESC i' raster mode recognition!
        initOut.write(byteArrayOf(0x1B, 0x28, 0x44, 0x04, 0x00, 0xA0.toByte(), 0x05, 0x04, 0x04))

        // 9. ESC ( c 08 00 [yTop:4B LE] [yBottom:4B LE] -> Page Format v2
        initOut.write(byteArrayOf(0x1B, 0x28, 0x63, 0x08, 0x00))
        writeInt32LE(initOut, yTop)
        writeInt32LE(initOut, yBottom)

        // 10. ESC ( S 08 00 [paperW:4B LE] [paperH:4B LE] -> Paper Dimensions v2
        initOut.write(byteArrayOf(0x1B, 0x28, 0x53, 0x08, 0x00))
        writeInt32LE(initOut, paperW)
        writeInt32LE(initOut, paperH)

        // 11. ESC ( m 01 00 12 -> Print Method
        initOut.write(byteArrayOf(0x1B, 0x28, 0x6D, 0x01, 0x00, 0x12))

        // 12. ESC ( V 04 00 [yTop:4B LE] -> Set initial vertical position
        initOut.write(byteArrayOf(0x1B, 0x28, 0x56, 0x04, 0x00))
        writeInt32LE(initOut, yTop)

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

            for (i in errCurr.indices) {
                errCurr[i] = errNext[i]
                errNext[i] = 0f
            }

            if (!hasBlackPixel) {
                pendingBlankLines++
            } else {
                if (pendingBlankLines > 0) {
                    // ESC ( v 04 00 [offset:4B LE] -> Relative vertical advance v2
                    batchOut.write(byteArrayOf(0x1B, 0x28, 0x76, 0x04, 0x00))
                    writeInt32LE(batchOut, pendingBlankLines)
                    pendingBlankLines = 0
                }

                // ESC ( $ 04 00 [marginLeft:4B LE] -> Set horizontal position v2
                batchOut.write(byteArrayOf(0x1B, 0x28, 0x24, 0x04, 0x00))
                writeInt32LE(batchOut, margin)

                val rleBits = encodePackBits1Bit(lineBitBytes)
                // ESC i color(0=Black) cmode(1=RLE) bpp(1) bytesPerLine(2B LE) lines(2B LE = 1) data
                batchOut.write(
                    byteArrayOf(
                        0x1B, 0x69,
                        0x00, // K (Black)
                        0x01, // RLE compressed
                        0x01, // 1 bit per pixel
                        (bytesPerLine and 0xFF).toByte(),
                        ((bytesPerLine shr 8) and 0xFF).toByte(),
                        0x01, 0x00 // 1 line
                    )
                )
                batchOut.write(rleBits)
                pendingBlankLines = 1

                if (batchOut.size() >= 16384) {
                    if (!sendChunk(batchOut.toByteArray())) return false
                    batchOut.reset()
                }
            }

            if (y % 40 == 0 || y == height - 1) {
                onProgress((y + 1).toFloat() / height.toFloat())
            }
        }

        // Form Feed (0x0C) + ESC @ + REMOTE1 teardown
        batchOut.write(0x0C)
        batchOut.write(byteArrayOf(0x1B, 0x40))
        writeRemote1JobTeardown(batchOut)
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

        if (!sendChunk(byteArrayOf(0x1B, 0x40))) return false

        val bandHeight = 64
        val errCurr = FloatArray(width + 2)
        val errNext = FloatArray(width + 2)

        var y = 0
        while (y < effectiveHeight) {
            if (isCancelled()) return false
            val currentBandH = min(bandHeight, effectiveHeight - y)
            val bandOut = ByteArrayOutputStream(8 + bytesPerRow * currentBandH)

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

        val finishCmd = byteArrayOf(0x1B, 0x64, 0x04, 0x1D, 0x56, 0x42, 0x00)
        return sendChunk(finishCmd)
    }

    /**
     * Encodes an RGB scanline segment using ESC/P-R 3-byte pixel PackBits RLE.
     * Matches `run_length_encode(line, bytes_per_pixel=3)` in `ezrec/python-epson`.
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
                // Repeated pixel run: control byte = (257 - runLen)
                out.write((257 - runLen) and 0xFF)
                out.write((baseColor shr 16) and 0xFF)
                out.write((baseColor shr 8) and 0xFF)
                out.write(baseColor and 0xFF)
                i += runLen
            } else {
                // Literal pixel sequence up to 128 pixels: control byte = (litLen - 1)
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
