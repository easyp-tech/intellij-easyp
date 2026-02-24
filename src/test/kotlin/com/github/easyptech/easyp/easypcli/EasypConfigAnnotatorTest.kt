package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.YAMLFileType
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class EasypConfigAnnotatorTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        EasypConfigAnnotator.clearCachesForTests()
    }

    fun testAnnotatorUsesUnsavedEditorSnapshot() {
        val fakeCli = createFakeCliScript()
        val settings = EasypSettings.getInstance(project).state
        settings.easypCliPath = fakeCli
        val annotator = EasypConfigAnnotator()

        val file = myFixture.configureByText(
            YAMLFileType.YML,
            """
            version: v1alpha
            """.trimIndent()
        )
        settings.configPath = file.virtualFile.name

        val firstInfo = annotator.collectInformation(file, myFixture.editor, false)
        assertNotNull(firstInfo)
        val firstResult = annotator.doAnnotate(firstInfo)
        assertTrue(firstResult != null)
        assertTrue(firstResult!!.warnings.isEmpty())

        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.setText(
                """
                unknown_top: 1
                version: v1alpha
                """.trimIndent()
            )
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        Thread.sleep(600)
        val secondInfo = annotator.collectInformation(file, myFixture.editor, false)
        assertNotNull(secondInfo)
        val secondResult = annotator.doAnnotate(secondInfo)
        assertTrue(secondResult != null)
        assertTrue(secondResult!!.warnings.any { issue -> issue.message.contains("unknown key \"unknown_top\"") })
    }

    fun testAnnotatorSkipsNonTargetYamlWhenConfigPathOverridden() {
        val fakeCli = createFakeCliScript()
        val settings = EasypSettings.getInstance(project).state
        settings.easypCliPath = fakeCli
        settings.configPath = "api/proto/custom-easyp.yaml"
        val annotator = EasypConfigAnnotator()

        val file = myFixture.configureByText(
            YAMLFileType.YML,
            """
            unknown_top: 1
            version: v1alpha
            """.trimIndent()
        )

        val info = annotator.collectInformation(file, myFixture.editor, false)
        assertNull(info)
    }

    fun testDebounceDoesNotReturnStaleResultForChangedContent() {
        val fakeCli = createFakeCliScript()
        val settings = EasypSettings.getInstance(project).state
        settings.easypCliPath = fakeCli
        val annotator = EasypConfigAnnotator()

        val file = myFixture.configureByText(
            YAMLFileType.YML,
            """
            unknown_top: 1
            version: v1alpha
            """.trimIndent()
        )
        settings.configPath = file.virtualFile.name

        val firstInfo = annotator.collectInformation(file, myFixture.editor, false)
        assertNotNull(firstInfo)
        val firstResult = annotator.doAnnotate(firstInfo)
        assertNotNull(firstResult)
        assertTrue(firstResult!!.warnings.isNotEmpty())

        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.setText("version: v1alpha")
            PsiDocumentManager.getInstance(project).commitDocument(document)
        }

        // No sleep: the second request is inside debounce window and must still revalidate because content changed.
        val secondInfo = annotator.collectInformation(file, myFixture.editor, false)
        assertNotNull(secondInfo)
        val secondResult = annotator.doAnnotate(secondInfo)
        assertNotNull(secondResult)
        assertTrue(secondResult!!.warnings.isEmpty())
        assertTrue(secondResult.errors.isEmpty())
    }

    private fun createFakeCliScript(): String {
        val scriptPath = Files.createTempFile("fake-easyp-", ".sh")
        val script = """
            #!/bin/sh
            set -eu
            CFG=""
            while [ "${'$'}#" -gt 0 ]; do
              case "${'$'}1" in
                --cfg|--config)
                  CFG="${'$'}2"
                  shift 2
                  ;;
                *)
                  shift
                  ;;
              esac
            done
            
            if [ -n "${'$'}CFG" ] && grep -q "unknown_top" "${'$'}CFG"; then
              cat <<'JSON'
            {"valid": true, "warnings": [{"code":"yaml_validation","message":"unknown key \"unknown_top\"","line":1,"column":1,"severity":"warn"}]}
            JSON
            else
              cat <<'JSON'
            {"valid": true}
            JSON
            fi
        """.trimIndent()

        Files.writeString(scriptPath, script, StandardCharsets.UTF_8)
        val file = scriptPath.toFile()
        file.setExecutable(true)
        return file.absolutePath
    }
}
