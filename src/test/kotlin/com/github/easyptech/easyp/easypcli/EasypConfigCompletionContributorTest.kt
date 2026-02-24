package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.jetbrains.yaml.YAMLFileType

class EasypConfigCompletionContributorTest : BasePlatformTestCase() {
    private val contributor = EasypConfigCompletionContributor()

    fun testTopLevelCompletionContainsCoreSections() {
        val variants = contributor.collectSuggestionsFromText("", 0)

        assertContainsElements(variants, "generate", "lint", "breaking")
    }

    fun testGenerateInputsSuggestsOnlyDirectoryAndGitRepo() {
        val text = """
            generate:
              inputs:
                - 
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "directory", "git_repo")
        assertDoesntContain(variants, "plugins")
    }

    fun testVersionValueCompletionSuggestsV1alpha() {
        val text = "version: "
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "v1alpha")
    }

    fun testTopLevelCompletionAfterNestedBlockAtRootIndent() {
        val text = """
            generate:
              plugins:
                - remote: "example.com/plugin"
                  out: gen

        """.trimIndent() + "\n"
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "version", "lint", "deps", "breaking")
    }

    fun testCompletionIsDisabledForNonTargetYaml() {
        val settings = EasypSettings.getInstance(project).state
        settings.configPath = "api/proto/custom-easyp.yaml"

        val file = myFixture.configureByText(YAMLFileType.YML, "<caret>")
        val variants = suggestionVariants(file, settings)

        assertDoesntContain(variants, "generate")
        assertDoesntContain(variants, "lint")
    }

    fun testCompletionSuggestsPluginFieldsOnNewIndentedLineInPluginItemTextFallback() {
        val text = "generate:\n" +
            "  plugins:\n" +
            "    - remote: \"example.com/plugin\"\n" +
            "      out: gen\n" +
            "      "

        val variants = contributor.collectSuggestionsFromText(text, text.length)
        assertContainsElements(variants, "name", "remote", "path", "command", "out", "opts", "with_imports")
    }

    fun testCompletionSuggestsPluginFieldsForPartialSequenceItemKey() {
        val text = "generate:\n" +
            "  plugins:\n" +
            "    - remote: \"example.com/plugin\"\n" +
            "      out: gen\n" +
            "    - c"

        val variants = contributor.collectSuggestionsFromText(text, text.length)
        assertContainsElements(variants, "name", "remote", "path", "command", "out", "opts", "with_imports")
    }

    fun testCompletionForPaymentBackendLikeConfigDashC() {
        val text = """
            deps: 
              - github.com/googleapis/googleapis
              - github.com/google/gnostic
            
            generate:
              inputs:
                - directory:
                    path: api
                    root: "."
            
              plugins:
                - remote: "api.beta.easyp.tech/protocolbuffers/go"
                  out: internal/pb
                  opts:
                    paths: source_relative
            
                - remote: "api.beta.easyp.tech/grpc/go"
                  out: internal/pb
                  opts:
                    paths: source_relative
                    require_unimplemented_servers: false
            
                - remote: "api.beta.easyp.tech/grpc-ecosystem/gateway:v2.27.3"
                  out: internal/pb
                  opts:
                    paths: source_relative
                - remote: "api.beta.easyp.tech/community/google-gnostic-openapi:v0.7.0"
                  out: internal/pb
                  opts:
                    paths: source_relative
                - path: "hello"
                  opts:
                    require_unimplemented_servers:
                      - hello
                      - umbrella
                    paths: rel
                    opt:
                    null:
                - c
        """.trimIndent()

        val variants = contributor.collectSuggestionsFromText(text, text.length)
        assertContainsElements(variants, "command", "remote", "path", "name", "opts", "out", "with_imports")
    }

    fun testArrayTemplateValueCompletionForDeps() {
        val text = "deps: "
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "<array>")
    }

    fun testMapTemplateValueCompletionForManaged() {
        val text = """
            generate:
              managed: 
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "<map>")
    }

    fun testStringTemplateValueCompletionForPluginOut() {
        val text = """
            generate:
              plugins:
                - out: 
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "<string>")
    }

    fun testUrlValueCompletionForGitRepoUrl() {
        val text = """
            generate:
              inputs:
                - git_repo:
                    url: 
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "<url>")
    }

    fun testSequenceItemCompletionOnBlankLineSuggestsInputVariants() {
        val text = """
            generate:
              inputs:
                
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "directory", "git_repo")
    }

    fun testGitRepoNestedFieldsCompletionAfterEnterWithoutIndent() {
        val text = """
            generate:
              inputs:
                - git_repo:
        """.trimIndent() + "\n"
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "url", "sub_directory", "root")
        assertDoesntContain(variants, "out")
    }

    fun testGitRepoCompletionDoesNotContainOut() {
        val text = """
            generate:
              inputs:
                - 
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "directory", "git_repo")
        assertDoesntContain(variants, "out")
    }

    fun testPluginsBlankLineSuggestsOnlyPluginStarters() {
        val text = """
            generate:
              plugins:
                
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "remote", "path", "command", "name")
        assertDoesntContain(variants, "out", "opts", "with_imports")
    }

    fun testCommandItemsDoNotSuggestPluginLevelKeys() {
        val text = """
            generate:
              plugins:
                - remote: "api.beta.easyp.tech/grpc/go"
                  out: internal/pb
                  opts:
                    paths: source_relative
                  command:
                    -
        """.trimIndent()
        val variants = contributor.collectSuggestionsFromText(text, text.length)

        assertContainsElements(variants, "<string>")
        assertDoesntContain(variants, "name", "remote", "path", "opts", "command", "with_imports")
    }

    fun testAutoPopupOnIndentAfterContainerLine() {
        val text = """
            generate:
              inputs:
                
        """.trimIndent()
        val should = contributor.shouldAutoPopup(' ', text, text.length)

        assertTrue(should)
    }

    fun testAutoPopupOnSpaceAfterDash() {
        val text = """
            generate:
              inputs:
                -
        """.trimIndent()
        val should = contributor.shouldAutoPopup(' ', text, text.length)

        assertTrue(should)
    }

    fun testAutoPopupOnSpaceAfterColon() {
        val text = "version:"
        val should = contributor.shouldAutoPopup(' ', text, text.length)

        assertTrue(should)
    }

    fun testAutoPopupOnPlainSpaceTextIsDisabled() {
        val text = "remote"
        val should = contributor.shouldAutoPopup(' ', text, text.length)

        assertFalse(should)
    }

    private fun suggestionVariants(file: PsiFile, settings: EasypSettings.State): List<String> {
        val caretOffset = myFixture.caretOffset
        val fallbackOffset = if (caretOffset > 0) caretOffset - 1 else caretOffset
        val position = file.findElementAt(caretOffset) ?: file.findElementAt(fallbackOffset) ?: file
        return contributor.collectSuggestions(position, file, settings)
    }
}
