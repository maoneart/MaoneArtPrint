package id.maoneart.print

import android.graphics.Bitmap
import android.net.Uri

enum class SourceFileType {
    PDF_PAGE,
    IMAGE,
    QUICK_TEXT
}

enum class EpsonDriverProtocol(val label: String, val shortDesc: String) {
    AUTO("Otomatis (Auto-Detect Epson)", "Deteksi otomatis seri L-Series Inkjet atau TM Thermal"),
    ESCPR("Epson Inkjet / EcoTank (ESC/P-R)", "Untuk Epson L120, L121, L1110, L3110, L3210, L3250, L805, dll."),
    ESCP2("Epson Inkjet Standar (ESC/P2)", "Mode kompatibilitas universal printer inkjet & dot-matrix Epson"),
    ESCPOS_80("Epson Kasir Thermal 80mm (ESC/POS)", "Untuk Epson TM-T82, TM-T88, dan printer struk 80mm"),
    ESCPOS_58("Epson Kasir Thermal 58mm (ESC/POS)", "Untuk printer struk thermal 58mm via USB OTG")
}

enum class PaperSizeOption(
    val label: String,
    val widthMm: Float,
    val heightMm: Float,
    val escprMediaCode: Byte
) {
    A4("A4 (210 × 297 mm)", 210f, 297f, 0x00),
    F4("F4 / Folio (215 × 330 mm)", 215f, 330f, 0x08),
    LETTER("Letter (216 × 279 mm)", 215.9f, 279.4f, 0x01),
    PHOTO_4R("Foto 4R (102 × 152 mm)", 102f, 152f, 0x05),
    PHOTO_5R("Foto 5R (127 × 178 mm)", 127f, 178f, 0x06),
    THERMAL_80("Struk 80mm", 72f, 200f, 0x00),
    THERMAL_58("Struk 58mm", 48f, 200f, 0x00)
}

enum class ColorModeOption(val label: String) {
    COLOR("Warna (Color)"),
    BLACK_WHITE("Hitam Putih (B&W)")
}

enum class PrintQualityOption(val label: String, val dpi: Int) {
    DRAFT("Draft Cepat", 180),
    NORMAL("Normal (360 DPI)", 360),
    HIGH("Tinggi / Foto Tajam", 360)
}

enum class PageOrientation(val label: String) {
    PORTRAIT("Tegak (Portrait)"),
    LANDSCAPE("Mendatar (Landscape)")
}

enum class ScaleFitMode(val label: String) {
    FIT_PAGE("Sesuaikan Kertas (Utuh)"),
    FILL_PAGE("Penuh Kertas (Full/Crop)"),
    MARGINLESS("Tanpa Tepi (Borderless)"),
    PAS_FOTO_3X4("Pas Foto 3×4 cm (4x)"),
    PAS_FOTO_4X6("Pas Foto 4×6 cm (4x)")
}

data class PrintJobConfig(
    val protocol: EpsonDriverProtocol = EpsonDriverProtocol.AUTO,
    val paperSize: PaperSizeOption = PaperSizeOption.A4,
    val colorMode: ColorModeOption = ColorModeOption.COLOR,
    val quality: PrintQualityOption = PrintQualityOption.NORMAL,
    val orientation: PageOrientation = PageOrientation.PORTRAIT,
    val scaleMode: ScaleFitMode = ScaleFitMode.FIT_PAGE,
    val copies: Int = 1,
    val brightnessBoost: Int = 0, // -20 to +20
    val contrastBoost: Int = 0    // -20 to +20
)

data class PrintablePageItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val fileName: String,
    val sourceType: SourceFileType,
    val uri: Uri? = null,
    val pdfPageIndex: Int = 0,
    val previewBitmap: Bitmap,
    val selected: Boolean = true,
    val rotationDegrees: Int = 0
)

data class ConnectedUsbPrinter(
    val deviceName: String,
    val displayName: String,
    val manufacturer: String,
    val vendorId: Int,
    val productId: Int,
    val isEpson: Boolean,
    val hasPermission: Boolean,
    val detectedCategory: String
)

data class PrintProgressState(
    val isPrinting: Boolean = false,
    val currentPage: Int = 0,
    val totalPages: Int = 0,
    val currentCopy: Int = 1,
    val totalCopies: Int = 1,
    val statusText: String = "",
    val progressFraction: Float = 0f,
    val bytesTransferred: Long = 0L
)
