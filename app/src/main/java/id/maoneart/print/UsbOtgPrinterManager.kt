package id.maoneart.print

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

class UsbOtgPrinterManager(private val context: Context) {

    companion object {
        const val ACTION_USB_PERMISSION = "id.maoneart.print.USB_PERMISSION"
        const val EPSON_VENDOR_ID = 0x04B8 // 1208 (Seiko Epson Corp.)
    }

    private val usbManager: UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _connectedPrinters = MutableStateFlow<List<ConnectedUsbPrinter>>(emptyList())
    val connectedPrinters: StateFlow<List<ConnectedUsbPrinter>> = _connectedPrinters.asStateFlow()

    private val _selectedPrinter = MutableStateFlow<ConnectedUsbPrinter?>(null)
    val selectedPrinter: StateFlow<ConnectedUsbPrinter?> = _selectedPrinter.asStateFlow()

    private val _printProgress = MutableStateFlow(PrintProgressState())
    val printProgress: StateFlow<PrintProgressState> = _printProgress.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)
    private var receiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_USB_PERMISSION,
                UsbManager.ACTION_USB_DEVICE_ATTACHED,
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    scanUsbPrinters(autoRequestPermission = true)
                }
            }
        }
    }

    fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(usbReceiver, filter)
        }
        receiverRegistered = true
        scanUsbPrinters(autoRequestPermission = true)
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        receiverRegistered = false
    }

    fun scanUsbPrinters(autoRequestPermission: Boolean = true) {
        val deviceMap = usbManager.deviceList ?: emptyMap()
        val found = mutableListOf<ConnectedUsbPrinter>()

        for ((_, device) in deviceMap) {
            val isEpson = device.vendorId == EPSON_VENDOR_ID
            val hasPrinterInterface = hasPrinterOrBulkOutInterface(device)

            if (isEpson || hasPrinterInterface) {
                val hasPerm = usbManager.hasPermission(device)
                val prodName = try {
                    device.productName?.takeIf { it.isNotBlank() }
                } catch (_: SecurityException) {
                    null
                }
                val mfgName = try {
                    device.manufacturerName?.takeIf { it.isNotBlank() }
                } catch (_: SecurityException) {
                    null
                } ?: if (isEpson) "EPSON" else "USB Printer"

                val displayTitle = when {
                    prodName != null && prodName.uppercase().contains("EPSON") -> prodName
                    prodName != null && isEpson -> "Epson $prodName"
                    prodName != null -> "$mfgName $prodName"
                    isEpson -> "Epson USB OTG Printer (PID: 0x${device.productId.toString(16).uppercase()})"
                    else -> "USB OTG Printer (${device.vendorId}:${device.productId})"
                }

                val category = when {
                    displayTitle.uppercase().contains("TM-") -> "Epson Thermal POS (ESC/POS)"
                    isEpson -> "Epson Inkjet / EcoTank (ESC/P-R)"
                    else -> "Printer USB OTG"
                }

                found.add(
                    ConnectedUsbPrinter(
                        deviceName = device.deviceName,
                        displayName = displayTitle,
                        manufacturer = mfgName,
                        vendorId = device.vendorId,
                        productId = device.productId,
                        isEpson = isEpson,
                        hasPermission = hasPerm,
                        detectedCategory = category
                    )
                )

                if (!hasPerm && autoRequestPermission) {
                    requestUsbPermission(device)
                }
            }
        }

        // Prioritize Epson printers first
        found.sortByDescending { it.isEpson }
        _connectedPrinters.value = found

        val currentSel = _selectedPrinter.value
        val updatedSelection = found.find { it.deviceName == currentSel?.deviceName } ?: found.firstOrNull()
        _selectedPrinter.value = updatedSelection
    }

    fun selectPrinter(printer: ConnectedUsbPrinter) {
        _selectedPrinter.value = printer
        val dev = usbManager.deviceList[printer.deviceName]
        if (dev != null && !usbManager.hasPermission(dev)) {
            requestUsbPermission(dev)
        }
    }

    fun requestPermissionForSelected() {
        val sel = _selectedPrinter.value ?: _connectedPrinters.value.firstOrNull() ?: return
        val dev = usbManager.deviceList[sel.deviceName] ?: return
        if (!usbManager.hasPermission(dev)) {
            requestUsbPermission(dev)
        } else {
            scanUsbPrinters(false)
        }
    }

    private fun requestUsbPermission(device: UsbDevice) {
        try {
            val intent = Intent(ACTION_USB_PERMISSION).apply {
                setPackage(context.packageName)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_ALLOW_UNSAFE_IMPLICIT_INTENT
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
            usbManager.requestPermission(device, pendingIntent)
        } catch (_: Exception) {
        }
    }

    private fun hasPrinterOrBulkOutInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) return true
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    return true
                }
            }
        }
        return false
    }

    private fun findPrinterEndpoint(device: UsbDevice): Pair<UsbInterface, UsbEndpoint>? {
        // First pass: look specifically for USB_CLASS_PRINTER (Class 7) with Bulk OUT
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_PRINTER) {
                for (e in 0 until intf.endpointCount) {
                    val ep = intf.getEndpoint(e)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                        return intf to ep
                    }
                }
            }
        }
        // Fallback: any interface with Bulk OUT endpoint
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK && ep.direction == UsbConstants.USB_DIR_OUT) {
                    return intf to ep
                }
            }
        }
        return null
    }

    fun cancelActivePrint() {
        cancelRequested.set(true)
    }

    /**
     * Sends Eject / Form Feed & Reset command over USB OTG.
     */
    suspend fun ejectAndResetPrinter(): Result<String> = withContext(Dispatchers.IO) {
        val printer = _selectedPrinter.value
            ?: return@withContext Result.failure(Exception("Printer belum terdeteksi. Pastikan kabel USB OTG terpasang dan printer menyala."))
        val device = usbManager.deviceList[printer.deviceName]
            ?: return@withContext Result.failure(Exception("Koneksi USB OTG terputus."))
        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return@withContext Result.failure(Exception("Izin akses USB OTG diperlukan. Silakan klik 'Izinkan' pada pop-up USB."))
        }

        val pair = findPrinterEndpoint(device)
            ?: return@withContext Result.failure(Exception("Endpoint komunikasi printer tidak ditemukan."))
        val (intf, outEndpoint) = pair
        val conn = usbManager.openDevice(device)
            ?: return@withContext Result.failure(Exception("Gagal membuka jalur USB OTG ke printer."))

        try {
            conn.claimInterface(intf, true)
            val resetPayload = ByteArrayOutputStream().apply {
                write(EpsonUsbProtocolEngine.buildEscprPageEnd(false))
                write(EpsonUsbProtocolEngine.buildEscprJobEnd())
                write(byteArrayOf(0x0C, 0x1B, 0x40))
            }.toByteArray()
            transferBulkAll(conn, outEndpoint, resetPayload)
            Result.success("Perintah Reset & Keluarkan Kertas (Form Feed) berhasil dikirim ke ${printer.displayName}.")
        } finally {
            try {
                conn.releaseInterface(intf)
                conn.close()
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Executes a full multi-page, multi-copy print job over USB OTG.
     */
    suspend fun executePrintJob(
        pages: List<PrintablePageItem>,
        config: PrintJobConfig
    ): Result<String> = withContext(Dispatchers.IO) {
        if (pages.isEmpty()) {
            return@withContext Result.failure(Exception("Pilih minimal 1 halaman atau gambar yang ingin dicetak."))
        }

        scanUsbPrinters(autoRequestPermission = false)
        val printer = _selectedPrinter.value
            ?: return@withContext Result.failure(
                Exception("Printer Epson belum terdeteksi di jalur USB OTG. Pastikan:\n1. Kabel OTG sudah terpasang ke Tablet\n2. Printer Epson dalam keadaan menyala (ON)")
            )

        val device = usbManager.deviceList[printer.deviceName]
            ?: return@withContext Result.failure(Exception("Perangkat USB OTG terputus. Coba cabut dan pasang kembali konektor OTG."))

        if (!usbManager.hasPermission(device)) {
            requestUsbPermission(device)
            return@withContext Result.failure(Exception("Menunggu izin akses USB OTG. Silakan tekan 'OK / Izinkan' pada dialog sistem Android lalu tekan Cetak lagi."))
        }

        val endpointPair = findPrinterEndpoint(device)
            ?: return@withContext Result.failure(Exception("Interface USB Printer tidak ditemukan pada perangkat ini."))
        val (usbInterface, outEndpoint) = endpointPair

        val connection = usbManager.openDevice(device)
            ?: return@withContext Result.failure(Exception("Gagal membuka koneksi USB OTG ke ${printer.displayName}."))

        cancelRequested.set(false)
        val activeProtocol = EpsonUsbProtocolEngine.resolveActiveProtocol(
            config.protocol,
            printer,
            config.paperSize
        )
        val (targetW, targetH) = EpsonUsbProtocolEngine.getTargetPixelDimensions(activeProtocol, config.paperSize)
        val totalCopies = config.copies.coerceIn(1, 99)
        val totalPages = pages.size
        val totalSteps = totalPages * totalCopies
        var completedSteps = 0
        var totalBytesSent = 0L

        _printProgress.value = PrintProgressState(
            isPrinting = true,
            currentPage = 1,
            totalPages = totalPages,
            currentCopy = 1,
            totalCopies = totalCopies,
            statusText = "Menyiapkan jalur USB OTG ke ${printer.displayName}...",
            progressFraction = 0.02f,
            bytesTransferred = 0L
        )

        try {
            if (!connection.claimInterface(usbInterface, true)) {
                return@withContext Result.failure(Exception("Gagal mengklaim interface USB Printer."))
            }

            val sendRawChunk: suspend (ByteArray) -> Boolean = { bytes ->
                val ok = transferBulkAll(connection, outEndpoint, bytes)
                if (ok) {
                    totalBytesSent += bytes.size
                }
                ok
            }

            // If ESC/P-R mode, send Job Header once before pages
            if (activeProtocol == EpsonUsbProtocolEngine.resolveActiveProtocol(EpsonDriverProtocol.ESCPR, printer, config.paperSize) &&
                activeProtocol == EpsonDriverProtocol.ESCPR
            ) {
                val pageW = if (config.orientation == PageOrientation.LANDSCAPE) maxOf(targetW, targetH) else minOf(targetW, targetH)
                val pageH = if (config.orientation == PageOrientation.LANDSCAPE) minOf(targetW, targetH) else maxOf(targetW, targetH)
                val jobHeader = EpsonUsbProtocolEngine.buildEscprJobStart(pageW, pageH, config)
                if (!sendRawChunk(jobHeader)) {
                    throw Exception("Gagal mengirim header pekerjaan cetak ke printer via USB OTG.")
                }
            }

            for (copyIndex in 1..totalCopies) {
                for ((pageIdx, pageItem) in pages.withIndex()) {
                    if (cancelRequested.get()) {
                        sendEmergencyAbort(connection, outEndpoint, activeProtocol)
                        return@withContext Result.failure(Exception("Pencetakan dibatalkan oleh pengguna. Kertas telah dikeluarkan dengan aman."))
                    }

                    val humanPage = pageIdx + 1
                    _printProgress.value = _printProgress.value.copy(
                        currentPage = humanPage,
                        currentCopy = copyIndex,
                        statusText = "Merender ${pageItem.title} (Rangkap $copyIndex/$totalCopies)...",
                        bytesTransferred = totalBytesSent
                    )

                    val renderedBitmap = DocumentRenderEngine.renderFinalPageBitmapForPrint(
                        context = context,
                        item = pageItem,
                        config = config,
                        targetWidthPx = targetW,
                        targetHeightPx = targetH
                    )

                    val isLastPageOverall = (copyIndex == totalCopies) && (humanPage == totalPages)

                    val pageSuccess = when (activeProtocol) {
                        EpsonDriverProtocol.ESCPR, EpsonDriverProtocol.AUTO -> {
                            sendRawChunk(EpsonUsbProtocolEngine.buildEscprPageStart()) &&
                                EpsonUsbProtocolEngine.streamEscprPageBitmap(
                                    bitmap = renderedBitmap,
                                    config = config,
                                    isCancelled = { cancelRequested.get() },
                                    onProgress = { pageFrac ->
                                        val overall = (completedSteps + pageFrac) / totalSteps.toFloat()
                                        _printProgress.value = _printProgress.value.copy(
                                            statusText = "Mencetak ${pageItem.title} (${(pageFrac * 100).toInt()}%)...",
                                            progressFraction = overall.coerceIn(0.02f, 0.99f),
                                            bytesTransferred = totalBytesSent
                                        )
                                    },
                                    sendChunk = sendRawChunk
                                ) &&
                                sendRawChunk(EpsonUsbProtocolEngine.buildEscprPageEnd(!isLastPageOverall))
                        }

                        EpsonDriverProtocol.ESCP2 -> {
                            EpsonUsbProtocolEngine.streamEscp2PageBitmap(
                                bitmap = renderedBitmap,
                                isCancelled = { cancelRequested.get() },
                                onProgress = { pageFrac ->
                                    val overall = (completedSteps + pageFrac) / totalSteps.toFloat()
                                    _printProgress.value = _printProgress.value.copy(
                                        statusText = "Mencetak ${pageItem.title} (${(pageFrac * 100).toInt()}%)...",
                                        progressFraction = overall.coerceIn(0.02f, 0.99f),
                                        bytesTransferred = totalBytesSent
                                    )
                                },
                                sendChunk = sendRawChunk
                            )
                        }

                        EpsonDriverProtocol.ESCPOS_80, EpsonDriverProtocol.ESCPOS_58 -> {
                            EpsonUsbProtocolEngine.streamEscPosThermalBitmap(
                                bitmap = renderedBitmap,
                                isCancelled = { cancelRequested.get() },
                                onProgress = { pageFrac ->
                                    val overall = (completedSteps + pageFrac) / totalSteps.toFloat()
                                    _printProgress.value = _printProgress.value.copy(
                                        statusText = "Mencetak Struk ${pageItem.title} (${(pageFrac * 100).toInt()}%)...",
                                        progressFraction = overall.coerceIn(0.02f, 0.99f),
                                        bytesTransferred = totalBytesSent
                                    )
                                },
                                sendChunk = sendRawChunk
                            )
                        }
                    }

                    renderedBitmap.recycle()

                    if (!pageSuccess) {
                        if (cancelRequested.get()) {
                            sendEmergencyAbort(connection, outEndpoint, activeProtocol)
                            return@withContext Result.failure(Exception("Pencetakan dibatalkan oleh pengguna."))
                        }
                        throw Exception("Pengiriman data USB OTG terhenti saat mencetak ${pageItem.title}. Periksa kabel OTG atau kertas printer.")
                    }

                    completedSteps++
                }
            }

            if (activeProtocol == EpsonDriverProtocol.ESCPR || activeProtocol == EpsonDriverProtocol.AUTO) {
                sendRawChunk(EpsonUsbProtocolEngine.buildEscprJobEnd())
            }

            _printProgress.value = PrintProgressState(isPrinting = false)
            Result.success(
                "Berhasil mencetak $totalPages halaman ($totalCopies rangkap) ke ${printer.displayName} via USB OTG."
            )
        } catch (e: Exception) {
            _printProgress.value = PrintProgressState(isPrinting = false)
            Result.failure(e)
        } finally {
            _printProgress.value = PrintProgressState(isPrinting = false)
            try {
                connection.releaseInterface(usbInterface)
                connection.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun sendEmergencyAbort(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        protocol: EpsonDriverProtocol
    ) {
        try {
            val abortBytes = when (protocol) {
                EpsonDriverProtocol.ESCPR, EpsonDriverProtocol.AUTO -> {
                    EpsonUsbProtocolEngine.buildEscprPageEnd(false) + EpsonUsbProtocolEngine.buildEscprJobEnd()
                }
                else -> byteArrayOf(0x0C, 0x1B, 0x40)
            }
            transferBulkAll(connection, endpoint, abortBytes)
        } catch (_: Exception) {
        }
    }

    private fun transferBulkAll(
        connection: UsbDeviceConnection,
        endpoint: UsbEndpoint,
        data: ByteArray
    ): Boolean {
        val chunkSize = 16384
        var offset = 0
        while (offset < data.size) {
            if (cancelRequested.get()) return false
            val len = min(chunkSize, data.size - offset)
            val slice = if (offset == 0 && len == data.size) {
                data
            } else {
                data.copyOfRange(offset, offset + len)
            }
            var retries = 0
            var transferred = -1
            while (retries < 5 && transferred < 0) {
                transferred = connection.bulkTransfer(endpoint, slice, slice.size, 8000)
                if (transferred < 0) {
                    retries++
                    Thread.sleep(120)
                }
            }
            if (transferred < 0) {
                return false
            }
            offset += len
        }
        return true
    }
}
