package id.maoneart.print

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveDone
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import java.io.FileOutputStream

private val BgDeep = Color(0xFF070B14)
private val BgCard = Color(0xFF111827)
private val BgGlass = Color(0xFF1E293B)
private val AccentCyan = Color(0xFF06B6D4)
private val AccentBlue = Color(0xFF3B82F6)
private val AccentEmerald = Color(0xFF10B981)
private val AccentAmber = Color(0xFFF59E0B)
private val AccentRose = Color(0xFFF43F5E)
private val TextPrimary = Color(0xFFF8FAFC)
private val TextMuted = Color(0xFF94A3B8)
private val BorderSubtle = Color(0xFF334155)

data class GlassModalState(
    val title: String,
    val message: String,
    val isConfirm: Boolean = false,
    val isSuccess: Boolean = true,
    val confirmLabel: String = "Ya, Lanjutkan",
    val cancelLabel: String = "Tutup",
    val onConfirm: (() -> Unit)? = null
)

class MainActivity : ComponentActivity() {

    private lateinit var usbOtgManager: UsbOtgPrinterManager
    private val incomingUrisState = mutableStateListOf<Pair<Uri, String?>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbOtgManager = UsbOtgPrinterManager(applicationContext)
        usbOtgManager.registerReceiver()

        handleIncomingIntent(intent)

        setContent {
            val colorScheme = darkColorScheme(
                primary = AccentCyan,
                secondary = AccentBlue,
                background = BgDeep,
                surface = BgCard,
                onPrimary = Color.White,
                onBackground = TextPrimary,
                onSurface = TextPrimary
            )
            MaterialTheme(colorScheme = colorScheme) {
                MaoneArtPrintStudioApp(
                    usbManager = usbOtgManager,
                    incomingUris = incomingUrisState,
                    onConsumeIncomingUris = { incomingUrisState.clear() },
                    onSystemPrintFallback = { selectedPages, config ->
                        triggerAndroidSystemPrint(selectedPages, config)
                    }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        usbOtgManager.scanUsbPrinters(autoRequestPermission = true)
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        usbOtgManager.scanUsbPrinters(autoRequestPermission = false)
    }

    override fun onDestroy() {
        usbOtgManager.unregisterReceiver()
        super.onDestroy()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val mime = intent.type

        when (action) {
            Intent.ACTION_VIEW -> {
                intent.data?.let { uri ->
                    incomingUrisState.add(uri to mime)
                }
            }
            Intent.ACTION_SEND -> {
                val streamUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_STREAM)
                }
                if (streamUri != null) {
                    incomingUrisState.add(streamUri to mime)
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                uris?.forEach { u ->
                    incomingUrisState.add(u to mime)
                }
            }
        }
    }

    private fun triggerAndroidSystemPrint(
        pages: List<PrintablePageItem>,
        config: PrintJobConfig
    ) {
        if (pages.isEmpty()) return
        val printManager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        val jobName = "MaoneArt_Print_${System.currentTimeMillis()}"

        val adapter = object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder("$jobName.pdf")
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(pages.size)
                    .build()
                callback.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pageRanges: Array<out PageRange>?,
                destination: ParcelFileDescriptor,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback
            ) {
                val pdfDoc = PdfDocument()
                try {
                    val wPt = ((config.paperSize.widthMm / 25.4f) * 72f).toInt().coerceAtLeast(200)
                    val hPt = ((config.paperSize.heightMm / 25.4f) * 72f).toInt().coerceAtLeast(200)
                    val finalW = if (config.orientation == PageOrientation.LANDSCAPE) maxOf(wPt, hPt) else minOf(wPt, hPt)
                    val finalH = if (config.orientation == PageOrientation.LANDSCAPE) minOf(wPt, hPt) else maxOf(wPt, hPt)

                    pages.forEachIndexed { idx, item ->
                        if (cancellationSignal?.isCanceled == true) {
                            callback.onWriteCancelled()
                            pdfDoc.close()
                            return
                        }
                        val pageInfo = PdfDocument.PageInfo.Builder(finalW, finalH, idx + 1).create()
                        val page = pdfDoc.startPage(pageInfo)
                        val canvas = page.canvas
                        val bmp = item.previewBitmap
                        val scale = minOf(
                            finalW.toFloat() / bmp.width.toFloat(),
                            finalH.toFloat() / bmp.height.toFloat()
                        )
                        val drawW = bmp.width * scale
                        val drawH = bmp.height * scale
                        val left = (finalW - drawW) / 2f
                        val top = (finalH - drawH) / 2f
                        val dst = android.graphics.RectF(left, top, left + drawW, top + drawH)
                        canvas.drawBitmap(bmp, null, dst, null)
                        pdfDoc.finishPage(page)
                    }

                    FileOutputStream(destination.fileDescriptor).use { out ->
                        pdfDoc.writeTo(out)
                    }
                    callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) {
                    callback.onWriteFailed(e.message)
                } finally {
                    pdfDoc.close()
                }
            }
        }

        val attrs = PrintAttributes.Builder()
            .setColorMode(
                if (config.colorMode == ColorModeOption.BLACK_WHITE)
                    PrintAttributes.COLOR_MODE_MONOCHROME
                else
                    PrintAttributes.COLOR_MODE_COLOR
            )
            .setMediaSize(
                if (config.orientation == PageOrientation.LANDSCAPE)
                    PrintAttributes.MediaSize.ISO_A4.asLandscape()
                else
                    PrintAttributes.MediaSize.ISO_A4
            )
            .build()

        printManager.print(jobName, adapter, attrs)
    }
}

@Composable
fun MaoneArtPrintStudioApp(
    usbManager: UsbOtgPrinterManager,
    incomingUris: List<Pair<Uri, String?>>,
    onConsumeIncomingUris: () -> Unit,
    onSystemPrintFallback: (List<PrintablePageItem>, PrintJobConfig) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val connectedPrinters by usbManager.connectedPrinters.collectAsState()
    val selectedPrinter by usbManager.selectedPrinter.collectAsState()
    val printProgress by usbManager.printProgress.collectAsState()

    val pageItems = remember { mutableStateListOf<PrintablePageItem>() }
    var jobConfig by remember { mutableStateOf(PrintJobConfig()) }
    var isLoadingFiles by remember { mutableStateOf(false) }
    var loadingMessage by remember { mutableStateOf("Memuat dokumen...") }

    // Mobile tab index (0 = Dokumen & Pilih Halaman, 1 = Pengaturan Cetak & Printer)
    var activeMobileTab by remember { mutableIntStateOf(0) }

    // Modals state
    var glassModal by remember { mutableStateOf<GlassModalState?>(null) }
    var zoomedPageItem by remember { mutableStateOf<PrintablePageItem?>(null) }
    var showRangeDialog by remember { mutableStateOf(false) }
    var showQuickTextDialog by remember { mutableStateOf(false) }

    // Load helper for URIs (PDF or JPG/PNG/WebP)
    val loadUrisIntoStudio: (List<Uri>, Boolean) -> Unit = { uris, clearExisting ->
        if (uris.isNotEmpty()) {
            scope.launch {
                isLoadingFiles = true
                if (clearExisting) {
                    pageItems.clear()
                }
                var addedCount = 0
                for (uri in uris) {
                    val mime = context.contentResolver.getType(uri)?.lowercase() ?: ""
                    val fileName = DocumentRenderEngine.getFileName(context, uri).lowercase()
                    val isPdf = mime.contains("pdf") || fileName.endsWith(".pdf")

                    try {
                        if (isPdf) {
                            loadingMessage = "Membuka halaman PDF..."
                            val pdfPages = DocumentRenderEngine.loadPdfPages(context, uri) { cur, tot ->
                                loadingMessage = "Merender Halaman PDF $cur dari $tot..."
                            }
                            pageItems.addAll(pdfPages)
                            addedCount += pdfPages.size
                        } else {
                            loadingMessage = "Membuka gambar ${fileName.uppercase()}..."
                            val imgItem = DocumentRenderEngine.loadImageUri(
                                context = context,
                                uri = uri,
                                indexLabel = pageItems.size + 1
                            )
                            if (imgItem != null) {
                                pageItems.add(imgItem)
                                addedCount++
                            }
                        }
                    } catch (e: Exception) {
                        glassModal = GlassModalState(
                            title = "Gagal Membuka File",
                            message = "Tidak dapat memuat berkas: ${e.message}",
                            isSuccess = false
                        )
                    }
                }
                isLoadingFiles = false
                if (addedCount == 0 && pageItems.isEmpty()) {
                    glassModal = GlassModalState(
                        title = "Format Tidak Didukung",
                        message = "Pilih file dokumen PDF atau gambar JPG, JPEG, PNG, atau WebP.",
                        isSuccess = false
                    )
                }
            }
        }
    }

    // Process incoming VIEW / SEND intents automatically
    if (incomingUris.isNotEmpty()) {
        val toLoad = incomingUris.map { it.first }
        onConsumeIncomingUris()
        loadUrisIntoStudio(toLoad, false)
    }

    // File Pickers
    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadUrisIntoStudio(listOf(uri), false)
        }
    }

    val multiImagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            loadUrisIntoStudio(uris, false)
        }
    }

    val anyFilePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            loadUrisIntoStudio(uris, false)
        }
    }

    val selectedPagesCount = pageItems.count { it.selected }

    val startUsbPrintAction: () -> Unit = {
        val chosen = pageItems.filter { it.selected }
        if (chosen.isEmpty()) {
            glassModal = GlassModalState(
                title = "Belum Ada Halaman Dipilih",
                message = "Buka file PDF, JPG, atau PNG terlebih dahulu, lalu centang minimal 1 halaman yang ingin dicetak.",
                isSuccess = false
            )
        } else {
            scope.launch {
                val res = usbManager.executePrintJob(chosen, jobConfig)
                res.onSuccess { msg ->
                    glassModal = GlassModalState(
                        title = "Pencetakan Selesai",
                        message = msg,
                        isSuccess = true
                    )
                }.onFailure { err ->
                    glassModal = GlassModalState(
                        title = "Status Cetak USB OTG",
                        message = err.message ?: "Gagal mengirim data ke printer.",
                        isSuccess = false
                    )
                }
            }
        }
    }

    Scaffold(
        containerColor = BgDeep,
        topBar = {
            TopStudioHeader(
                selectedPrinter = selectedPrinter,
                connectedCount = connectedPrinters.size,
                onScanClick = { usbManager.scanUsbPrinters(autoRequestPermission = true) },
                onTestPrintClick = {
                    val pName = selectedPrinter?.displayName ?: "Epson USB OTG Printer"
                    val testPage = DocumentRenderEngine.createTestPrintPage(pName)
                    pageItems.add(0, testPage)
                    glassModal = GlassModalState(
                        title = "Halaman Tes Kalibrasi Siap",
                        message = "Halaman tes warna CMYK & Nozzle Pattern telah ditambahkan ke antrean nomor 1. Ingin langsung mencetaknya via USB OTG sekarang?",
                        isConfirm = true,
                        confirmLabel = "Cetak Tes Sekarang",
                        cancelLabel = "Lihat Preview Dulu",
                        onConfirm = {
                            scope.launch {
                                val res = usbManager.executePrintJob(listOf(testPage), jobConfig)
                                res.onSuccess { m ->
                                    glassModal = GlassModalState("Tes Cetak Berhasil", m, isSuccess = true)
                                }.onFailure { e ->
                                    glassModal = GlassModalState("Info Koneksi OTG", e.message ?: "Gagal", isSuccess = false)
                                }
                            }
                        }
                    )
                },
                onEjectResetClick = {
                    glassModal = GlassModalState(
                        title = "Reset & Keluarkan Kertas",
                        message = "Kirim perintah Form Feed & Reset Inisialisasi (ESC @) ke printer Epson melalui kabel USB OTG?",
                        isConfirm = true,
                        confirmLabel = "Ya, Keluarkan",
                        cancelLabel = "Batal",
                        onConfirm = {
                            scope.launch {
                                val res = usbManager.ejectAndResetPrinter()
                                res.onSuccess { m ->
                                    glassModal = GlassModalState("Perintah Terkirim", m, isSuccess = true)
                                }.onFailure { e ->
                                    glassModal = GlassModalState("Gagal Mengirim", e.message ?: "Error", isSuccess = false)
                                }
                            }
                        }
                    )
                }
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isTabletWide = maxWidth >= 700.dp

            if (isTabletWide) {
                // TABLET 2-PANE STUDIO LAYOUT
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Left Pane: File Openers + Print Settings + Print Button
                    Column(
                        modifier = Modifier
                            .width(370.dp)
                            .fillMaxHeight()
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            FileOpenersCard(
                                onOpenPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                onOpenImages = { multiImagePickerLauncher.launch(arrayOf("image/jpeg", "image/png", "image/webp", "image/*")) },
                                onOpenAny = { anyFilePickerLauncher.launch(arrayOf("application/pdf", "image/*")) },
                                onOpenQuickText = { showQuickTextDialog = true }
                            )

                            UsbPrinterSelectorCard(
                                printers = connectedPrinters,
                                selectedPrinter = selectedPrinter,
                                onSelectPrinter = { usbManager.selectPrinter(it) },
                                onRequestPerm = { usbManager.requestPermissionForSelected() },
                                onScan = { usbManager.scanUsbPrinters(true) }
                            )

                            PrintSettingsCard(
                                config = jobConfig,
                                onConfigChange = { jobConfig = it }
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        PrimaryPrintActionFooter(
                            selectedCount = selectedPagesCount,
                            totalCount = pageItems.size,
                            copies = jobConfig.copies,
                            onPrintUsb = startUsbPrintAction,
                            onSystemPrint = {
                                val chosen = pageItems.filter { it.selected }
                                if (chosen.isEmpty()) {
                                    glassModal = GlassModalState(
                                        title = "Pilih Halaman Dulu",
                                        message = "Centang minimal 1 halaman untuk dicetak.",
                                        isSuccess = false
                                    )
                                } else {
                                    onSystemPrintFallback(chosen, jobConfig)
                                }
                            }
                        )
                    }

                    // Right Pane: Interactive Page Grid Selector & Preview
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        PageSelectionWorkspace(
                            pages = pageItems,
                            selectedCount = selectedPagesCount,
                            onTogglePage = { idx ->
                                val item = pageItems[idx]
                                pageItems[idx] = item.copy(selected = !item.selected)
                            },
                            onRotatePage = { idx ->
                                val item = pageItems[idx]
                                pageItems[idx] = item.copy(rotationDegrees = (item.rotationDegrees + 90) % 360)
                            },
                            onDeletePage = { idx ->
                                pageItems.removeAt(idx)
                            },
                            onZoomPage = { item ->
                                zoomedPageItem = item
                            },
                            onSelectAll = {
                                for (i in pageItems.indices) {
                                    pageItems[i] = pageItems[i].copy(selected = true)
                                }
                            },
                            onDeselectAll = {
                                for (i in pageItems.indices) {
                                    pageItems[i] = pageItems[i].copy(selected = false)
                                }
                            },
                            onOpenRangePicker = { showRangeDialog = true },
                            onClearAll = {
                                if (pageItems.isNotEmpty()) {
                                    glassModal = GlassModalState(
                                        title = "Bersihkan Daftar Halaman?",
                                        message = "Hapus semua ${pageItems.size} halaman dari daftar antrean pratinjau saat ini?",
                                        isConfirm = true,
                                        confirmLabel = "Ya, Bersihkan",
                                        cancelLabel = "Batal",
                                        onConfirm = { pageItems.clear() }
                                    )
                                }
                            },
                            onOpenPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                            onOpenImages = { multiImagePickerLauncher.launch(arrayOf("image/*")) }
                        )
                    }
                }
            } else {
                // PHONE / COMPACT TABLET PORTRAIT LAYOUT
                Column(modifier = Modifier.fillMaxSize()) {
                    // 2-Tab Switcher
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BgCard)
                            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TabPillButton(
                            modifier = Modifier.weight(1f),
                            title = "1. File & Pilih Halaman (${selectedPagesCount}/${pageItems.size})",
                            selected = activeMobileTab == 0,
                            onClick = { activeMobileTab = 0 }
                        )
                        TabPillButton(
                            modifier = Modifier.weight(1f),
                            title = "2. Pengaturan Kertas & OTG",
                            selected = activeMobileTab == 1,
                            onClick = { activeMobileTab = 1 }
                        )
                    }

                    Box(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        if (activeMobileTab == 0) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                FileOpenersCard(
                                    onOpenPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                    onOpenImages = { multiImagePickerLauncher.launch(arrayOf("image/*")) },
                                    onOpenAny = { anyFilePickerLauncher.launch(arrayOf("application/pdf", "image/*")) },
                                    onOpenQuickText = { showQuickTextDialog = true }
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(modifier = Modifier.weight(1f)) {
                                    PageSelectionWorkspace(
                                        pages = pageItems,
                                        selectedCount = selectedPagesCount,
                                        onTogglePage = { idx ->
                                            val item = pageItems[idx]
                                            pageItems[idx] = item.copy(selected = !item.selected)
                                        },
                                        onRotatePage = { idx ->
                                            val item = pageItems[idx]
                                            pageItems[idx] = item.copy(rotationDegrees = (item.rotationDegrees + 90) % 360)
                                        },
                                        onDeletePage = { idx -> pageItems.removeAt(idx) },
                                        onZoomPage = { item -> zoomedPageItem = item },
                                        onSelectAll = {
                                            for (i in pageItems.indices) pageItems[i] = pageItems[i].copy(selected = true)
                                        },
                                        onDeselectAll = {
                                            for (i in pageItems.indices) pageItems[i] = pageItems[i].copy(selected = false)
                                        },
                                        onOpenRangePicker = { showRangeDialog = true },
                                        onClearAll = {
                                            if (pageItems.isNotEmpty()) {
                                                glassModal = GlassModalState(
                                                    title = "Bersihkan Semua Halaman?",
                                                    message = "Hapus semua halaman dari pratinjau?",
                                                    isConfirm = true,
                                                    confirmLabel = "Bersihkan",
                                                    cancelLabel = "Batal",
                                                    onConfirm = { pageItems.clear() }
                                                )
                                            }
                                        },
                                        onOpenPdf = { pdfPickerLauncher.launch(arrayOf("application/pdf")) },
                                        onOpenImages = { multiImagePickerLauncher.launch(arrayOf("image/*")) }
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                UsbPrinterSelectorCard(
                                    printers = connectedPrinters,
                                    selectedPrinter = selectedPrinter,
                                    onSelectPrinter = { usbManager.selectPrinter(it) },
                                    onRequestPerm = { usbManager.requestPermissionForSelected() },
                                    onScan = { usbManager.scanUsbPrinters(true) }
                                )
                                PrintSettingsCard(
                                    config = jobConfig,
                                    onConfigChange = { jobConfig = it }
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                            }
                        }
                    }

                    Box(modifier = Modifier.padding(12.dp)) {
                        PrimaryPrintActionFooter(
                            selectedCount = selectedPagesCount,
                            totalCount = pageItems.size,
                            copies = jobConfig.copies,
                            onPrintUsb = startUsbPrintAction,
                            onSystemPrint = {
                                val chosen = pageItems.filter { it.selected }
                                if (chosen.isNotEmpty()) {
                                    onSystemPrintFallback(chosen, jobConfig)
                                } else {
                                    glassModal = GlassModalState(
                                        title = "Pilih Halaman Dulu",
                                        message = "Centang minimal 1 halaman atau gambar untuk dicetak.",
                                        isSuccess = false
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Loading file overlay
    if (isLoadingFiles) {
        Dialog(onDismissRequest = {}) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = BgGlass),
                border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = AccentCyan)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = loadingMessage,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    // Active USB Print Progress Modal
    if (printProgress.isPrinting) {
        PrintProgressGlassDialog(
            state = printProgress,
            onCancel = { usbManager.cancelActivePrint() }
        )
    }

    // Page Range Selector Dialog (e.g., "1-3, 5")
    if (showRangeDialog) {
        PageRangeSelectorModal(
            totalPages = pageItems.size,
            onDismiss = { showRangeDialog = false },
            onApplyRange = { selectedIndices ->
                for (i in pageItems.indices) {
                    pageItems[i] = pageItems[i].copy(selected = i in selectedIndices)
                }
                showRangeDialog = false
            }
        )
    }

    // Quick Text / Nota Creator Dialog
    if (showQuickTextDialog) {
        QuickTextCreatorModal(
            onDismiss = { showQuickTextDialog = false },
            onAddPage = { title, content ->
                val page = DocumentRenderEngine.createQuickTextPage(title, content)
                pageItems.add(page)
                showQuickTextDialog = false
            }
        )
    }

    // High-Res Page Zoom Modal
    zoomedPageItem?.let { item ->
        ZoomPagePreviewModal(
            item = item,
            onDismiss = { zoomedPageItem = null },
            onToggleSelect = {
                val idx = pageItems.indexOfFirst { it.id == item.id }
                if (idx >= 0) {
                    val updated = pageItems[idx].copy(selected = !pageItems[idx].selected)
                    pageItems[idx] = updated
                    zoomedPageItem = updated
                }
            },
            onRotate = {
                val idx = pageItems.indexOfFirst { it.id == item.id }
                if (idx >= 0) {
                    val updated = pageItems[idx].copy(rotationDegrees = (pageItems[idx].rotationDegrees + 90) % 360)
                    pageItems[idx] = updated
                    zoomedPageItem = updated
                }
            }
        )
    }

    // MaoneArt Glassmorphism Alert / Confirm Modal (100% Symmetrical 2-Col Grid)
    glassModal?.let { modal ->
        MaoneArtGlassModal(
            state = modal,
            onDismiss = { glassModal = null }
        )
    }
}

@Composable
private fun TopStudioHeader(
    selectedPrinter: ConnectedUsbPrinter?,
    connectedCount: Int,
    onScanClick: () -> Unit,
    onTestPrintClick: () -> Unit,
    onEjectResetClick: () -> Unit
) {
    Surface(
        color = BgCard,
        tonalElevation = 4.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            Brush.linearGradient(listOf(AccentCyan, AccentBlue))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Print,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "MaoneArt Print",
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(AccentCyan.copy(alpha = 0.18f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "USB OTG EPSON",
                                color = AccentCyan,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    val statusColor = if (selectedPrinter != null) AccentEmerald else AccentAmber
                    val statusLabel = if (selectedPrinter != null) {
                        "Terhubung: ${selectedPrinter.displayName}" +
                            if (!selectedPrinter.hasPermission) " (Klik Izinkan USB)" else " • Siap"
                    } else {
                        "Menunggu Kabel OTG Printer Epson..."
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                OutlinedButton(
                    onClick = onTestPrintClick,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, BorderSubtle)
                ) {
                    Icon(Icons.Default.Tune, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Tes Print", color = TextPrimary, fontSize = 12.sp)
                }

                IconButton(
                    onClick = onEjectResetClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgGlass)
                ) {
                    Icon(Icons.Default.Eject, contentDescription = "Keluarkan Kertas / Reset", tint = AccentAmber, modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = onScanClick,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(BgGlass)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Scan USB OTG", tint = AccentCyan, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun FileOpenersCard(
    onOpenPdf: () -> Unit,
    onOpenImages: () -> Unit,
    onOpenAny: () -> Unit,
    onOpenQuickText: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "BUKA FILE DOKUMEN ATAU FOTO (PDF, JPG, PNG)",
                color = TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Buka PDF
                Button(
                    onClick = onOpenPdf,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose.copy(alpha = 0.9f)),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Buka PDF", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Buka JPG / PNG
                Button(
                    onClick = onOpenImages,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("JPG / PNG", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                // Ketik Nota / Teks
                OutlinedButton(
                    onClick = onOpenQuickText,
                    modifier = Modifier.height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, BorderSubtle),
                    contentPadding = PaddingValues(horizontal = 10.dp)
                ) {
                    Icon(Icons.Default.EditNote, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Teks", color = TextPrimary, fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun UsbPrinterSelectorCard(
    printers: List<ConnectedUsbPrinter>,
    selectedPrinter: ConnectedUsbPrinter?,
    onSelectPrinter: (ConnectedUsbPrinter) -> Unit,
    onRequestPerm: () -> Unit,
    onScan: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(
            1.dp,
            if (selectedPrinter != null) AccentEmerald.copy(alpha = 0.5f) else BorderSubtle
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Usb, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "KONEKSI KABEL USB OTG EPSON",
                        color = TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "Scan Ulang",
                    color = AccentCyan,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable { onScan() }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (printers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BgDeep)
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "Belum ada printer USB OTG terdeteksi",
                            color = AccentAmber,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Colokkan kabel USB printer Epson ke konektor OTG Tablet dan pastikan printer menyala.",
                            color = TextMuted,
                            fontSize = 11.sp
                        )
                    }
                }
            } else {
                printers.forEach { p ->
                    val isSel = p.deviceName == selectedPrinter?.deviceName
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isSel) AccentEmerald.copy(alpha = 0.14f) else BgDeep)
                            .border(
                                1.dp,
                                if (isSel) AccentEmerald else BorderSubtle,
                                RoundedCornerShape(12.dp)
                            )
                            .clickable { onSelectPrinter(p) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = p.displayName,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "${p.detectedCategory} • VID: 0x${p.vendorId.toString(16).uppercase()}",
                                color = TextMuted,
                                fontSize = 11.sp
                            )
                        }
                        if (!p.hasPermission) {
                            Button(
                                onClick = onRequestPerm,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentAmber),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Izinkan USB", color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        } else {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = "Siap",
                                tint = AccentEmerald,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun PrintSettingsCard(
    config: PrintJobConfig,
    onConfigChange: (PrintJobConfig) -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "PENGATURAN KERTAS, WARNA & DRIVER EPSON",
                    color = TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Ukuran Kertas
            SettingSectionLabel("Ukuran Kertas")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PaperSizeOption.entries.forEach { size ->
                    ChoiceChipPill(
                        text = size.label,
                        selected = config.paperSize == size,
                        onClick = { onConfigChange(config.copy(paperSize = size)) }
                    )
                }
            }

            // Warna vs Hitam Putih & Orientasi
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingSectionLabel("Mode Warna")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ColorModeOption.entries.forEach { mode ->
                            ChoiceChipPill(
                                modifier = Modifier.weight(1f),
                                text = if (mode == ColorModeOption.COLOR) "Warna" else "Hitam Putih",
                                selected = config.colorMode == mode,
                                onClick = { onConfigChange(config.copy(colorMode = mode)) }
                            )
                        }
                    }
                }
                Column(modifier = Modifier.weight(1f)) {
                    SettingSectionLabel("Orientasi")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PageOrientation.entries.forEach { ori ->
                            ChoiceChipPill(
                                modifier = Modifier.weight(1f),
                                text = if (ori == PageOrientation.PORTRAIT) "Portrait" else "Landscape",
                                selected = config.orientation == ori,
                                onClick = { onConfigChange(config.copy(orientation = ori)) }
                            )
                        }
                    }
                }
            }

            // Skala & Tata Letak Foto/Dokumen
            SettingSectionLabel("Tata Letak / Skala Halaman")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ScaleFitMode.entries.forEach { sm ->
                    ChoiceChipPill(
                        text = sm.label,
                        selected = config.scaleMode == sm,
                        onClick = { onConfigChange(config.copy(scaleMode = sm)) }
                    )
                }
            }

            // Protokol Driver Epson
            SettingSectionLabel("Protokol Driver Epson USB OTG")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                EpsonDriverProtocol.entries.forEach { proto ->
                    ChoiceChipPill(
                        text = proto.label,
                        selected = config.protocol == proto,
                        onClick = { onConfigChange(config.copy(protocol = proto)) }
                    )
                }
            }

            // Kualitas & Jumlah Rangkap (Copies)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    SettingSectionLabel("Kualitas Cetak")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        PrintQualityOption.entries.forEach { q ->
                            ChoiceChipPill(
                                text = when (q) {
                                    PrintQualityOption.DRAFT -> "Draft"
                                    PrintQualityOption.NORMAL -> "Normal"
                                    PrintQualityOption.HIGH -> "Tinggi"
                                },
                                selected = config.quality == q,
                                onClick = { onConfigChange(config.copy(quality = q)) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(horizontalAlignment = Alignment.End) {
                    SettingSectionLabel("Jumlah Rangkap")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        SmallStepButton("-") {
                            if (config.copies > 1) onConfigChange(config.copy(copies = config.copies - 1))
                        }
                        Text(
                            text = "${config.copies}x",
                            color = TextPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                        SmallStepButton("+") {
                            if (config.copies < 99) onConfigChange(config.copy(copies = config.copies + 1))
                        }
                    }
                }
            }

            // Kecerahan & Kontras Cetakan
            SettingSectionLabel("Kecerahan (${config.brightnessBoost}) & Ketajaman Kontras (${config.contrastBoost})")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = config.brightnessBoost.toFloat(),
                        onValueChange = { onConfigChange(config.copy(brightnessBoost = it.toInt())) },
                        valueRange = -20f..20f,
                        colors = SliderDefaults.colors(thumbColor = AccentCyan, activeTrackColor = AccentCyan)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Slider(
                        value = config.contrastBoost.toFloat(),
                        onValueChange = { onConfigChange(config.copy(contrastBoost = it.toInt())) },
                        valueRange = -20f..20f,
                        colors = SliderDefaults.colors(thumbColor = AccentBlue, activeTrackColor = AccentBlue)
                    )
                }
            }
        }
    }
}

@Composable
private fun PageSelectionWorkspace(
    pages: List<PrintablePageItem>,
    selectedCount: Int,
    onTogglePage: (Int) -> Unit,
    onRotatePage: (Int) -> Unit,
    onDeletePage: (Int) -> Unit,
    onZoomPage: (PrintablePageItem) -> Unit,
    onSelectAll: () -> Unit,
    onDeselectAll: () -> Unit,
    onOpenRangePicker: () -> Unit,
    onClearAll: () -> Unit,
    onOpenPdf: () -> Unit,
    onOpenImages: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = BgCard),
        border = BorderStroke(1.dp, BorderSubtle),
        modifier = Modifier.fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
            // Selection Top Toolbar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "PILIH HALAMAN / GAMBAR YANG MAU DICETAK",
                        color = TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (pages.isEmpty())
                            "Belum ada dokumen dibuka"
                        else
                            "Terpilih $selectedCount dari ${pages.size} halaman (Klik kartu untuk memilih/batal)",
                        color = if (selectedCount > 0) AccentCyan else TextMuted,
                        fontSize = 11.sp
                    )
                }

                if (pages.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SmallToolbarActionChip(
                            icon = Icons.Default.DoneAll,
                            label = "Semua",
                            onClick = onSelectAll
                        )
                        SmallToolbarActionChip(
                            icon = Icons.Default.RemoveDone,
                            label = "Reset",
                            onClick = onDeselectAll
                        )
                        if (pages.size > 1) {
                            SmallToolbarActionChip(
                                icon = Icons.Default.FilterList,
                                label = "Rentang",
                                onClick = onOpenRangePicker
                            )
                        }
                        IconButton(
                            onClick = onClearAll,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(AccentRose.copy(alpha = 0.15f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "Bersihkan",
                                tint = AccentRose,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (pages.isEmpty()) {
                // Empty state for tablet & phone
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(BgDeep)
                        .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = AccentCyan.copy(alpha = 0.7f),
                            modifier = Modifier.size(56.dp)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Pilih File PDF, JPG, atau PNG",
                            color = TextPrimary,
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Semua halaman PDF dan foto akan ditampilkan di sini.\nAnda bebas mencentang halaman mana saja yang mau dicetak ke printer Epson via USB OTG.",
                            color = TextMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Button(
                                onClick = onOpenPdf,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentRose),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pilih File PDF", fontWeight = FontWeight.Bold)
                            }
                            Button(
                                onClick = onOpenImages,
                                colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Default.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Pilih Foto JPG/PNG", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 165.dp),
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    itemsIndexed(pages, key = { _, item -> item.id }) { idx, pageItem ->
                        SelectablePageCard(
                            indexNumber = idx + 1,
                            item = pageItem,
                            onToggle = { onTogglePage(idx) },
                            onRotate = { onRotatePage(idx) },
                            onDelete = { onDeletePage(idx) },
                            onZoom = { onZoomPage(pageItem) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectablePageCard(
    indexNumber: Int,
    item: PrintablePageItem,
    onToggle: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit,
    onZoom: () -> Unit
) {
    val borderColor = if (item.selected) AccentCyan else BorderSubtle
    val cardBg = if (item.selected) AccentCyan.copy(alpha = 0.1f) else BgDeep

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg),
        border = BorderStroke(if (item.selected) 2.dp else 1.dp, borderColor),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggle() }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            // Top bar of card: Page Badge + Selected Check
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (item.selected) AccentCyan else BgGlass)
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = "#$indexNumber",
                        color = if (item.selected) Color.Black else TextMuted,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = if (item.selected) "CETAK" else "LEWATI",
                        color = if (item.selected) AccentEmerald else TextMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = if (item.selected) AccentEmerald else TextMuted.copy(alpha = 0.35f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Page Thumbnail Preview
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = item.previewBitmap.asImageBitmap(),
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(4.dp)
                        .rotate(item.rotationDegrees.toFloat())
                )

                if (!item.selected) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.45f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "TIDAK DICETAK",
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color.Black.copy(alpha = 0.7f))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = item.title,
                color = TextPrimary,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = item.subtitle,
                color = TextMuted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Action Buttons on Card (Zoom, Rotate 90°, Delete)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(
                    onClick = onZoom,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgGlass)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = "Perbesar", tint = AccentCyan, modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = onRotate,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgGlass)
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = "Putar 90°", tint = TextPrimary, modifier = Modifier.size(16.dp))
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BgGlass)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Hapus", tint = AccentRose, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun PrimaryPrintActionFooter(
    selectedCount: Int,
    totalCount: Int,
    copies: Int,
    onPrintUsb: () -> Unit,
    onSystemPrint: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Button(
            onClick = onPrintUsb,
            modifier = Modifier
                .weight(1f)
                .height(54.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (selectedCount > 0) AccentEmerald else AccentBlue
            )
        ) {
            Icon(Icons.Default.Print, contentDescription = null, modifier = Modifier.size(22.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = if (selectedCount > 0)
                    "CETAK VIA USB OTG ($selectedCount Hal × ${copies}x)"
                else
                    "PILIH HALAMAN UNTUK CETAK",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }

        OutlinedButton(
            onClick = onSystemPrint,
            modifier = Modifier.height(54.dp),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, BorderSubtle)
        ) {
            Text(
                text = "Print\nSistem",
                color = TextPrimary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PrintProgressGlassDialog(
    state: PrintProgressState,
    onCancel: () -> Unit
) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = BgGlass),
            border = BorderStroke(1.5.dp, AccentCyan.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "MENCETAK KE PRINTER EPSON (USB OTG)",
                    color = AccentCyan,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Halaman ${state.currentPage} dari ${state.totalPages} • Rangkap ${state.currentCopy}/${state.totalCopies}",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = state.statusText,
                    color = TextMuted,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(16.dp))
                LinearProgressIndicator(
                    progress = { state.progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(6.dp)),
                    color = AccentEmerald,
                    trackColor = BgDeep
                )
                Spacer(modifier = Modifier.height(8.dp))
                val kbSent = state.bytesTransferred / 1024
                Text(
                    text = "${(state.progressFraction * 100).toInt()}% • Data USB Terkirim: $kbSent KB",
                    color = TextMuted,
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(18.dp))
                Button(
                    onClick = onCancel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentRose)
                ) {
                    Text("Batalkan & Keluarkan Kertas", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun PageRangeSelectorModal(
    totalPages: Int,
    onDismiss: () -> Unit,
    onApplyRange: (Set<Int>) -> Unit
) {
    var rangeText by remember { mutableStateOf("1-$totalPages") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = BgGlass),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Pilih Rentang Halaman Cetak",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Masukkan nomor halaman yang ingin dicetak (Total $totalPages halaman).\nContoh: 1-3, 5, 8-10",
                    color = TextMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = rangeText,
                    onValueChange = { rangeText = it },
                    singleLine = true,
                    label = { Text("Nomor Halaman (misal: 1-3, 5)") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChipPill(
                        text = "Halaman Ganjil",
                        selected = false,
                        onClick = {
                            val odds = (1..totalPages step 2).joinToString(",")
                            rangeText = odds
                        }
                    )
                    ChoiceChipPill(
                        text = "Halaman Genap",
                        selected = false,
                        onClick = {
                            val evens = (2..totalPages step 2).joinToString(",")
                            rangeText = evens
                        }
                    )
                }
                Spacer(modifier = Modifier.height(18.dp))

                // Symmetrical 2-Column Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Text("Batal", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            val parsed = parsePageRangeInput(rangeText, totalPages)
                            onApplyRange(parsed)
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text("Terapkan", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun parsePageRangeInput(input: String, maxPages: Int): Set<Int> {
    val result = mutableSetOf<Int>()
    val parts = input.split(",", ";", " ").map { it.trim() }.filter { it.isNotEmpty() }
    for (part in parts) {
        if (part.contains("-")) {
            val bounds = part.split("-").map { it.trim() }
            val start = bounds.getOrNull(0)?.toIntOrNull() ?: continue
            val end = bounds.getOrNull(1)?.toIntOrNull() ?: start
            val s = minOf(start, end).coerceIn(1, maxPages)
            val e = maxOf(start, end).coerceIn(1, maxPages)
            for (p in s..e) {
                result.add(p - 1)
            }
        } else {
            val single = part.toIntOrNull()
            if (single != null && single in 1..maxPages) {
                result.add(single - 1)
            }
        }
    }
    return result
}

@Composable
private fun QuickTextCreatorModal(
    onDismiss: () -> Unit,
    onAddPage: (String, String) -> Unit
) {
    var title by remember { mutableStateOf("Nota / Dokumen Cepat") }
    var body by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = BgGlass),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = "Ketik Dokumen Teks / Struk Cepat",
                    color = TextPrimary,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    singleLine = true,
                    label = { Text("Judul Halaman / Nota") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Isi Teks / Rincian Cetak...") },
                    minLines = 5,
                    maxLines = 10,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentCyan,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(18.dp))

                // Symmetrical 2-Column Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Text("Batal", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = {
                            if (body.isNotBlank() || title.isNotBlank()) {
                                onAddPage(title, body.ifBlank { title })
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                    ) {
                        Text("Tambahkan", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ZoomPagePreviewModal(
    item: PrintablePageItem,
    onDismiss: () -> Unit,
    onToggleSelect: () -> Unit,
    onRotate: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = BgGlass),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.5f)),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.88f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.title,
                            color = TextPrimary,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = item.subtitle,
                            color = TextMuted,
                            fontSize = 12.sp
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = onRotate,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Icon(Icons.Default.RotateRight, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Putar 90°", color = TextPrimary, fontSize = 12.sp)
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Tutup", tint = TextPrimary)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        bitmap = item.previewBitmap.asImageBitmap(),
                        contentDescription = item.title,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(12.dp)
                            .rotate(item.rotationDegrees.toFloat())
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Symmetrical 2-Column Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, BorderSubtle)
                    ) {
                        Text("Tutup Pratinjau", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onToggleSelect,
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (item.selected) AccentRose else AccentEmerald
                        )
                    ) {
                        Text(
                            text = if (item.selected) "Batalkan Pilihan Cetak" else "Pilih Halaman Ini",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

/**
 * MaoneArt Glassmorphism Modal (Strictly follows GEMINI.md Rule #5: 2-Column Symmetrical Grid)
 */
@Composable
private fun MaoneArtGlassModal(
    state: GlassModalState,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = BgGlass),
            border = BorderStroke(
                1.5.dp,
                when {
                    state.isConfirm -> AccentCyan.copy(alpha = 0.6f)
                    state.isSuccess -> AccentEmerald.copy(alpha = 0.6f)
                    else -> AccentAmber.copy(alpha = 0.6f)
                }
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = state.title,
                    color = TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = state.message,
                    color = TextMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                // 100% Symmetrical 2-Column Grid Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.isConfirm && state.onConfirm != null) {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Text(state.cancelLabel, color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = {
                                val act = state.onConfirm
                                onDismiss()
                                act.invoke()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentCyan)
                        ) {
                            Text(state.confirmLabel, color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    } else {
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, BorderSubtle)
                        ) {
                            Text("Tutup", color = TextPrimary, fontWeight = FontWeight.SemiBold)
                        }
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (state.isSuccess) AccentEmerald else AccentCyan
                            )
                        ) {
                            Text("OK, Mengerti", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingSectionLabel(text: String) {
    Text(
        text = text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun ChoiceChipPill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) AccentCyan.copy(alpha = 0.2f) else BgDeep)
            .border(
                1.dp,
                if (selected) AccentCyan else BorderSubtle,
                RoundedCornerShape(10.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) AccentCyan else TextPrimary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines = 1
        )
    }
}

@Composable
private fun SmallStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BgGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = TextPrimary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )
    }
}

@Composable
private fun SmallToolbarActionChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(BgGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = AccentCyan, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TabPillButton(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) AccentCyan else Color.Transparent)
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = if (selected) Color.Black else TextMuted,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
