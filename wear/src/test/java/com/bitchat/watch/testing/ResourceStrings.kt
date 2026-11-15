package com.bitchat.watch.testing

import com.bitchat.watch.R
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Reads the watch's strings straight from the resource files, for plain JVM tests (the unit
 * test classpath does not carry merged Android resources).
 */
object ResourceStrings {
    private val cache = HashMap<String, Map<String, String>>()

    private fun table(qualifier: String): Map<String, String> = cache.getOrPut(qualifier) {
        val dir = if (qualifier.isEmpty()) "values" else "values-$qualifier"
        val file = listOf("src/main/res/$dir/strings.xml", "wear/src/main/res/$dir/strings.xml")
            .map(::File).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            node.attributes.getNamedItem("name").nodeValue to
                node.textContent.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }

    fun name(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    /** The string [id] in [qualifier] ("" for English, "ar" for Arabic). */
    fun get(id: Int, qualifier: String = ""): String =
        table(qualifier)[name(id)] ?: error("${name(id)} missing in values-$qualifier")

    fun names(qualifier: String = ""): Set<String> = table(qualifier).keys
}
