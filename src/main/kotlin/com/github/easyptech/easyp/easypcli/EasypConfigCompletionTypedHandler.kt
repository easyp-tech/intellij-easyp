package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile

class EasypConfigCompletionTypedHandler : TypedHandlerDelegate() {
    private val log = Logger.getInstance(EasypConfigCompletionTypedHandler::class.java)

    override fun checkAutoPopup(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        if (tryScheduleAutoPopup(charTyped, project, editor, file, "checkAutoPopup")) {
            return Result.CONTINUE
        }
        return Result.CONTINUE
    }

    override fun charTyped(charTyped: Char, project: Project, editor: Editor, file: PsiFile): Result {
        // Enter/new-line paths are not consistently routed through checkAutoPopup
        // in all editor handlers; this keeps auto-completion responsive in nested YAML blocks.
        tryScheduleAutoPopup(charTyped, project, editor, file, "charTyped")
        return Result.CONTINUE
    }

    private fun tryScheduleAutoPopup(
        charTyped: Char,
        project: Project,
        editor: Editor,
        file: PsiFile,
        origin: String
    ): Boolean {
        val yamlFile = file as? YAMLFile ?: return false
        val virtualFile = yamlFile.virtualFile ?: return false
        val settings = EasypSettings.getInstance(project).state
        if (!EasypConfigTarget.isTargetConfigFile(virtualFile, project, settings)) {
            return false
        }

        val documentText = editor.document.charsSequence.toString()
        val offset = editor.caretModel.offset
        if (!EasypConfigCompletionContributor.shouldAutoPopupFor(charTyped, documentText, offset)) {
            return false
        }

        AutoPopupController.getInstance(project).scheduleAutoPopup(editor)
        log.warn(
            "[easyp-completion] typed-handler auto-popup origin=$origin file=${virtualFile.path} " +
                "char=$charTyped offset=$offset"
        )
        return true
    }
}
