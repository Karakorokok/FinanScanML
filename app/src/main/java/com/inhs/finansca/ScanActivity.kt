package com.inhs.finansca

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
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

class ScanActivity : BaseActivity() {

    private lateinit var imgScanResult: ImageView
    private lateinit var btnStartScan: Button
    private lateinit var btnCompute: Button
    private lateinit var txtExtractedText: TextView
    private lateinit var txtRawText: TextView

    private val CAMERA_REQUEST_CODE = 100
    private val REQUEST_IMAGE_CAPTURE = 1
    private var imageUri: Uri? = null
    private val checkedItems = BooleanArray(25) { true }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_scan)

        imgScanResult = findViewById(R.id.img_scanResult)
        btnStartScan = findViewById(R.id.btn_startScan)
        btnCompute = findViewById(R.id.btn_Compute)
        txtExtractedText = findViewById(R.id.txt_extracted_text)
        txtRawText = findViewById(R.id.txt_raw_text)

        btnStartScan.setOnClickListener {
            checkCameraPermission()
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

        findViewById<Button>(R.id.btn_txtRecognition).setOnClickListener {
            val extractedText = txtRawText.text.toString()

            if (extractedText.isNotEmpty()) {
                val builder = android.app.AlertDialog.Builder(this)
                builder.setTitle("Text Recognition Result")
                    .setMessage(extractedText)
                    .setPositiveButton("OK") { dialog, _ -> dialog.dismiss() }
                    .show()
            }
            else {
                Toast.makeText(this, "No text extracted.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openCamera()
            }
            else {
                Toast.makeText(this, "Camera permission is required to scan documents", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun openCamera() {
        val imageFile = createImageFile()
        if (imageFile != null) {
            imageUri = FileProvider.getUriForFile(this, "${packageName}.provider", imageFile)

            val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, imageUri)
            startActivityForResult(cameraIntent, REQUEST_IMAGE_CAPTURE)
        }
        else {
            Toast.makeText(this, "Error creating image file", Toast.LENGTH_SHORT).show()
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

        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == Activity.RESULT_OK && imageUri != null) {
            try {
                val bitmap = MediaStore.Images.Media.getBitmap(this.contentResolver, imageUri)

                val rotatedBitmap = fixImageRotation(bitmap, imageUri!!)
                imgScanResult.setImageBitmap(rotatedBitmap)

                val preprocessedBitmap = preprocessImage(rotatedBitmap)
                extractTextFromImage(preprocessedBitmap)

            } catch (e: IOException) {
                e.printStackTrace()
            }
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

    private fun preprocessImage(bitmap: Bitmap): Bitmap {
        val bmpGray = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmpGray)
        val paint = Paint()
        val colorMatrix = ColorMatrix()

        colorMatrix.setSaturation(0f)

        val contrastMatrix = ColorMatrix()
        val contrast = 1.5f // Adjust contrast level
        contrastMatrix.setScale(contrast, contrast, contrast, 1f)
        colorMatrix.postConcat(contrastMatrix)

        val filter = ColorMatrixColorFilter(colorMatrix)
        paint.colorFilter = filter
        canvas.drawBitmap(bitmap, 0f, 0f, paint)

        return bmpGray
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
                txtRawText.text = visionText.text
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

    private fun computeFinancialRatios(data: Map<String, Float>): Map<String, Float> {
        fun safeDivide(numerator: Float?, denominator: Float?): Float {
            return if (numerator != null && denominator != null && denominator != 0f) {
                numerator / denominator
            } else {
                0f
            }
        }

        fun percentage(value: Float?): Float {
            return if (value != null) value * 100 else 0f
        }

        val receivablesTurnover = safeDivide(
            data["Net Credit Sales"] ?: data["Sales"],
            data["Accounts Receivable"]
        )

        val inventoryTurnover = safeDivide(
            data["Cost of Goods Sold"] ?: data["Cost of Good Sold"],
            data["Inventory"]
        )

        val accountsPayableTurnover = safeDivide(
            data["Cost of Goods Sold"],
            data["Accounts Payable"]
        )

        val totalAssetsTurnover = safeDivide(
            data["Net Sales"] ?: data["Sales"],
            data["Total Asset"] ?: data["Total Assets"]
        )

        val operatingCycle = (safeDivide(360f, inventoryTurnover) + safeDivide(360f, receivablesTurnover))

        val cashConversionCycle = operatingCycle - safeDivide(360f, accountsPayableTurnover)

        return mapOf(
            "Current Ratio" to safeDivide(data["Current Assets"], data["Current Liabilities"]),
            "Debt Ratio" to safeDivide(data["Total Liabilities"], data["Total Assets"]),
            "Debt-to-Equity Ratio" to safeDivide(data["Total Liabilities"], data["Total Equity"] ?: data["Total Owner's Equity"]),
            "Net Profit Margin" to percentage(
                safeDivide(data["Net Income"], data["Net Sales"] ?: data["Total Revenue"])
            ),
            "Gross Profit Margin" to percentage(
                safeDivide(data["Gross Profit"], data["Net Sales"] ?: data["Total Revenue"])
            ),
            "Inventory Turnover" to inventoryTurnover,
            "Return on Assets (ROA)" to percentage(
                safeDivide(data["Net Income"], data["Total Asset"] ?: data["Total Assets"])
            ),
            "Return on Equity (ROE)" to percentage(
                safeDivide(data["Net Income"], data["Total Equity"] ?: data["Equity"])
            ),
            "Earnings Per Share (EPS)" to safeDivide(data["Net Income"], data["Shares Outstanding"]),
            "Price-to-Earnings Ratio (P/E)" to safeDivide(
                data["Market Price per Share"],
                safeDivide(data["Net Income"], data["Shares Outstanding"])
            ),
            "Operating Profit Margin" to percentage(
                safeDivide(data["Operating Income"] ?: data["EBIT"], data["Net Sales"] ?: data["Total Revenue"])
            ),
            "Interest Coverage Ratio" to safeDivide(
                data["Operating Income"] ?: data["EBIT"], data["Interest Expense"]
            ),
            "Quick Ratio" to (safeDivide(
                (data["Cash"] ?: 0f) + (data["Marketable Securities"] ?: 0f) + (data["Accounts Receivable"] ?: 0f),
                data["Current Liabilities"]
            ).takeIf { it > 0 } ?: safeDivide(
                (data["Current Assets"] ?: 0f) - (data["Inventory"] ?: 0f),
                data["Current Liabilities"]
            ) ?: 0f),

            "Dividend Payout Ratio" to safeDivide(data["Dividends Paid"], data["Net Income"]),
            "Retention Ratio" to safeDivide(data["Retained Earnings"], data["Net Income"]),
            "Receivables Turnover" to receivablesTurnover,
            "Accounts Payable Turnover" to accountsPayableTurnover,
            "Payables Turnover" to safeDivide(data["Cost of Goods Sold"], data["Accounts Payable"]),
            "Asset Turnover" to safeDivide(data["Sales"], data["Total Assets"]),
            "Average Collection Period" to safeDivide(360f, receivablesTurnover),
            "Days Inventories" to safeDivide(360f, inventoryTurnover),
            "Days Payable" to safeDivide(360f, accountsPayableTurnover),
            "Total Assets Turnover" to totalAssetsTurnover,
            "Operating Cycle" to operatingCycle,
            "Cash Conversion Cycle" to cashConversionCycle
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

//    private fun showSettingsDialog() {
//        val options = arrayOf(
//            "Current Ratio", "Debt-to-Equity Ratio", "Net Profit Margin",
//            "Gross Profit Margin", "Inventory Turnover", "Return on Assets (ROA)",
//            "Return on Equity (ROE)", "Earnings Per Share (EPS)", "Price-to-Earnings Ratio (P/E)",
//            "Debt Ratio", "Operating Profit Margin", "Interest Coverage Ratio",
//            "Quick Ratio", "Dividend Payout Ratio", "Retention Ratio",
//            "Receivables Turnover", "Payables Turnover", "Asset Turnover"
//        )
//
//        val builder = android.app.AlertDialog.Builder(this)
//        builder.setTitle("Select Ratios to Compute")
//            .setMultiChoiceItems(options, checkedItems) { _, which, isChecked ->
//                checkedItems[which] = isChecked  // ✅ This now updates the global array
//            }
//            .setPositiveButton("OK") { _, _ ->
//                Toast.makeText(this, "Settings updated!", Toast.LENGTH_SHORT).show()
//            }
//            .setNegativeButton("Cancel", null)
//
//        builder.create().show()
//    }

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
