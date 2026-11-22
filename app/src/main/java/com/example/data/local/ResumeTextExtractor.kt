package com.example.data.local

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.ImageType
import com.tom_roush.pdfbox.rendering.PDFRenderer
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object ResumeTextExtractor {
    private const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private const val MAX_EXTRACTED_CHARS = 18000
    private const val MAX_OCR_PDF_PAGES = 8

    fun extract(context: Context, uri: Uri, mimeType: String): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the selected CV.")
        require(bytes.size <= MAX_FILE_BYTES) { "Please choose a CV smaller than 8 MB." }

        val text = when {
            mimeType == "application/pdf" || bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-" ->
                extractPdf(context, bytes)
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                uri.toString().lowercase().endsWith(".docx") -> extractDocx(bytes)
            mimeType.startsWith("image/") -> recognizeBitmap(bytes)
            else -> error("Please upload a PDF, Word DOCX, JPG or PNG CV.")
        }

        return text.replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim().take(MAX_EXTRACTED_CHARS)
            .also {
                require(it.isNotBlank()) {
                    "No readable text was found. Try a clearer scan or a text-based PDF/DOCX."
                }
            }
    }

    private fun extractPdf(context: Context, bytes: ByteArray): String {
        PDFBoxResourceLoader.init(context)
        PDDocument.load(bytes).use { document ->
            val nativeText = PDFTextStripper().getText(document).trim()
            if (nativeText.length >= 80) return nativeText

            // Scanned PDFs contain page images rather than selectable text. Render a
            // limited number of pages and recognize their text on-device with ML Kit.
            val renderer = PDFRenderer(document)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            try {
                val pageCount = minOf(document.numberOfPages, MAX_OCR_PDF_PAGES)
                val recognized = (0 until pageCount).mapNotNull { page ->
                    val bitmap = renderer.renderImage(page, 1.5f, ImageType.RGB)
                    try {
                        Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
                    } finally {
                        bitmap.recycle()
                    }
                }.filter { it.isNotBlank() }.joinToString("\\n")
                return if (nativeText.isNotBlank()) "$nativeText\\n$recognized" else recognized
            } finally {
                recognizer.close()
            }
        }
    }

    private fun recognizeBitmap(bytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: error("This image could not be opened. Please choose a JPG or PNG image.")
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text
        } finally {
            recognizer.close()
            bitmap.recycle()
        }
    }

    private fun extractDocx(bytes: ByteArray): String {
        val xml = ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var found: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == "word/document.xml") { found = zip.readBytes(); break }
            }
            found
        } ?: error("This Word file does not contain readable document text.")
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            isXIncludeAware = false
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(InputSource(ByteArrayInputStream(xml)))
        val nodes = doc.getElementsByTagNameNS("*", "t")
        return (0 until nodes.length).joinToString(" ") { nodes.item(it).textContent.orEmpty() }
    }
}
