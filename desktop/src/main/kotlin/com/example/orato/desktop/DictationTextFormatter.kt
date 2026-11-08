package com.example.orato.desktop

class DictationTextFormatter {
    fun formatFinalChunk(existingText: String, rawText: String): String {
        val normalized = normalizeCommands(rawText)
        if (normalized.isBlank()) {
            return ""
        }

        val builder = StringBuilder()
        val shouldPrefixSpace = existingText.isNotBlank() &&
            !existingText.last().isWhitespace() &&
            !startsWithPunctuation(normalized)

        if (shouldPrefixSpace) {
            builder.append(' ')
        }
        builder.append(applySentenceCase(existingText + builder.toString(), normalized))
        return builder.toString()
    }

    private fun normalizeCommands(text: String): String {
        var result = " ${text.trim()} "

        commandsForProfile().forEach { (spoken, symbol) ->
            result = result.replace(" $spoken ", " $symbol ")
        }

        result = result
            .replace(Regex("\\s+([,.!?;:])"), "$1")
            .replace(Regex("([,.!?;:])(?=\\S)"), "$1 ")
            .replace(Regex("\\s+([)\\]}\"'])"), "$1")
            .replace(Regex("([(\\[{\"'])\\s+"), "$1")
            .replace(Regex("\\s*\\n\\s*"), "\n")
            .replace(Regex("[ \t]{2,}"), " ")
            .trim()

        return result
    }

    private fun applySentenceCase(existingText: String, chunk: String): String {
        if (!shouldApplySentenceCase()) {
            return chunk
        }

        val combined = existingText + chunk
        val shouldUppercase = combined.dropLast(chunk.length)
            .trimEnd()
            .let { prefix ->
                prefix.isEmpty() || prefix.last() in setOf('.', '!', '?', '\n')
            }

        if (!shouldUppercase) {
            return chunk
        }

        val firstLetterIndex = chunk.indexOfFirst { it.isLetter() }
        if (firstLetterIndex == -1) {
            return chunk
        }

        val chars = chunk.toCharArray()
        chars[firstLetterIndex] = chars[firstLetterIndex].titlecaseChar()
        return String(chars)
    }

    private fun startsWithPunctuation(text: String): Boolean {
        return text.firstOrNull() in setOf('.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '\'')
    }

    private fun shouldApplySentenceCase(): Boolean {
        val speechCode = ModelLibrary.current().code
        return speechCode == "tr"
    }

    private fun commandsForProfile(): Map<String, String> {
        val speechCode = ModelLibrary.current().code
        return when (speechCode) {
            "ar" -> ARABIC_COMMANDS
            "fa" -> FARSI_COMMANDS
            else -> TURKISH_COMMANDS
        }
    }

    private companion object {
        val TURKISH_COMMANDS = linkedMapOf(
            "yeni paragraf" to "\n\n",
            "yeni satır" to "\n",
            "alt satır" to "\n",
            "virgül" to ",",
            "nokta" to ".",
            "ünlem" to "!",
            "soru işareti" to "?",
            "iki nokta" to ":",
            "noktalı virgül" to ";",
            "tırnak aç" to "\"",
            "tırnak kapa" to "\"",
            "parantez aç" to "(",
            "parantez kapa" to ")",
            "köşeli parantez aç" to "[",
            "köşeli parantez kapa" to "]"
        )

        val ARABIC_COMMANDS = linkedMapOf(
            "فقرة جديدة" to "\n\n",
            "سطر جديد" to "\n",
            "فاصلة" to "،",
            "نقطة" to ".",
            "علامة تعجب" to "!",
            "علامة استفهام" to "؟",
            "نقطتان" to ":",
            "فاصلة منقوطة" to "؛",
            "افتح قوس" to "(",
            "اغلق قوس" to ")",
            "افتح قوس مربع" to "[",
            "اغلق قوس مربع" to "]",
            "افتح اقتباس" to "\"",
            "اغلق اقتباس" to "\""
        )

        val FARSI_COMMANDS = linkedMapOf(
            "پاراگراف جدید" to "\n\n",
            "خط جدید" to "\n",
            "ویرگول" to "،",
            "نقطه" to ".",
            "علامت تعجب" to "!",
            "علامت سوال" to "؟",
            "دو نقطه" to ":",
            "نقطه ویرگول" to "؛",
            "پرانتز باز" to "(",
            "پرانتز بسته" to ")",
            "کروشه باز" to "[",
            "کروشه بسته" to "]",
            "گیومه باز" to "\"",
            "گیومه بسته" to "\""
        )

    }
}
