package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.ExternalAnnotator
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import org.jetbrains.annotations.TestOnly
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

class EasypConfigAnnotator : ExternalAnnotator<EasypConfigAnnotator.CollectInfo, ValidateConfigResponse>() {
    private val log = Logger.getInstance(EasypConfigAnnotator::class.java)

    data class CollectInfo(
        val file: PsiFile,
        val snapshot: String,
        val modificationStamp: Long,
        val targetPath: String
    )

    private data class CacheKey(val targetPath: String, val contentHash: String)
    private data class CacheEntry(
        val timestampMs: Long,
        val contentHash: String,
        val response: ValidateConfigResponse
    )

    override fun collectInformation(file: PsiFile, editor: Editor, hasErrors: Boolean): CollectInfo? {
        val virtualFile = file.virtualFile ?: return null
        val project = file.project
        val settings = EasypSettings.getInstance(project).state
        if (!EasypConfigTarget.isTargetConfigFile(virtualFile, project, settings)) {
            log.debug("[easyp-annotator] skip non-target file=${virtualFile.path}")
            return null
        }

        val document = PsiDocumentManager.getInstance(project).getDocument(file)
        val snapshot = document?.text ?: file.text
        val stamp = document?.modificationStamp ?: file.modificationStamp
        val targetPath = EasypConfigTarget.resolvedConfigPath(project, settings)?.toString() ?: virtualFile.path
        log.warn(
            "[easyp-annotator] collect file=${virtualFile.path} target=$targetPath stamp=$stamp size=${snapshot.length}"
        )

        return CollectInfo(
            file = file,
            snapshot = snapshot,
            modificationStamp = stamp,
            targetPath = targetPath
        )
    }

    override fun doAnnotate(collectInfo: CollectInfo?): ValidateConfigResponse? {
        collectInfo ?: return null

        val contentHash = hashContent(collectInfo.snapshot)
        val cacheKey = CacheKey(collectInfo.targetPath, contentHash)
        responseCache[cacheKey]?.let { cached ->
            log.warn(
                "[easyp-annotator] cache-hit target=${collectInfo.targetPath} hash=${contentHash.take(12)} " +
                    "errors=${cached.response.errors.size} warnings=${cached.response.warnings.size}"
            )
            return cached.response
        }

        val now = System.currentTimeMillis()
        val lastResult = debounceCache[collectInfo.targetPath]
        if (
            lastResult != null &&
            lastResult.contentHash == contentHash &&
            now - lastResult.timestampMs < DEBOUNCE_MS
        ) {
            log.warn(
                "[easyp-annotator] debounce-hit target=${collectInfo.targetPath} hash=${contentHash.take(12)} " +
                    "ageMs=${now - lastResult.timestampMs}"
            )
            return lastResult.response
        }

        log.warn("[easyp-annotator] validate-start target=${collectInfo.targetPath} hash=${contentHash.take(12)}")
        val response = validateSnapshot(collectInfo) ?: EMPTY_RESPONSE.also {
            log.warn(
                "[easyp-annotator] validate returned null, using empty response to clear stale annotations " +
                    "target=${collectInfo.targetPath}"
            )
        }
        log.warn(
            "[easyp-annotator] validate-done target=${collectInfo.targetPath} hash=${contentHash.take(12)} " +
                "valid=${response.valid} errors=${response.errors.size} warnings=${response.warnings.size}"
        )

        val entry = CacheEntry(timestampMs = now, contentHash = contentHash, response = response)
        responseCache[cacheKey] = entry
        debounceCache[collectInfo.targetPath] = entry
        return response
    }

    override fun apply(file: PsiFile, annotationResult: ValidateConfigResponse?, holder: AnnotationHolder) {
        annotationResult ?: return

        val document = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
        val issues = annotationResult.errors + annotationResult.warnings
        log.warn(
            "[easyp-annotator] apply file=${file.virtualFile?.path ?: file.name} " +
                "issues=${issues.size} errors=${annotationResult.errors.size} warnings=${annotationResult.warnings.size}"
        )

        for (issue in issues) {
            val severity = EasypValidationPresentation.toHighlightSeverity(issue.severity)
            val range = EasypValidationPresentation.toTextRange(document, issue.line, issue.column)
            val annotationBuilder = holder.newAnnotation(severity, issue.message)
            if (range != null) {
                annotationBuilder.range(range).create()
            } else {
                annotationBuilder.fileLevel().create()
            }
        }
    }

    private fun validateSnapshot(collectInfo: CollectInfo): ValidateConfigResponse? {
        val tempConfig = try {
            Files.createTempFile("easyp-config-", ".yaml")
        } catch (t: Throwable) {
            log.warn("Failed to create temporary config file for easyp validate-config", t)
            return null
        }

        return try {
            Files.writeString(tempConfig, collectInfo.snapshot, StandardCharsets.UTF_8)
            val response = EasypCli(collectInfo.file.project).validateConfig(
                cfgOverridePath = tempConfig.toString(),
                timeoutMillis = VALIDATE_TIMEOUT_MS
            )
            if (response == null) {
                log.warn(
                    "[easyp-annotator] validateSnapshot: cli returned null for temp=${tempConfig.fileName}"
                )
            }
            response
        } catch (t: Throwable) {
            log.warn("Failed to run easyp validate-config on editor snapshot", t)
            null
        } finally {
            runCatching { Files.deleteIfExists(tempConfig) }
        }
    }

    private fun hashContent(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    companion object {
        private const val DEBOUNCE_MS = 500L
        private const val VALIDATE_TIMEOUT_MS = 1500
        private val EMPTY_RESPONSE = ValidateConfigResponse(valid = true)
        private val responseCache = ConcurrentHashMap<CacheKey, CacheEntry>()
        private val debounceCache = ConcurrentHashMap<String, CacheEntry>()

        @TestOnly
        internal fun clearCachesForTests() {
            responseCache.clear()
            debounceCache.clear()
        }
    }
}

internal object EasypValidationPresentation {
    fun toHighlightSeverity(severity: String?): HighlightSeverity {
        return when (severity?.lowercase()) {
            "error" -> HighlightSeverity.ERROR
            "warn", "warning" -> HighlightSeverity.WARNING
            "info" -> HighlightSeverity.WEAK_WARNING
            else -> HighlightSeverity.WEAK_WARNING
        }
    }

    fun toTextRange(document: Document, line: Int?, column: Int?): TextRange? {
        if (line == null || line <= 0) {
            return null
        }

        val lineIndex = line - 1
        if (lineIndex < 0 || lineIndex >= document.lineCount) {
            return null
        }

        val startOffset = document.getLineStartOffset(lineIndex)
        val endOffset = document.getLineEndOffset(lineIndex)
        if (endOffset <= startOffset) {
            return null
        }

        val rangeStart = if (column != null && column > 0) {
            val requested = startOffset + (column - 1)
            if (requested in startOffset until endOffset) requested else startOffset
        } else {
            startOffset
        }

        return TextRange(rangeStart, endOffset)
    }
}
