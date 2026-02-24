package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.completion.PrefixMatcher
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.patterns.PlatformPatterns.psiElement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import org.jetbrains.yaml.psi.YAMLDocument
import org.jetbrains.yaml.psi.YAMLFile
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLMapping
import org.jetbrains.yaml.psi.YAMLSequence
import org.jetbrains.yaml.psi.YAMLSequenceItem

class EasypConfigCompletionContributor : CompletionContributor() {
    private val log = Logger.getInstance(EasypConfigCompletionContributor::class.java)
    private data class RenderContext(
        val valuePath: String? = null,
        val keyContextPath: String? = null,
        val valuePosition: Boolean = false,
        val insideSequenceItemMapping: Boolean = false
    )
    private data class InsertTemplate(
        val text: String,
        val caretOffsetInText: Int
    )

    init {
        extend(
            CompletionType.BASIC,
            psiElement(),
            object : CompletionProvider<CompletionParameters>() {
                override fun addCompletions(
                    parameters: CompletionParameters,
                    context: ProcessingContext,
                    result: CompletionResultSet
                ) {
                    addConfigCompletions(parameters, result)
                }
            }
        )
    }

    override fun invokeAutoPopup(position: PsiElement, typeChar: Char): Boolean {
        val file = position.containingFile as? YAMLFile ?: return false
        val virtualFile = file.virtualFile ?: return false
        val project = file.project
        val settings = EasypSettings.getInstance(project).state
        if (!EasypConfigTarget.isTargetConfigFile(virtualFile, project, settings)) {
            return false
        }

        val document = file.viewProvider.document
        val offset = position.textOffset
        val shouldPopup = shouldAutoPopup(typeChar, document?.text ?: file.text, offset)
        if (shouldPopup) {
            log.warn(
                "[easyp-completion] auto-popup file=${virtualFile.path} char=$typeChar offset=$offset"
            )
        }
        return shouldPopup
    }

    private fun addConfigCompletions(parameters: CompletionParameters, result: CompletionResultSet) {
        val yamlFile = parameters.originalFile as? YAMLFile ?: return
        val suggestions = collectSuggestions(parameters.position, yamlFile)
        val prefix = completionPrefix(yamlFile.text, parameters.offset)
        val resultSet = result.withPrefixMatcher(AlwaysVisiblePrefixMatcher(prefix ?: ""))
        val renderContext = resolveRenderContext(parameters.position)
        log.warn(
            "[easyp-completion] invoke file=${yamlFile.virtualFile?.path ?: yamlFile.name} " +
                "offset=${parameters.offset} prefix=${prefix ?: "<none>"} " +
                "suggestions=${suggestions.size} values=${suggestions.take(12)}"
        )
        suggestions.forEach { suggestion ->
            resultSet.addElement(buildLookupElement(suggestion, renderContext))
        }
        if (suggestions.isNotEmpty()) {
            resultSet.stopHere()
        }
    }

    internal fun collectSuggestions(
        position: PsiElement,
        file: PsiFile,
        settings: EasypSettings.State = EasypSettings.getInstance(file.project).state
    ): List<String> {
        val virtualFile = file.virtualFile ?: return emptyList()
        val project = file.project
        if (!EasypConfigTarget.isTargetConfigFile(virtualFile, project, settings)) {
            log.warn(
                "[easyp-completion] skip non-target file=${virtualFile.path} " +
                    "resolved=${EasypConfigTarget.resolvedConfigPath(project, settings)} cfg=${settings.configPath}"
            )
            return emptyList()
        }

        val yamlFile = file as? YAMLFile
        if (yamlFile == null) {
            val fallback = fallbackSuggestions(file.text, position.textOffset)
            log.warn(
                "[easyp-completion] fallback non-yaml file=${virtualFile.path} offset=${position.textOffset} " +
                    "suggestions=${fallback.size}"
            )
            return fallback
        }

        val keyValue = PsiTreeUtil.getParentOfType(position, YAMLKeyValue::class.java, false)
        val sequenceItem = PsiTreeUtil.getParentOfType(position, YAMLSequenceItem::class.java, false)
        if (isValuePosition(position, keyValue)) {
            val values = valueCompletions(keyValue)
            if (values.isNotEmpty()) {
                val keyPath = keyValue?.let { pathForKeyValue(it).joinToString(".") } ?: "<unknown>"
                log.warn(
                    "[easyp-completion] value suggestions file=${virtualFile.path} " +
                        "path=$keyPath values=$values"
                )
                return values
            }
            val fallback = fallbackSuggestions(file.text, position.textOffset)
            log.warn(
                "[easyp-completion] value fallback file=${virtualFile.path} offset=${position.textOffset} " +
                    "suggestions=${fallback.size}"
            )
            return fallback
        }

        val sequenceItemMapping = sequenceItem?.value as? YAMLMapping
        val mapping = when {
            sequenceItemMapping != null -> sequenceItemMapping
            sequenceItem != null -> null
            keyValue != null -> keyValue.parentMapping
            else -> PsiTreeUtil.getParentOfType(position, YAMLMapping::class.java, false)
        }
        if (keyValue == null && sequenceItem == null && mapping == null) {
            val fallback = fallbackSuggestions(file.text, position.textOffset)
            log.warn(
                "[easyp-completion] unresolved-psi fallback file=${virtualFile.path} " +
                    "offset=${position.textOffset} suggestions=${fallback.size}"
            )
            return fallback
        }

        val psiContextPath = resolveKeyContextPath(position, keyValue, mapping, sequenceItem).joinToString(".")
        val fallbackContextPath = fallbackKeyContextPath(file.text, position.textOffset)
        val keyContextPath = when {
            shouldPreferFallbackContext(fallbackContextPath, psiContextPath) -> fallbackContextPath
            psiContextPath.isNotBlank() && KEY_COMPLETIONS.containsKey(psiContextPath) -> psiContextPath
            !fallbackContextPath.isNullOrBlank() -> fallbackContextPath
            else -> psiContextPath
        }
        val expectedKeys = KEY_COMPLETIONS[keyContextPath].orEmpty()
        if (expectedKeys.isEmpty()) {
            scalarSequenceItemCompletionsForPath(keyContextPath)?.let { scalarSuggestions ->
                if (scalarSuggestions.isNotEmpty()) {
                    log.warn(
                        "[easyp-completion] scalar sequence suggestions file=${virtualFile.path} " +
                            "context=$keyContextPath suggestions=$scalarSuggestions"
                    )
                    return scalarSuggestions
                }
            }
            val fallback = fallbackSuggestions(file.text, position.textOffset)
            log.warn(
                "[easyp-completion] empty-context fallback file=${virtualFile.path} " +
                    "context=$keyContextPath suggestions=${fallback.size}"
            )
            return fallback
        }

        val existingKeys = mapping?.keyValues
            ?.mapNotNull { key -> key.keyText.takeIf { it.isNotBlank() } }
            ?.toSet()
            ?: emptySet()

        val suggestions = expectedKeys
            .asSequence()
            .filterNot { existingKeys.contains(it) }
            .toList()
        val contextualSuggestions = contextualKeySuggestions(
            keyContextPath = keyContextPath,
            suggestions = suggestions,
            sequenceItem = sequenceItem,
            mapping = mapping,
            fileText = file.text,
            offset = position.textOffset
        )
        if (contextualSuggestions.isNotEmpty()) {
            log.warn(
                "[easyp-completion] key suggestions file=${virtualFile.path} " +
                    "context=$keyContextPath suggestions=$contextualSuggestions"
            )
            return contextualSuggestions
        }
        if (suggestions.isNotEmpty()) {
            log.warn(
                "[easyp-completion] key suggestions file=${virtualFile.path} " +
                    "context=$keyContextPath suggestions=$suggestions"
            )
            return suggestions
        }
        val fallback = fallbackSuggestions(file.text, position.textOffset)
        log.warn(
            "[easyp-completion] no-key-suggestions fallback file=${virtualFile.path} " +
                "context=$keyContextPath suggestions=${fallback.size}"
        )
        return fallback
    }

    internal fun collectSuggestionsFromText(text: String, offset: Int): List<String> {
        return fallbackSuggestions(text, offset)
    }

    internal fun shouldAutoPopup(typeChar: Char, text: String, rawOffset: Int): Boolean {
        return shouldAutoPopupFor(typeChar, text, rawOffset)
    }

    private fun fallbackSuggestions(text: String, rawOffset: Int): List<String> {
        if (text.isEmpty()) {
            return KEY_COMPLETIONS[""].orEmpty()
        }

        val offset = rawOffset.coerceIn(0, text.length)
        val beforeCaret = text.substring(0, offset)
        val currentLine = beforeCaret.substringAfterLast('\n')
        val previousLines = beforeCaret.substringBeforeLast('\n', "").lines()
        val pathStack = mutableListOf<Pair<Int, String>>()

        previousLines.forEach { line ->
            updatePathStack(pathStack, line)
        }
        val currentIndent = leadingIndent(currentLine)
        val preserveContainerContext = shouldPreserveContainerContext(currentLine, previousLines)
        if (!preserveContainerContext) {
            while (pathStack.isNotEmpty() && currentIndent <= pathStack.last().first) {
                pathStack.removeLast()
            }
        }

        val trimmedCurrent = currentLine.trimStart()
        val stackPath = pathStack.map { it.second }
        val previousMeaningful = previousLines.asReversed()
            .firstOrNull { it.trim().isNotEmpty() && !it.trimStart().startsWith("#") }
        if (trimmedCurrent.isBlank()) {
            val directPath = stackPath.joinToString(".")
            val direct = KEY_COMPLETIONS[directPath].orEmpty()
            if (direct.isNotEmpty()) {
                return filterPluginStarterSuggestionsForFallback(
                    contextPath = directPath,
                    suggestions = direct,
                    trimmedCurrentLine = trimmedCurrent,
                    previousMeaningfulLine = previousMeaningful
                )
            }
            val sequencePath = if (stackPath.isEmpty()) {
                stackPath
            } else if (stackPath.last().endsWith("[]")) {
                stackPath
            } else {
                stackPath.dropLast(1) + "${stackPath.last()}[]"
            }
            val sequenceSuggestions = KEY_COMPLETIONS[sequencePath.joinToString(".")].orEmpty()
            if (sequenceSuggestions.isNotEmpty()) {
                return filterPluginStarterSuggestionsForFallback(
                    contextPath = sequencePath.joinToString("."),
                    suggestions = sequenceSuggestions,
                    trimmedCurrentLine = trimmedCurrent,
                    previousMeaningfulLine = previousMeaningful
                )
            }
        }
        if (trimmedCurrent.contains(":")) {
            val sequenceItemValue = trimmedCurrent.startsWith("-")
            val keyLine = if (sequenceItemValue) trimmedCurrent.removePrefix("-").trimStart() else trimmedCurrent
            val key = keyLine.substringBefore(':').trim()
            if (key.isNotEmpty()) {
                val valueBasePath = if (sequenceItemValue) {
                    if (stackPath.isEmpty()) {
                        stackPath
                    } else if (stackPath.last().endsWith("[]")) {
                        stackPath
                    } else {
                        stackPath.dropLast(1) + "${stackPath.last()}[]"
                    }
                } else {
                    stackPath
                }
                return valueCompletionsByPath((valueBasePath + key).joinToString("."))
            }
        }

        val keyPath = if (trimmedCurrent.startsWith("-")) {
            if (stackPath.isEmpty()) {
                stackPath
            } else if (stackPath.last().endsWith("[]")) {
                stackPath
            } else {
                stackPath.dropLast(1) + "${stackPath.last()}[]"
            }
        } else {
            stackPath
        }
        val keyPathString = keyPath.joinToString(".")
        val keySuggestions = KEY_COMPLETIONS[keyPathString].orEmpty()
        if (keySuggestions.isNotEmpty()) {
            return filterPluginStarterSuggestionsForFallback(
                contextPath = keyPathString,
                suggestions = keySuggestions,
                trimmedCurrentLine = trimmedCurrent,
                previousMeaningfulLine = previousMeaningful
            )
        }
        return scalarSequenceItemCompletionsForPath(keyPathString).orEmpty()
    }

    private fun leadingIndent(line: String): Int {
        val firstNonWhitespace = line.indexOfFirst { !it.isWhitespace() }
        return if (firstNonWhitespace < 0) line.length else firstNonWhitespace
    }

    private fun updatePathStack(pathStack: MutableList<Pair<Int, String>>, line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
            return
        }

        val indent = line.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) 0 else it }
        while (pathStack.isNotEmpty() && indent <= pathStack.last().first) {
            pathStack.removeLast()
        }

        if (trimmed.startsWith("-")) {
            if (pathStack.isNotEmpty() && !pathStack.last().second.endsWith("[]")) {
                val last = pathStack.removeLast()
                pathStack += last.first to "${last.second}[]"
            }
            val body = trimmed.removePrefix("-").trim()
            if (body.contains(":")) {
                val key = body.substringBefore(':').trim()
                val inlineValue = body.substringAfter(':', "").trim()
                val isContainer = inlineValue.isEmpty()
                if (key.isNotEmpty() && isContainer) {
                    pathStack += indent + 1 to key
                }
            }
            return
        }

        if (trimmed.contains(":")) {
            val key = trimmed.substringBefore(':').trim()
            val inlineValue = trimmed.substringAfter(':', "").trim()
            val isContainer = inlineValue.isEmpty()
            if (key.isNotEmpty() && isContainer) {
                pathStack += indent to key
            }
        }
    }

    private fun resolveKeyContextPath(
        position: com.intellij.psi.PsiElement,
        keyValue: YAMLKeyValue?,
        mapping: YAMLMapping?,
        sequenceItem: YAMLSequenceItem?
    ): List<String> {
        if (sequenceItem != null && mapping == null) {
            return pathForSequenceItem(sequenceItem)
        }

        if (mapping != null) {
            return pathForMapping(mapping)
        }

        if (sequenceItem != null) {
            return pathForSequenceItem(sequenceItem)
        }

        if (keyValue != null) {
            return pathForMapping(keyValue.parentMapping)
        }

        val yamlDocument = PsiTreeUtil.getParentOfType(position, YAMLDocument::class.java, false)
        val topLevelMapping = yamlDocument?.topLevelValue as? YAMLMapping
        if (topLevelMapping != null) {
            return pathForMapping(topLevelMapping)
        }
        return emptyList()
    }

    private fun valueCompletions(keyValue: YAMLKeyValue?): List<String> {
        keyValue ?: return emptyList()
        val keyPath = pathForKeyValue(keyValue).joinToString(".")
        return valueCompletionsByPath(keyPath)
    }

    private fun valueCompletionsByPath(keyPath: String): List<String> {
        val variants = linkedSetOf<String>()
        if (keyPath == "version") {
            variants += "v1alpha"
        }
        if (BOOLEAN_VALUE_PATHS.contains(keyPath)) {
            variants += "true"
            variants += "false"
        }
        if (SEQUENCE_VALUE_PATHS.contains(keyPath)) {
            variants += TEMPLATE_ARRAY
        }
        if (MAP_VALUE_PATHS.contains(keyPath)) {
            variants += TEMPLATE_MAP
        }
        if (STRING_VALUE_PATHS.contains(keyPath)) {
            variants += TEMPLATE_STRING
        }
        if (URL_VALUE_PATHS.contains(keyPath)) {
            variants += TEMPLATE_URL
        }
        if (ANY_VALUE_PATHS.contains(keyPath)) {
            variants += TEMPLATE_STRING
            variants += "true"
            variants += "0"
            variants += TEMPLATE_ARRAY
            variants += TEMPLATE_MAP
        }
        return variants.toList()
    }

    private fun isValuePosition(position: com.intellij.psi.PsiElement, keyValue: YAMLKeyValue?): Boolean {
        keyValue ?: return false
        val keyRange = keyValue.key?.textRange ?: return false
        val valueRange = keyValue.value?.textRange
        val offset = position.textOffset
        val document = keyValue.containingFile.viewProvider.document
        val keyPath = pathForKeyValue(keyValue).joinToString(".")
        val keyType = pathTypeHint(keyPath)
        if (valueRange != null) {
            if (document != null && !isSameLine(document, keyRange.endOffset, offset)) {
                if (keyType == "map" || keyType == "array") {
                    // For container values on following lines, suggest nested keys/items, not value templates.
                    return false
                }
                val currentLine = currentLineText(document, offset).trimStart()
                if (currentLine.startsWith("-")) {
                    // New list item on a following line should be treated as key context.
                    return false
                }
            }
            return valueRange.containsOffset(offset)
        }
        if (offset <= keyRange.endOffset) {
            return false
        }

        if (document == null) {
            return false
        }
        return isSameLine(document, keyRange.endOffset, offset)
    }

    private fun currentLineText(document: Document, offset: Int): String {
        if (document.textLength == 0) return ""
        val safeOffset = offset.coerceIn(0, document.textLength - 1)
        val line = document.getLineNumber(safeOffset)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        return document.charsSequence.subSequence(start, end).toString()
    }

    private fun isSameLine(document: Document, offsetA: Int, offsetB: Int): Boolean {
        if (document.textLength == 0) return true
        val safeA = offsetA.coerceIn(0, document.textLength - 1)
        val safeB = offsetB.coerceIn(0, document.textLength - 1)
        return document.getLineNumber(safeA) == document.getLineNumber(safeB)
    }

    private fun pathForKeyValue(keyValue: YAMLKeyValue): List<String> {
        val parentPath = pathForMapping(keyValue.parentMapping)
        val keyText = keyValue.keyText.trim()
        if (keyText.isEmpty()) {
            return parentPath
        }
        return parentPath + keyText
    }

    private fun pathForMapping(mapping: YAMLMapping?): List<String> {
        mapping ?: return emptyList()
        return when (val parent = mapping.parent) {
            is YAMLDocument -> emptyList()
            is YAMLFile -> emptyList()
            is YAMLKeyValue -> pathForKeyValue(parent)
            is YAMLSequenceItem -> pathForSequenceItem(parent)
            else -> emptyList()
        }
    }

    private fun pathForSequenceItem(item: YAMLSequenceItem): List<String> {
        val sequence = item.parent as? YAMLSequence ?: return emptyList()
        val ownerKeyValue = sequence.parent as? YAMLKeyValue ?: return emptyList()
        val parentPath = pathForMapping(ownerKeyValue.parentMapping)
        val sequenceKey = ownerKeyValue.keyText.trim()
        if (sequenceKey.isEmpty()) {
            return parentPath
        }
        return parentPath + "$sequenceKey[]"
    }

    private fun completionPrefix(text: String, rawOffset: Int): String? {
        if (text.isEmpty()) return null
        val offset = rawOffset.coerceIn(0, text.length)
        var index = offset - 1
        while (index >= 0) {
            val ch = text[index]
            if (ch.isWhitespace() || ch == ':' || ch == '-') {
                break
            }
            index--
        }
        val prefix = text.substring(index + 1, offset)
        return prefix.takeIf { it.isNotEmpty() }
    }

    private fun resolveRenderContext(position: PsiElement): RenderContext {
        val keyValue = PsiTreeUtil.getParentOfType(position, YAMLKeyValue::class.java, false)
        if (isValuePosition(position, keyValue)) {
            val psiPath = keyValue?.let { pathForKeyValue(it).joinToString(".") }
            val fallbackPath = fallbackValuePath(position.containingFile.text, position.textOffset)
            return RenderContext(
                valuePath = psiPath ?: fallbackPath,
                keyContextPath = fallbackKeyContextPath(position.containingFile.text, position.textOffset),
                valuePosition = true
            )
        }
        val sequenceItem = PsiTreeUtil.getParentOfType(position, YAMLSequenceItem::class.java, false)
        val sequenceItemMapping = sequenceItem?.value as? YAMLMapping
        val mapping = when {
            sequenceItemMapping != null -> sequenceItemMapping
            sequenceItem != null -> null
            keyValue != null -> keyValue.parentMapping
            else -> PsiTreeUtil.getParentOfType(position, YAMLMapping::class.java, false)
        }
        val psiContextPath = resolveKeyContextPath(position, keyValue, mapping, sequenceItem).joinToString(".")
        val fallbackContextPath = fallbackKeyContextPath(position.containingFile.text, position.textOffset)
        val keyContextPath = when {
            shouldPreferFallbackContext(fallbackContextPath, psiContextPath) -> fallbackContextPath
            psiContextPath.isNotBlank() && KEY_COMPLETIONS.containsKey(psiContextPath) -> psiContextPath
            !fallbackContextPath.isNullOrBlank() -> fallbackContextPath
            psiContextPath.isNotBlank() -> psiContextPath
            else -> null
        }
        return RenderContext(
            keyContextPath = keyContextPath,
            valuePosition = false,
            insideSequenceItemMapping = sequenceItemMapping != null
        )
    }

    private fun fallbackKeyContextPath(text: String, rawOffset: Int): String? {
        if (text.isEmpty()) return null
        val offset = rawOffset.coerceIn(0, text.length)
        val beforeCaret = text.substring(0, offset)
        val currentLine = beforeCaret.substringAfterLast('\n')
        val previousLines = beforeCaret.substringBeforeLast('\n', "").lines()
        val pathStack = mutableListOf<Pair<Int, String>>()
        previousLines.forEach { line -> updatePathStack(pathStack, line) }

        val currentIndent = leadingIndent(currentLine)
        val preserveContainerContext = shouldPreserveContainerContext(currentLine, previousLines)
        if (!preserveContainerContext) {
            while (pathStack.isNotEmpty() && currentIndent <= pathStack.last().first) {
                pathStack.removeLast()
            }
        }
        val stackPath = pathStack.map { it.second }
        val trimmedCurrent = currentLine.trimStart()
        val direct = stackPath.joinToString(".")
        if (trimmedCurrent.isBlank() && KEY_COMPLETIONS.containsKey(direct)) {
            return direct
        }
        val sequencePath = (if (stackPath.isEmpty()) {
            stackPath
        } else if (stackPath.last().endsWith("[]")) {
            stackPath
        } else {
            stackPath.dropLast(1) + "${stackPath.last()}[]"
        }).joinToString(".")
        if (
            (trimmedCurrent.isBlank() || trimmedCurrent.startsWith("-")) &&
            KEY_COMPLETIONS.containsKey(sequencePath)
        ) {
            return sequencePath
        }
        return direct.takeIf { it.isNotBlank() }
    }

    private fun fallbackValuePath(text: String, rawOffset: Int): String? {
        if (text.isEmpty()) return null
        val offset = rawOffset.coerceIn(0, text.length)
        val beforeCaret = text.substring(0, offset)
        val currentLine = beforeCaret.substringAfterLast('\n').trimStart()
        if (!currentLine.contains(":")) return null
        val keyLine = if (currentLine.startsWith("-")) currentLine.removePrefix("-").trimStart() else currentLine
        val key = keyLine.substringBefore(':').trim().takeIf { it.isNotBlank() } ?: return null
        val base = fallbackKeyContextPath(text, rawOffset)
        if (base.isNullOrBlank()) return key
        return "$base.$key"
    }

    private fun buildLookupElement(suggestion: String, context: RenderContext): LookupElementBuilder {
        val lookupString = when (suggestion) {
            TEMPLATE_ARRAY -> "array"
            TEMPLATE_MAP -> "map"
            TEMPLATE_STRING -> "string"
            TEMPLATE_URL -> "url"
            else -> suggestion
        }
        var builder = LookupElementBuilder.create(suggestion, lookupString)
        val typeHint = if (context.valuePosition) {
            valueTypeHint(suggestion, context.valuePath)
        } else {
            keyTypeHint(context.keyContextPath, suggestion)
        }
        if (!typeHint.isNullOrBlank()) {
            builder = builder.withTypeText(typeHint, true)
        }
        if (suggestion == TEMPLATE_URL) {
            builder = builder.withTailText(" https://...", true)
        }
        builder = builder.withInsertHandler { insertionContext, _ ->
            applySmartInsert(insertionContext, suggestion, context)
        }
        return builder
    }

    private fun applySmartInsert(
        insertionContext: InsertionContext,
        suggestion: String,
        context: RenderContext
    ) {
        val document = insertionContext.document
        val startOffset = insertionContext.startOffset
        val endOffset = insertionContext.tailOffset
        val lineMeta = lineMeta(document, startOffset)
        val sequenceContext = context.keyContextPath?.endsWith("[]") == true
        var replacementStart = startOffset
        var effectiveLineStartsWithDash = lineMeta.trimmedLine.startsWith("-")

        if (!context.valuePosition && sequenceContext && effectiveLineStartsWithDash) {
            val dashStart = sequenceDashReplacementStart(document, endOffset)
            if (dashStart != null) {
                replacementStart = dashStart
                effectiveLineStartsWithDash = false
            }
        }

        var template = if (context.valuePosition) {
            valueInsertTemplate(
                suggestion = suggestion,
                valuePath = context.valuePath,
                lineIndent = lineMeta.indent,
                lineStartsWithDash = lineMeta.trimmedLine.startsWith("-"),
                sequenceContext = sequenceContext
            )
        } else {
            val fullPath = if (context.keyContextPath.isNullOrBlank()) {
                suggestion
            } else {
                "${context.keyContextPath}.$suggestion"
            }
            keyInsertTemplate(
                suggestion = suggestion,
                fullPath = fullPath,
                contextPath = context.keyContextPath,
                lineIndent = lineMeta.indent,
                lineStartsWithDash = effectiveLineStartsWithDash,
                insideSequenceItemMapping = context.insideSequenceItemMapping
            )
        }

        if (!context.valuePosition) {
            val dashHasNoSpacePrefix = replacementStart > 0 &&
                document.charsSequence[replacementStart - 1] == '-' &&
                (replacementStart == 1 || document.charsSequence[replacementStart - 2].isWhitespace())
            if (dashHasNoSpacePrefix && !template.text.startsWith(" ")) {
                template = template.copy(
                    text = " ${template.text}",
                    caretOffsetInText = template.caretOffsetInText + 1
                )
            }
        }

        document.replaceString(replacementStart, endOffset, template.text)
        insertionContext.editor.caretModel.moveToOffset(replacementStart + template.caretOffsetInText)
        insertionContext.setAddCompletionChar(false)
        insertionContext.commitDocument()
    }

    private fun keyInsertTemplate(
        suggestion: String,
        fullPath: String,
        contextPath: String?,
        lineIndent: String,
        lineStartsWithDash: Boolean,
        insideSequenceItemMapping: Boolean
    ): InsertTemplate {
        val sequenceEntry = contextPath?.endsWith("[]") == true
        val nestedIndent = if (sequenceEntry || lineStartsWithDash) lineIndent + "    " else lineIndent + "  "
        val linePrefix = if (sequenceEntry && !lineStartsWithDash && !insideSequenceItemMapping) "- " else ""

        if (fullPath == "generate.inputs[].directory") {
            val text = "${linePrefix}${suggestion}:\n${nestedIndent}path: \"\"\n${nestedIndent}root: \".\""
            val caret = text.indexOf("\"\"").let { if (it >= 0) it + 1 else text.length }
            return InsertTemplate(text = text, caretOffsetInText = caret)
        }
        if (fullPath == "generate.inputs[].git_repo") {
            val text = "${linePrefix}${suggestion}:\n${nestedIndent}url: \"\"\n${nestedIndent}sub_directory: \"\"\n" +
                "${nestedIndent}root: \"\""
            val caret = text.indexOf("\"\"").let { if (it >= 0) it + 1 else text.length }
            return InsertTemplate(text = text, caretOffsetInText = caret)
        }
        if (fullPath == "generate.plugins[].command") {
            val text = "${linePrefix}${suggestion}:\n${nestedIndent}- "
            return InsertTemplate(text = text, caretOffsetInText = text.length)
        }

        return when (pathTypeHint(fullPath)) {
            "map" -> {
                val text = "${linePrefix}${suggestion}:\n$nestedIndent"
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            "array" -> {
                val text = "${linePrefix}${suggestion}:\n${nestedIndent}- "
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            "string" -> {
                val text = "${linePrefix}${suggestion}: \"\""
                InsertTemplate(text = text, caretOffsetInText = text.length - 1)
            }
            "boolean" -> {
                val text = "${linePrefix}${suggestion}: false"
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            "enum" -> {
                val enumValue = ENUM_DEFAULTS[fullPath] ?: "v1alpha"
                val text = "${linePrefix}${suggestion}: $enumValue"
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            "url" -> {
                val text = "${linePrefix}${suggestion}: \"https://github.com/org/repo.git\""
                InsertTemplate(text = text, caretOffsetInText = text.length - 1)
            }
            "any" -> {
                val text = "${linePrefix}${suggestion}: "
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            else -> {
                val text = "${linePrefix}${suggestion}: "
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
        }
    }

    private fun valueInsertTemplate(
        suggestion: String,
        valuePath: String?,
        lineIndent: String,
        lineStartsWithDash: Boolean,
        sequenceContext: Boolean
    ): InsertTemplate {
        val nestedIndent = if (lineStartsWithDash || sequenceContext) lineIndent + "    " else lineIndent + "  "
        return when (suggestion) {
            TEMPLATE_MAP -> {
                val text = "\n$nestedIndent"
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            TEMPLATE_ARRAY -> {
                val text = "\n${nestedIndent}- "
                InsertTemplate(text = text, caretOffsetInText = text.length)
            }
            TEMPLATE_STRING -> InsertTemplate(text = "\"\"", caretOffsetInText = 1)
            TEMPLATE_URL -> {
                val text = "\"https://github.com/org/repo.git\""
                InsertTemplate(text = text, caretOffsetInText = text.length - 1)
            }
            else -> {
                val inferred = when (pathTypeHint(valuePath ?: "")) {
                    "map" -> "\n$nestedIndent"
                    "array" -> "\n${nestedIndent}- "
                    "string" -> "\"\""
                    "url" -> "\"https://github.com/org/repo.git\""
                    "boolean" -> "false"
                    else -> suggestion
                }
                val caret = when (inferred) {
                    "\"\"" -> 1
                    "\"https://github.com/org/repo.git\"" -> inferred.length - 1
                    else -> inferred.length
                }
                InsertTemplate(text = inferred, caretOffsetInText = caret)
            }
        }
    }

    private data class LineMeta(
        val indent: String,
        val trimmedLine: String
    )

    private fun lineMeta(document: Document, offset: Int): LineMeta {
        if (document.textLength == 0) {
            return LineMeta(indent = "", trimmedLine = "")
        }
        val safe = offset.coerceIn(0, document.textLength - 1)
        val line = document.getLineNumber(safe)
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        val rawLine = document.charsSequence.subSequence(start, end).toString()
        val indentLen = rawLine.indexOfFirst { !it.isWhitespace() }.let { if (it < 0) rawLine.length else it }
        return LineMeta(
            indent = rawLine.substring(0, indentLen),
            trimmedLine = rawLine.trimStart()
        )
    }

    private fun sequenceDashReplacementStart(document: Document, rawOffset: Int): Int? {
        if (document.textLength == 0) return null
        val safeOffset = rawOffset.coerceIn(0, document.textLength)
        val anchorOffset = when {
            safeOffset <= 0 -> 0
            safeOffset >= document.textLength -> document.textLength - 1
            else -> safeOffset
        }
        val line = document.getLineNumber(anchorOffset)
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val lineText = document.charsSequence.subSequence(lineStart, lineEnd).toString()
        val caretColumn = (safeOffset - lineStart).coerceIn(0, lineText.length)
        val typedPrefix = lineText.substring(0, caretColumn)
        val dashIndex = typedPrefix.indexOf('-')
        if (dashIndex < 0) return null
        if (typedPrefix.substring(0, dashIndex).any { !it.isWhitespace() }) return null
        val afterDash = typedPrefix.substring(dashIndex + 1)
        if (afterDash.contains(':')) return null
        return lineStart + dashIndex
    }

    private fun shouldPreserveContainerContext(currentLine: String, previousLines: List<String>): Boolean {
        if (currentLine.trim().isNotEmpty()) return false
        if (leadingIndent(currentLine) != 0) return false
        val previousMeaningful = previousLines.asReversed()
            .firstOrNull { it.trim().isNotEmpty() && !it.trimStart().startsWith("#") }
            ?: return false
        val trimmed = previousMeaningful.trimStart()
        if (!trimmed.contains(':')) return false
        return if (trimmed.startsWith("-")) {
            val body = trimmed.removePrefix("-").trimStart()
            body.contains(":") && body.substringAfter(':', "").trim().isEmpty()
        } else {
            trimmed.substringAfter(':', "").trim().isEmpty()
        }
    }

    private fun keyTypeHint(contextPath: String?, key: String): String? {
        val fullPath = if (contextPath.isNullOrBlank()) key else "$contextPath.$key"
        return pathTypeHint(fullPath)
    }

    private fun valueTypeHint(suggestion: String, valuePath: String?): String? {
        return when {
            suggestion == "v1alpha" -> "enum"
            suggestion == "true" || suggestion == "false" -> "boolean"
            suggestion == TEMPLATE_ARRAY -> "array"
            suggestion == TEMPLATE_MAP -> "map"
            suggestion == TEMPLATE_STRING -> "string"
            suggestion == TEMPLATE_URL -> "url"
            else -> valuePath?.let { pathTypeHint(it) }
        }
    }

    private fun pathTypeHint(path: String): String? {
        return when {
            path == "version" -> "enum"
            BOOLEAN_VALUE_PATHS.contains(path) -> "boolean"
            URL_VALUE_PATHS.contains(path) -> "url"
            STRING_VALUE_PATHS.contains(path) -> "string"
            SEQUENCE_VALUE_PATHS.contains(path) -> "array"
            MAP_VALUE_PATHS.contains(path) -> "map"
            ANY_VALUE_PATHS.contains(path) -> "any"
            else -> null
        }
    }

    private fun contextualKeySuggestions(
        keyContextPath: String?,
        suggestions: List<String>,
        sequenceItem: YAMLSequenceItem?,
        mapping: YAMLMapping?,
        fileText: String,
        offset: Int
    ): List<String> {
        if (keyContextPath.isNullOrBlank()) {
            return suggestions
        }
        if (keyContextPath != "generate.plugins[]") {
            return suggestions
        }
        val line = lineText(fileText, offset).trimStart()
        val previousMeaningful = previousMeaningfulLine(fileText, offset)?.trimStart().orEmpty()
        val applyPluginStarterFilter = line.isBlank() && previousMeaningful.endsWith("plugins:")
        if (!applyPluginStarterFilter) {
            return suggestions
        }
        return suggestions.filter { PLUGIN_STARTER_KEYS.contains(it) }
    }

    private fun lineText(text: String, rawOffset: Int): String {
        if (text.isEmpty()) return ""
        val offset = rawOffset.coerceIn(0, text.length)
        val before = text.substring(0, offset)
        return before.substringAfterLast('\n')
    }

    private fun previousMeaningfulLine(text: String, rawOffset: Int): String? {
        if (text.isEmpty()) return null
        val offset = rawOffset.coerceIn(0, text.length)
        val before = text.substring(0, offset).substringBeforeLast('\n', "")
        if (before.isEmpty()) return null
        return before
            .lineSequence()
            .toList()
            .asReversed()
            .firstOrNull { it.trim().isNotEmpty() && !it.trimStart().startsWith("#") }
    }

    private fun filterPluginStarterSuggestionsForFallback(
        contextPath: String,
        suggestions: List<String>,
        trimmedCurrentLine: String,
        previousMeaningfulLine: String?
    ): List<String> {
        if (contextPath != "generate.plugins[]") {
            return suggestions
        }
        val previousTrimmed = previousMeaningfulLine?.trimStart().orEmpty()
        val applyPluginStarterFilter = trimmedCurrentLine.isBlank() && previousTrimmed.endsWith("plugins:")
        if (!applyPluginStarterFilter) {
            return suggestions
        }
        return suggestions.filter { PLUGIN_STARTER_KEYS.contains(it) }
    }

    private fun shouldPreferFallbackContext(fallback: String?, psi: String): Boolean {
        if (fallback.isNullOrBlank()) return false
        if (KEY_COMPLETIONS.containsKey(fallback)) return true
        if (psi.isBlank()) return true
        return isMoreSpecificContext(fallback, psi)
    }

    private fun isMoreSpecificContext(candidate: String, base: String): Boolean {
        if (candidate == base) return false
        if (candidate.startsWith("$base.")) return true
        if (candidate.startsWith("$base[]")) return true
        return candidate.count { it == '.' } > base.count { it == '.' }
    }

    private fun scalarSequenceItemCompletionsForPath(path: String?): List<String>? {
        if (path.isNullOrBlank()) return null
        if (!SCALAR_SEQUENCE_ITEM_PATHS.contains(path)) return null
        return listOf(TEMPLATE_STRING)
    }

    private class AlwaysVisiblePrefixMatcher(prefix: String) : PrefixMatcher(prefix) {
        override fun cloneWithPrefix(prefix: String): PrefixMatcher = AlwaysVisiblePrefixMatcher(prefix)
        override fun prefixMatches(name: String): Boolean = true
        override fun isStartMatch(name: String): Boolean = true
    }

    companion object {
        internal fun shouldAutoPopupFor(typeChar: Char, text: String, rawOffset: Int): Boolean {
            if (typeChar == ':' || typeChar == '-' || typeChar == '\n') {
                return true
            }
            if (typeChar != ' ' && typeChar != '\t') {
                return false
            }
            if (text.isEmpty()) {
                return false
            }
            val offset = rawOffset.coerceIn(0, text.length)
            val beforeCaret = text.substring(0, offset)
            val linePrefix = beforeCaret.substringAfterLast('\n')
            val trimmedLinePrefix = linePrefix.trimStart()
            if (trimmedLinePrefix.endsWith("-") || trimmedLinePrefix.endsWith(":")) {
                return true
            }
            // Trigger while typing indentation on a new line after container keys,
            // so nested completions appear without manual completion invoke.
            if (trimmedLinePrefix.isBlank()) {
                val previousMeaningful = beforeCaret.substringBeforeLast('\n', "")
                    .lineSequence()
                    .toList()
                    .asReversed()
                    .firstOrNull { it.trim().isNotEmpty() && !it.trimStart().startsWith("#") }
                    ?.trimStart()
                    ?: return false
                if (previousMeaningful.endsWith(":")) {
                    return true
                }
                if (previousMeaningful.startsWith("-")) {
                    val body = previousMeaningful.removePrefix("-").trimStart()
                    if (body.endsWith(":")) {
                        return true
                    }
                }
            }
            return false
        }

        private val KEY_COMPLETIONS: Map<String, List<String>> = mapOf(
            "" to listOf("version", "lint", "deps", "generate", "breaking"),
            "lint" to listOf(
                "use",
                "enum_zero_value_suffix",
                "service_suffix",
                "ignore",
                "except",
                "allow_comment_ignores",
                "ignore_only"
            ),
            "breaking" to listOf("ignore", "against_git_ref"),
            "generate" to listOf("inputs", "plugins", "managed"),
            "generate.inputs[]" to listOf("directory", "git_repo"),
            "generate.inputs[].directory" to listOf("path", "root"),
            "generate.inputs[].git_repo" to listOf("url", "sub_directory", "root"),
            "generate.plugins[]" to listOf("name", "remote", "path", "command", "out", "opts", "with_imports"),
            "generate.managed" to listOf("enabled", "disable", "override"),
            "generate.managed.disable[]" to listOf("module", "path", "file_option", "field_option", "field"),
            "generate.managed.override[]" to listOf("file_option", "field_option", "value", "module", "path", "field"),
        )

        private val BOOLEAN_VALUE_PATHS: Set<String> = setOf(
            "lint.allow_comment_ignores",
            "generate.plugins[].with_imports",
            "generate.managed.enabled"
        )

        private val URL_VALUE_PATHS: Set<String> = setOf(
            "generate.inputs[].git_repo.url"
        )

        private val STRING_VALUE_PATHS: Set<String> = setOf(
            "lint.enum_zero_value_suffix",
            "lint.service_suffix",
            "breaking.against_git_ref",
            "generate.inputs[].directory.path",
            "generate.inputs[].directory.root",
            "generate.inputs[].git_repo.sub_directory",
            "generate.inputs[].git_repo.root",
            "generate.plugins[].name",
            "generate.plugins[].remote",
            "generate.plugins[].path",
            "generate.plugins[].out",
            "generate.managed.disable[].module",
            "generate.managed.disable[].path",
            "generate.managed.disable[].file_option",
            "generate.managed.disable[].field_option",
            "generate.managed.disable[].field",
            "generate.managed.override[].file_option",
            "generate.managed.override[].field_option",
            "generate.managed.override[].module",
            "generate.managed.override[].path",
            "generate.managed.override[].field"
        )

        private val SEQUENCE_VALUE_PATHS: Set<String> = setOf(
            "deps",
            "lint.use",
            "lint.ignore",
            "lint.except",
            "breaking.ignore",
            "generate.inputs",
            "generate.plugins",
            "generate.plugins[].command",
            "generate.managed.disable",
            "generate.managed.override"
        )

        private val MAP_VALUE_PATHS: Set<String> = setOf(
            "lint",
            "lint.ignore_only",
            "breaking",
            "generate",
            "generate.managed",
            "generate.inputs[].directory",
            "generate.inputs[].git_repo",
            "generate.plugins[].opts"
        )

        private val ANY_VALUE_PATHS: Set<String> = setOf(
            "generate.managed.override[].value"
        )

        private val SCALAR_SEQUENCE_ITEM_PATHS: Set<String> = setOf(
            "deps[]",
            "lint.use[]",
            "lint.ignore[]",
            "lint.except[]",
            "breaking.ignore[]",
            "generate.plugins[].command[]"
        )

        private val PLUGIN_STARTER_KEYS: Set<String> = setOf(
            "remote",
            "path",
            "command",
            "name"
        )

        private val ENUM_DEFAULTS: Map<String, String> = mapOf(
            "version" to "v1alpha"
        )

        private const val TEMPLATE_ARRAY = "<array>"
        private const val TEMPLATE_MAP = "<map>"
        private const val TEMPLATE_STRING = "<string>"
        private const val TEMPLATE_URL = "<url>"
    }
}
