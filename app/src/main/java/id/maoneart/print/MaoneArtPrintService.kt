package id.maoneart.print

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.print.PrintAttributes
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class MaoneArtPrintService : PrintService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var usbOtgManager: UsbOtgPrinterManager

    override fun onCreate() {
        super.onCreate()
        usbOtgManager = UsbOtgPrinterManager(applicationContext)
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                refreshSystemPrinters()
            }

            override fun onStopPrinterDiscovery() {}

            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {
                refreshSystemPrinters()
            }

            override fun onStartPrinterStateTracking(printerId: PrinterId) {
                refreshSystemPrinters()
            }

            override fun onStopPrinterStateTracking(printerId: PrinterId) {}

            override fun onDestroy() {}

            private fun refreshSystemPrinters() {
                usbOtgManager.scanUsbPrinters(autoRequestPermission = true)
                val connected = usbOtgManager.connectedPrinters.value
                val printersToReport = mutableListOf<PrinterInfo>()

                if (connected.isNotEmpty()) {
                    for (usbPrinter in connected) {
                        val pId = generatePrinterId("usb_otg_${usbPrinter.vendorId}_${usbPrinter.productId}")
                        val caps = buildCapabilities(pId)
                        val info = PrinterInfo.Builder(
                            pId,
                            "${usbPrinter.displayName} (USB OTG)",
                            PrinterInfo.STATUS_IDLE
                        )
                            .setDescription("Terhubung via Kabel USB OTG • Siap Cetak")
                            .setCapabilities(caps)
                            .build()
                        printersToReport.add(info)
                    }
                } else {
                    val defaultId = generatePrinterId("maoneart_epson_otg_default")
                    val caps = buildCapabilities(defaultId)
                    val info = PrinterInfo.Builder(
                        defaultId,
                        "MaoneArt Epson USB OTG Printer",
                        PrinterInfo.STATUS_IDLE
                    )
                        .setDescription("Pasang kabel USB OTG ke Printer Epson untuk mencetak langsung")
                        .setCapabilities(caps)
                        .build()
                    printersToReport.add(info)
                }

                addPrinters(printersToReport)
            }

            private fun buildCapabilities(printerId: PrinterId): PrinterCapabilitiesInfo {
                return PrinterCapabilitiesInfo.Builder(printerId)
                    .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                    .addMediaSize(PrintAttributes.MediaSize.NA_FOOLSCAP, false)
                    .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                    .addMediaSize(PrintAttributes.MediaSize.NA_INDEX_4X6, false)
                    .addResolution(
                        PrintAttributes.Resolution("epson_360dpi", "Epson 360 DPI", 360, 360),
                        true
                    )
                    .setColorModes(
                        PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME,
                        PrintAttributes.COLOR_MODE_COLOR
                    )
                    .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                    .build()
            }
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        usbOtgManager.cancelActivePrint()
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        if (printJob.isQueued) {
            printJob.start()
        }

        val docData = printJob.document.data
        if (docData == null) {
            printJob.fail("Dokumen kosong atau tidak dapat dibaca.")
            return
        }

        val attrs = printJob.info.attributes
        val isMono = attrs.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME
        val copies = printJob.info.copies.coerceAtLeast(1)
        val mediaSize = attrs.mediaSize
        val paperOption = when {
            mediaSize?.id == PrintAttributes.MediaSize.NA_FOOLSCAP.id -> PaperSizeOption.F4
            mediaSize?.id == PrintAttributes.MediaSize.NA_LETTER.id -> PaperSizeOption.LETTER
            mediaSize?.id == PrintAttributes.MediaSize.NA_INDEX_4X6.id -> PaperSizeOption.PHOTO_4R
            else -> PaperSizeOption.A4
        }
        val orientation = if (mediaSize != null && !mediaSize.isPortrait) {
            PageOrientation.LANDSCAPE
        } else {
            PageOrientation.PORTRAIT
        }

        val tempFile = File(cacheDir, "sys_print_${System.currentTimeMillis()}.pdf")
        try {
            ParcelFileDescriptor.AutoCloseInputStream(docData).use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Exception) {
            printJob.fail("Gagal menyiapkan file cetak: ${e.message}")
            return
        }

        serviceScope.launch {
            try {
                val pages = renderTempPdfFile(tempFile)
                val config = PrintJobConfig(
                    protocol = EpsonDriverProtocol.AUTO,
                    paperSize = paperOption,
                    colorMode = if (isMono) ColorModeOption.BLACK_WHITE else ColorModeOption.COLOR,
                    quality = PrintQualityOption.NORMAL,
                    orientation = orientation,
                    scaleMode = ScaleFitMode.FIT_PAGE,
                    copies = copies
                )
                val res = usbOtgManager.executePrintJob(pages, config)
                if (res.isSuccess) {
                    printJob.complete()
                } else {
                    printJob.fail(res.exceptionOrNull()?.message ?: "Gagal mencetak via USB OTG.")
                }
            } catch (e: Exception) {
                printJob.fail(e.message ?: "Terjadi kesalahan saat mencetak.")
            } finally {
                tempFile.delete()
            }
        }
    }

    private fun renderTempPdfFile(pdfFile: File): List<PrintablePageItem> {
        val list = mutableListOf<PrintablePageItem>()
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        val bmpW = (page.width * 2).coerceAtLeast(600)
                        val bmpH = (page.height * 2).coerceAtLeast(800)
                        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                        Canvas(bmp).drawColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                        list.add(
                            PrintablePageItem(
                                id = UUID.randomUUID().toString(),
                                title = "Halaman ${i + 1}",
                                subtitle = "Print Sistem Android",
                                fileName = pdfFile.name,
                                sourceType = SourceFileType.QUICK_TEXT,
                                previewBitmap = bmp,
                                selected = true
                            )
                        )
                    }
                }
            }
        }
        return list
    }
}
