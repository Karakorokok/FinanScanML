package com.inhs.finansca

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class UploadActivity : BaseActivity() {

    private lateinit var imgScanResult: ImageView
    private lateinit var btnUpload: Button
    private lateinit var btnCompute: Button
    private lateinit var txtExtractedText: TextView
    private val REQUEST_PDF_UPLOAD = 2

    private val REQUEST_IMAGE_CAPTURE = 1
    private var imageUri: Uri? = null
    private val checkedItems = BooleanArray(25) { true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_upload)

        imgScanResult = findViewById(R.id.img_scanResult)
        btnUpload = findViewById(R.id.btn_upload)
        btnCompute = findViewById(R.id.btn_Compute)
        txtExtractedText = findViewById(R.id.txt_extracted_text)

        btnUpload.setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT)
            intent.type = "application/pdf"
            startActivityForResult(intent, REQUEST_PDF_UPLOAD)
        }

        btnCompute.setOnClickListener {
            val extractedText = txtExtractedText.text.toString()
            val financialData = parseExtractedText(extractedText)

            if (financialData.isEmpty()) {
                Toast.makeText(this, "No financial data available for computation.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val computedRatios = computeFinancialRatios(financialData)
            showResultDialog(computedRatios)
        }

        findViewById<Button>(R.id.btn_Settings).setOnClickListener {
            showSettingsDialog()
        }
    }

    private fun createImageFile(): File? {
        return try {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile("IMG_${timestamp}_", ".jpg", storageDir)
        } catch (e: IOException) {
            e.printStackTrace()
            null
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_PDF_UPLOAD && resultCode == Activity.RESULT_OK && data?.data != null) {
            val pdfUri = data.data!!
            processPdf(pdfUri)
        }

    }

//    private fun processPdf(pdfUri: Uri) {
//        try {
//            val fileDescriptor = contentResolver.openFileDescriptor(pdfUri, "r") ?: return
//            val renderer = PdfRenderer(fileDescriptor)
//            val page = renderer.openPage(0)
//
//            val bitmap = Bitmap.createBitmap(page.width, page.height, Bitmap.Config.ARGB_8888)
//            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
//
//            page.close()
//            renderer.close()
//            fileDescriptor.close()
//
//            imgScanResult.setImageBitmap(bitmap)  // Show the extracted image
//            extractTextFromImage(bitmap)  // Process with OCR
//
//        } catch (e: IOException) {
//            e.printStackTrace()
//            Toast.makeText(this, "Failed to process PDF", Toast.LENGTH_SHORT).show()
//        }
//    }

    private fun processPdf(pdfUri: Uri) {
        try {
            val fileDescriptor = contentResolver.openFileDescriptor(pdfUri, "r") ?: return
            val renderer = PdfRenderer(fileDescriptor)
            val page = renderer.openPage(0)

            // Increase resolution by rendering at a larger size
            val scale = 2  // Increase to 2x resolution
            val width = page.width * scale
            val height = page.height * scale
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val matrix = android.graphics.Matrix()
            matrix.setScale(scale.toFloat(), scale.toFloat()) // Scale up

            page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            page.close()
            renderer.close()
            fileDescriptor.close()

            // Preprocess for better OCR
            val enhancedBitmap = preprocessImage(bitmap)
            imgScanResult.setImageBitmap(enhancedBitmap)

            // Extract text using OCR
            extractTextFromImage(enhancedBitmap)

        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Failed to process PDF", Toast.LENGTH_SHORT).show()
        }
    }



    private fun fixImageRotation(bitmap: Bitmap, imageUri: Uri): Bitmap {
        val exif = ExifInterface(contentResolver.openInputStream(imageUri)!!)
        val rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }

        return if (rotation != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotation.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

//    private fun preprocessImage(bitmap: Bitmap): Bitmap {
//        val bmpGray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
//        val canvas = android.graphics.Canvas(bmpGray)
//        val paint = Paint()
//        val colorMatrix = ColorMatrix()
//
//        colorMatrix.setSaturation(0f)
//
//        val contrastMatrix = ColorMatrix()
//        val contrast = 1.5f // Adjust contrast level
//        contrastMatrix.setScale(contrast, contrast, contrast, 1f)
//        colorMatrix.postConcat(contrastMatrix)
//
//        val filter = ColorMatrixColorFilter(colorMatrix)
//        paint.colorFilter = filter
//        canvas.drawBitmap(bitmap, 0f, 0f, paint)
//
//        return bmpGray
//    }

    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        val bmpGray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmpGray)
        val paint = Paint()

        // Convert to grayscale
        val colorMatrix = ColorMatrix()
        colorMatrix.setSaturation(0f)

        // Increase contrast and brightness
        val contrastMatrix = ColorMatrix()
        val contrast = 1.8f  // 🔥 Boost contrast
        contrastMatrix.setScale(contrast, contrast, contrast, 1f)
        colorMatrix.postConcat(contrastMatrix)

        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        // Apply sharpening filter (reduces fuzzy dots)
        return sharpenImage(bmpGray)
    }

    private fun sharpenImage(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val sharpenedBitmap = Bitmap.createBitmap(width, height, bitmap.config ?: Bitmap.Config.ARGB_8888)


        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val kernel = arrayOf(
            floatArrayOf(0f, -1f, 0f),
            floatArrayOf(-1f, 5f, -1f),
            floatArrayOf(0f, -1f, 0f)
        )

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                var r = 0
                var g = 0
                var b = 0
                for (ky in -1..1) {
                    for (kx in -1..1) {
                        val pixel = pixels[(y + ky) * width + (x + kx)]
                        val kernelValue = kernel[ky + 1][kx + 1]
                        r += ((pixel shr 16 and 0xFF) * kernelValue).toInt()
                        g += ((pixel shr 8 and 0xFF) * kernelValue).toInt()
                        b += ((pixel and 0xFF) * kernelValue).toInt()
                    }
                }
                r = r.coerceIn(0, 255)
                g = g.coerceIn(0, 255)
                b = b.coerceIn(0, 255)
                sharpenedBitmap.setPixel(x, y, (0xFF shl 24) or (r shl 16) or (g shl 8) or b)
            }
        }
        return sharpenedBitmap
    }
    // Adaptive thresholding to remove noise & artifacts
    private fun applyThresholding(bitmap: Bitmap): Bitmap {
        val newBitmap = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)

        for (i in pixels.indices) {
            val color = pixels[i] and 0xFF  // Extract grayscale value
            pixels[i] = if (color > 128) 0xFFFFFFFF.toInt() else 0xFF000000.toInt() // Binarization
        }

        newBitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return newBitmap
    }

    private fun extractTextFromImage(bitmap: Bitmap) {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val items = mutableListOf<Pair<String, Float>>()
                val values = mutableListOf<Pair<String, Float>>()
                var foundItemHeader = false
                var foundValueHeader = false

                for (block in visionText.textBlocks) {
                    for (line in block.lines) {
                        val text = line.text.trim()
                        val boundingBox = line.boundingBox

                        if (boundingBox != null) {
                            val xPosition = boundingBox.left
                            val yPosition = boundingBox.top

                            // Detect headers explicitly
                            if (text.equals("Item", ignoreCase = true)) {
                                foundItemHeader = true
                                continue
                            }
                            if (text.equals("Value", ignoreCase = true)) {
                                foundValueHeader = true
                                continue
                            }

                            // Classify as item or value based on position
                            if (xPosition < bitmap.width / 2) {
                                items.add(text to yPosition.toFloat())
                            } else {
                                values.add(text to yPosition.toFloat())
                            }
                        }
                    }
                }

                // Sort items and values by their Y-position (top to bottom)
                items.sortBy { it.second }
                values.sortBy { it.second }

                val extractedData = StringBuilder()

                // Ensure headers exist
                extractedData.append("Item: Value\n")

                for (i in items.indices) {
                    val item = items.getOrNull(i)?.first ?: ""
                    val value = values.getOrNull(i)?.first ?: ""
                    extractedData.append("$item: $value\n")
                }

                txtExtractedText.text = extractedData.toString()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Failed to extract text: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }


    private fun displayExtractedText(visionText: Text) {
        if (visionText.text.isNotEmpty()) {
            val rawText = visionText.text
            val correctedText = fixCommaPeriodIssues(rawText)
            txtExtractedText.text = correctedText
        }else {
            txtExtractedText.text = "No text found in image."
        }
    }

    private fun fixCommaPeriodIssues(text: String): String {
        return text.replace(Regex("\\b(\\d+)\\.(\\d{3})\\b")) { matchResult ->
            val numberPart1 = matchResult.groupValues[1]
            val numberPart2 = matchResult.groupValues[2]
            "$numberPart1,$numberPart2"
        }
    }

    private fun parseExtractedText(text: String): Map<String, Float> {
        val financialData = mutableMapOf<String, Float>()
        val lines = text.split("\n")

        for (line in lines) {
            val parts = line.split(":").map { it.trim() }
            if (parts.size == 2) {
                val key = parts[0]
                val value = parts[1].replace(",", "").toFloatOrNull() // Convert to Float, handling commas
                if (value != null) {
                    financialData[key] = value
                }
            }
        }
        return financialData
    }

//    private fun computeFinancialRatios(data: Map<String, Float>): Map<String, Float> {
//        return mapOf(
//            "Current Ratio" to safeDivide(data["Current Assets"], data["Current Liabilities"]),
//            "Debt-to-Equity Ratio" to safeDivide(data["Total Liabilities"], data["Total Equity"]),
//            "Net Profit Margin" to safeDivide(data["Net Income"], data["Total Revenue"]),
//            "Gross Profit Margin" to safeDivide(data["Gross Profit"], data["Total Revenue"]),
//            "Inventory Turnover" to safeDivide(data["Cost of Goods Sold"], data["Inventory"]),
//            "Return on Assets (ROA)" to safeDivide(data["Net Income"], data["Total Assets"]),
//            "Return on Equity (ROE)" to safeDivide(data["Net Income"], data["Total Equity"]),
//            "Earnings Per Share (EPS)" to safeDivide(data["Net Income"], data["Shares Outstanding"]),
//            "Price-to-Earnings Ratio (P/E)" to safeDivide(data["Market Price per Share"], safeDivide(data["Net Income"], data["Shares Outstanding"])),
//            "Debt Ratio" to safeDivide(data["Total Liabilities"], data["Total Assets"]),
//            "Operating Profit Margin" to safeDivide(data["EBIT"], data["Total Revenue"]),
//            "Interest Coverage Ratio" to safeDivide(data["EBIT"], data["Interest Expense"]),
//            "Quick Ratio" to safeDivide(data["Current Assets"]?.minus(data["Inventory"] ?: 0f), data["Current Liabilities"]),
//            "Dividend Payout Ratio" to safeDivide(data["Dividends Paid"], data["Net Income"]),
//            "Retention Ratio" to safeDivide(data["Retained Earnings"], data["Net Income"]),
//            "Receivables Turnover" to safeDivide(data["Sales"], data["Accounts Receivable"]),
//            "Payables Turnover" to safeDivide(data["Cost of Goods Sold"], data["Accounts Payable"]),
//            "Asset Turnover" to safeDivide(data["Sales"], data["Total Assets"])
//        )
//    }

    private fun getValue(data: Map<String, Float>, primary: String, alternative: String? = null): Float {
        return data[primary] ?: alternative?.let { data[it] } ?: 0f
    }

    private fun computeFinancialRatios(data: Map<String, Float>): Map<String, Float> {
        return mapOf(
            "Current Ratio" to safeDivide(getValue(data, "Current Assets", "Total Current Assets"), getValue(data, "Current Liabilities", "Total Current Liabilities")),
            "Debt-to-Equity Ratio" to safeDivide(getValue(data, "Total Liabilities"), getValue(data, "Total Equity")),
            "Net Profit Margin" to safeDivide(getValue(data, "Net Income"), getValue(data, "Total Revenue")),
            "Gross Profit Margin" to safeDivide(getValue(data, "Gross Profit"), getValue(data, "Total Revenue")),
            "Inventory Turnover" to safeDivide(getValue(data, "Cost of Goods Sold"), getValue(data, "Inventory")),
            "Return on Assets (ROA)" to safeDivide(getValue(data, "Net Income"), getValue(data, "Total Assets")),
            "Return on Equity (ROE)" to safeDivide(getValue(data, "Net Income"), getValue(data, "Total Equity")),
            "Earnings Per Share (EPS)" to safeDivide(getValue(data, "Net Income"), getValue(data, "Shares Outstanding")),
            "Price-to-Earnings Ratio (P/E)" to safeDivide(getValue(data, "Market Price per Share"), safeDivide(getValue(data, "Net Income"), getValue(data, "Shares Outstanding"))),
            "Debt Ratio" to safeDivide(getValue(data, "Total Liabilities"), getValue(data, "Total Assets")),
            "Operating Profit Margin" to safeDivide(getValue(data, "EBIT"), getValue(data, "Total Revenue")),
            "Interest Coverage Ratio" to safeDivide(getValue(data, "EBIT"), getValue(data, "Interest Expense")),
            "Quick Ratio" to safeDivide(getValue(data, "Current Assets", "Total Current Assets") - getValue(data, "Inventory"), getValue(data, "Current Liabilities", "Total Current Liabilities")),
            "Dividend Payout Ratio" to safeDivide(getValue(data, "Dividends Paid"), getValue(data, "Net Income")),
            "Retention Ratio" to safeDivide(getValue(data, "Retained Earnings"), getValue(data, "Net Income")),
            "Receivables Turnover" to safeDivide(getValue(data, "Sales"), getValue(data, "Accounts Receivable")),
            "Payables Turnover" to safeDivide(getValue(data, "Cost of Goods Sold"), getValue(data, "Accounts Payable")),
            "Asset Turnover" to safeDivide(getValue(data, "Sales"), getValue(data, "Total Assets"))
        )
    }


    private fun safeDivide(numerator: Float?, denominator: Float?): Float {
        return if (numerator != null && denominator != null && denominator != 0f) {
            numerator / denominator
        }
        else {
            0f
        }
    }

    private fun showResultDialog(ratios: Map<String, Float>) {
        val options = arrayOf(
            "Current Ratio", "Debt-to-Equity Ratio", "Net Profit Margin",
            "Gross Profit Margin", "Inventory Turnover", "Return on Assets (ROA)",
            "Return on Equity (ROE)", "Earnings Per Share (EPS)", "Price-to-Earnings Ratio (P/E)",
            "Debt Ratio", "Operating Profit Margin", "Interest Coverage Ratio",
            "Quick Ratio", "Dividend Payout Ratio", "Retention Ratio",
            "Receivables Turnover", "Payables Turnover", "Asset Turnover"
        )

        val filteredRatios = ratios.entries.filterIndexed { index, _ ->
            checkedItems[index]
        }.joinToString("\n") { "${it.key}: ${"%.2f".format(it.value)}" }

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Computed Financial Ratios")
            .setMessage(filteredRatios.ifEmpty { "No ratios selected for computation." })
            .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
            .show()
    }

    private fun showSettingsDialog() {
        val options = arrayOf(
            "Current Ratio", "Debt Ratio", "Debt-to-Equity Ratio",
            "Net Profit Margin", "Gross Profit Margin", "Inventory Turnover",
            "Return on Assets (ROA)", "Return on Equity (ROE)", "Earnings Per Share (EPS)",
            "Price-to-Earnings Ratio (P/E)", "Operating Profit Margin", "Interest Coverage Ratio",
            "Quick Ratio", "Dividend Payout Ratio", "Retention Ratio",
            "Receivables Turnover", "Accounts Payable Turnover", "Payables Turnover",
            "Asset Turnover", "Average Collection Period", "Days Inventories", "Days Payable",
            "Total Assets Turnover", "Operating Cycle", "Cash Conversion Cycle"
        )

        val builder = android.app.AlertDialog.Builder(this)
        builder.setTitle("Select Ratios to Compute")
            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                Toast.makeText(this, "Settings updated!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)

        builder.create().show()
    }


}
