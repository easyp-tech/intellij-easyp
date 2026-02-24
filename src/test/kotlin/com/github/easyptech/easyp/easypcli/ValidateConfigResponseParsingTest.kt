package com.github.easyptech.easyp.easypcli

import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class ValidateConfigResponseParsingTest : BasePlatformTestCase() {
    fun testValidateConfigResponseParsesErrorsAndWarnings() {
        val raw = """
            {
              "valid": false,
              "errors": [
                {
                  "code": "yaml_validation",
                  "message": "directory.path is required",
                  "line": 7,
                  "column": 9,
                  "severity": "error"
                }
              ],
              "warnings": [
                {
                  "code": "yaml_validation",
                  "message": "unknown key \"unknown_top\"",
                  "line": 2,
                  "column": 1,
                  "severity": "warn"
                }
              ]
            }
        """.trimIndent()

        val response = EasypCliJsonParser.parseValidateConfigResponse(raw)
        assertNotNull(response)
        val parsed = response!!
        assertFalse(parsed.valid)
        assertSize(1, parsed.errors)
        assertSize(1, parsed.warnings)
        assertEquals("error", parsed.errors.single().severity)
        assertEquals("warn", parsed.warnings.single().severity)
    }

    fun testSeverityMappingSupportsWarn() {
        assertEquals(HighlightSeverity.ERROR, EasypValidationPresentation.toHighlightSeverity("error"))
        assertEquals(HighlightSeverity.WARNING, EasypValidationPresentation.toHighlightSeverity("warn"))
        assertEquals(HighlightSeverity.WARNING, EasypValidationPresentation.toHighlightSeverity("warning"))
        assertEquals(HighlightSeverity.WEAK_WARNING, EasypValidationPresentation.toHighlightSeverity("info"))
    }

    fun testLineAndColumnMapToDocumentRange() {
        val document = EditorFactory.getInstance().createDocument(
            """
            version: v1alpha
            unknown_top: 1
            """.trimIndent()
        )

        val range = EasypValidationPresentation.toTextRange(document, line = 2, column = 3)
        assertNotNull(range)

        val expectedStart = document.getLineStartOffset(1) + 2
        val expectedEnd = document.getLineEndOffset(1)
        assertEquals(expectedStart, range!!.startOffset)
        assertEquals(expectedEnd, range.endOffset)
    }

    fun testInvalidLineReturnsNoRange() {
        val document = EditorFactory.getInstance().createDocument("version: v1alpha")
        val range = EasypValidationPresentation.toTextRange(document, line = 9, column = 1)
        assertNull(range)
    }
}
