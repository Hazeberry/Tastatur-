package de.hazeberry.keyboard

/** Eine gespeicherte Abfrage: Name für die Auswahl, Text für den Chat. */
data class QueryTemplate(val name: String, val text: String)

/**
 * Abfrage-Vorlagen und das Zusammensetzen des Textes aus Abfrage und Dateien, ohne Android-Code.
 *
 * Vorlagen stehen in den Einstellungen als ein einziger Text, damit sich alles an einer Stelle
 * bearbeiten lässt:
 *
 *     ## Zusammenfassen
 *     Fasse den folgenden Text in fünf Stichpunkten zusammen.
 *
 *     ## Übersetzen
 *     Übersetze den folgenden Text ins Englische.
 *
 * Eine Zeile mit `## ` am Anfang beginnt eine Vorlage; alles bis zur nächsten solchen Zeile ist
 * ihr Text. Steht in dem Text gar keine solche Zeile, gilt er als eine einzige Vorlage "Abfrage".
 */
object FileCompose {

    const val DEFAULT_TEMPLATES = """## Zusammenfassen
Fasse den folgenden Text verständlich in fünf Stichpunkten zusammen. Nenne die wichtigsten Fakten und offenen Fragen.

## Analysieren
Analysiere den folgenden Text. Trenne Fakten von Annahmen, zeige Widersprüche und fasse die zentralen Erkenntnisse zusammen.

## Fragen finden
Lies den folgenden Text und erstelle zehn gezielte Fragen, mit denen ich die wichtigsten Inhalte überprüfen kann."""

    /** Linie zwischen Abfrage und Dateiinhalt (damit das Modell beides auseinanderhält). */
    const val SEPARATOR = "———"

    const val MAX_NAME = 40
    const val MAX_TEMPLATES = 30

    /** Vorlagen aus dem Einstellungstext lesen. Leere Namen oder Texte fallen weg. */
    fun parseTemplates(source: String): List<QueryTemplate> {
        val text = source.replace("\r\n", "\n").replace('\r', '\n')
        if (text.isBlank()) return emptyList()
        val result = ArrayList<QueryTemplate>()
        var name: String? = null
        val body = StringBuilder()

        fun flush() {
            val n = name?.trim().orEmpty().take(MAX_NAME)
            val t = body.toString().trim()
            if (n.isNotEmpty() && t.isNotEmpty() && result.size < MAX_TEMPLATES) result.add(QueryTemplate(n, t))
            body.setLength(0)
        }

        var sawHeader = false
        for (line in text.lineSequence()) {
            if (line.startsWith("## ") || line.trimEnd() == "##") {
                if (sawHeader) flush()
                sawHeader = true
                name = line.removePrefix("##").trim()
            } else if (sawHeader) {
                body.append(line).append('\n')
            }
        }
        if (sawHeader) {
            flush()
            return result
        }
        // Keine Überschrift: der ganze Text ist eine Vorlage.
        return listOf(QueryTemplate("Abfrage", text.trim()))
    }

    /** Die Vorlage mit diesem Namen oder null (auch bei leerem Namen = "keine Abfrage"). */
    fun find(templates: List<QueryTemplate>, name: String?): QueryTemplate? =
        if (name.isNullOrEmpty()) null else templates.firstOrNull { it.name == name }

    /**
     * Setzt den endgültigen Text zusammen: Abfrage, Trennlinie, dann die Dateien. Mehrere Dateien
     * (oder [forceHeaders]) bekommen `[Datei: name]` davor. Ohne Dateien mit Text ist das Ergebnis leer.
     */
    fun build(query: QueryTemplate?, parts: List<Part>, forceHeaders: Boolean): String {
        val files = parts.filter { it.text != null }
        if (files.isEmpty()) return ""
        val headers = files.size > 1 || forceHeaders
        val body = files.joinToString("\n\n") { if (headers) "[Datei: ${it.name}]\n${it.text}" else it.text!! }
        return if (query == null) body else "${query.text}\n\n$SEPARATOR\n\n$body"
    }

    /** Anfang des Textes für die Vorschau, an einer Zeilengrenze gekürzt, wenn möglich. */
    fun preview(text: String, maxChars: Int = 700): String {
        if (text.length <= maxChars) return text
        var cut = DocumentReader.safeCut(text, maxChars)
        val lineEnd = cut.lastIndexOf('\n')
        if (lineEnd > maxChars / 2) cut = cut.substring(0, lineEnd)
        return cut.trimEnd() + "\n…"
    }

    /** Zahl mit deutschem Tausenderpunkt: 12345 -> "12.345". */
    fun count(n: Int): String = n.toString().reversed().chunked(3).joinToString(".").reversed()
}
