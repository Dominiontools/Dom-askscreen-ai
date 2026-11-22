package com.example.data.local

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

object ResumeTextExtractor {
    fun extract(context: Context, uri: Uri, mimeType: String): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the selected CV.")
        require(bytes.size <= 8 * 1024 * 1024) { "Please choose a CV smaller than 8 MB." }
        val text = when {
            mimeType == "application/pdf" || bytes.take(5).toByteArray().toString(Charsets.US_ASCII) == "%PDF-" -> {
                PDFBoxResourceLoader.init(context)
                PDDocument.load(bytes).use { PDFTextStripper().getText(it) }
            }
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ||
                uri.toString().lowercase().endsWith(".docx") -> extractDocx(bytes)
            else -> error("Please upload a PDF or Word DOCX CV.")
        }
        return text.replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\n{3,}"), "\\n\\n")
            .trim().take(18000)
            .also { require(it.isNotBlank()) { "No readable text was found. Please use a text-based PDF or DOCX file." } }
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
