package com.karal.studio.core.compiler

/**
 * On-device TypeScript → JavaScript conversion for the supported subset.
 *
 * Supported: type annotations on variables, parameters and return values, `interface` and
 * `type` declarations, `as` casts, simple generic calls, and TypeScript access modifiers.
 * Not supported (reported by [unsupportedConstructs]): decorators, enums, namespaces and
 * runtime TypeScript APIs. This is intentionally explicit: we do not pretend to bundle
 * tsc inside the APK.
 */
object TypeScriptStripper {
    fun strip(source: String): String {
        var current = removeTypeDeclarations(source)
        val masked = maskLiterals(current)
        current = applyRegex(current, masked, Regex("""\b(?:const|let|var)\s+([A-Za-z_$][\w$]*)\s*:\s*[^=;]+(?==)"""), "$1 ")
        val masked2 = maskLiterals(current)
        current = applyRegex(current, masked2, Regex("""([(,]\s*[A-Za-z_$][\w$]*)\??\s*:\s*[^,)=]+(?=[,)=])"""), "$1")
        val masked3 = maskLiterals(current)
        current = applyRegex(current, masked3, Regex("""\)\s*:\s*[A-Za-z_$][\w<>\[\]|\s,]*\s*(?=\{)"""), ") ")
        val masked4 = maskLiterals(current)
        current = applyRegex(current, masked4, Regex("""\bas\s+[A-Za-z_$][\w<>\[\]|\s,]*"""), "")
        val masked5 = maskLiterals(current)
        current = applyRegex(current, masked5, Regex("""([A-Za-z_$][\w$]*)<[A-Za-z_$][\w<>,.\s]*>\s*\("""), "$1(")
        val masked6 = maskLiterals(current)
        current = applyRegex(current, masked6, Regex("""\b(?:public|private|protected|readonly)\s+"""), "")
        return "// Compiled from TypeScript (supported subset) by KARAL BOT STUDIO\n" +
            current.replace(Regex("""\n{3,}"""), "\n\n").trim() + "\n"
    }

    /** Returns human-readable warnings for syntax this converter cannot handle. */
    fun unsupportedConstructs(source: String): List<String> {
        val masked = maskLiterals(source)
        return buildList {
            if (Regex("""@[A-Za-z_]""").containsMatchIn(masked)) add("Decorators are not supported.")
            if (Regex("""\benum\s+[A-Za-z_]""").containsMatchIn(masked)) add("Enums are not supported; use a const object.")
            if (Regex("""\bnamespace\s+[A-Za-z_]""").containsMatchIn(masked)) add("Namespaces are not supported.")
            if (Regex("""\bimport\s+type\b""").containsMatchIn(masked)) add("Type-only imports are not supported.")
        }
    }

    fun balanced(source: String): Boolean {
        val masked = maskLiterals(source)
        val stack = ArrayDeque<Char>()
        masked.forEach { c ->
            when (c) {
                '{', '(', '[' -> stack.addLast(c)
                '}', ')', ']' -> {
                    val open = stack.removeLastOrNull() ?: return false
                    if (open != matching(c)) return false
                }
            }
        }
        return stack.isEmpty()
    }

    private fun matching(close: Char) = when (close) {
        '}' -> '{'; ')' -> '('; ']' -> '['; else -> '?'
    }

    /** Replaces string/template contents and comments with spaces, preserving offsets and newlines. */
    private fun maskLiterals(source: String): String {
        val chars = source.toCharArray()
        var i = 0
        while (i < chars.size) {
            val c = chars[i]
            when {
                c == '"' || c == '\'' || c == '`' -> {
                    val quote = c
                    chars[i] = ' '
                    i++
                    while (i < chars.size) {
                        if (chars[i] == '\\') {
                            chars[i] = ' '; i++
                            if (i < chars.size) { chars[i] = if (chars[i] == '\n') '\n' else ' '; i++ }
                            continue
                        }
                        if (chars[i] == quote) { chars[i] = ' '; i++; break }
                        if (chars[i] != '\n') chars[i] = ' '
                        i++
                    }
                }
                c == '/' && i + 1 < chars.size && chars[i + 1] == '/' -> {
                    while (i < chars.size && chars[i] != '\n') { chars[i] = ' '; i++ }
                }
                c == '/' && i + 1 < chars.size && chars[i + 1] == '*' -> {
                    chars[i] = ' '; chars[i + 1] = ' '; i += 2
                    while (i < chars.size) {
                        if (chars[i] == '*' && i + 1 < chars.size && chars[i + 1] == '/') {
                            chars[i] = ' '; chars[i + 1] = ' '; i += 2; break
                        }
                        if (chars[i] != '\n') chars[i] = ' '
                        i++
                    }
                }
                else -> i++
            }
        }
        return String(chars)
    }

    private fun removeTypeDeclarations(source: String): String {
        val masked = maskLiterals(source)
        val removals = mutableListOf<IntRange>()
        Regex("""\binterface\s+[A-Za-z_$][\w$]*""").findAll(masked).forEach { match ->
            val brace = masked.indexOf('{', match.range.last)
            if (brace >= 0) {
                var depth = 0
                var end = -1
                for (i in brace until masked.length) {
                    when (masked[i]) {
                        '{' -> depth++
                        '}' -> { depth--; if (depth == 0) { end = i; break } }
                    }
                }
                if (end >= 0) removals += match.range.first..end
            }
        }
        Regex("""\btype\s+[A-Za-z_$][\w$]*\s*=""").findAll(masked).forEach { match ->
            var depth = 0
            var end = -1
            for (i in match.range.last + 1 until masked.length) {
                when (masked[i]) {
                    '{', '(', '[' -> depth++
                    '}', ')', ']' -> depth--
                    ';' -> if (depth == 0) { end = i; break }
                    '\n' -> if (depth == 0 && i > match.range.last + 40) { end = i - 1; break }
                }
            }
            if (end >= 0) removals += match.range.first..end
        }
        return removals.sortedByDescending { it.first }.fold(source) { acc, range ->
            val start = range.first
            val endExclusive = minOf(range.last + 1, acc.length)
            if (start in 0 until acc.length) acc.removeRange(start, endExclusive) else acc
        }
    }

    private fun applyRegex(
        original: String,
        masked: String,
        regex: Regex,
        replacement: String
    ): String {
        val edits = mutableListOf<Pair<IntRange, String>>()
        regex.findAll(masked).forEach { match ->
            val expanded = replacement.replace("\$1", match.groupValues.getOrNull(1).orEmpty())
            edits += match.range to expanded
        }
        var result = original
        edits.sortedByDescending { it.first.first }.forEach { edit ->
            val range = edit.first
            val value = edit.second
            val start = range.first
            val endExclusive = minOf(range.last + 1, result.length)
            if (start in 0 until result.length) result = result.replaceRange(start, endExclusive, value)
        }
        return result
    }
}
