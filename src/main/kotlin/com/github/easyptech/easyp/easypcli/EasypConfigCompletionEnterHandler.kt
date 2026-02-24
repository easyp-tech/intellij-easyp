package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.codeInsight.AutoPopupController
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.editorActions.enter.EnterHandlerDelegate
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.util.Condition
import com.intellij.psi.PsiFile
import org.jetbrains.yaml.psi.YAMLFile

class EasypConfigCompletionEnterHandler : EnterHandlerDelegate {
    private val log = Logger.getInstance(EasypConfigCompletionEnterHandler::class.java)

    override fun postProcessEnter(file: PsiFile, editor: Editor, dataContext: DataContext): EnterHandlerDelegate.Result {
        val yamlFile = file as? YAMLFile ?: return EnterHandlerDelegate.Result.Continue
        val virtualFile = yamlFile.virtualFile ?: return EnterHandlerDelegate.Result.Continue
        val project = file.project
        val settings = EasypSettings.getInstance(project).state
        if (!EasypConfigTarget.isTargetConfigFile(virtualFile, project, settings)) {
            return EnterHandlerDelegate.Result.Continue
        }

        val popupController = AutoPopupController.getInstance(project)
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) {
                return@invokeLater
            }
            popupController.autoPopupMemberLookup(
                editor,
                CompletionType.BASIC,
                Condition { psiFile ->
                    val yaml = psiFile as? YAMLFile ?: return@Condition false
                    val vf = yaml.virtualFile ?: return@Condition false
                    EasypConfigTarget.isTargetConfigFile(vf, project, settings)
                }
            )
        }
        log.warn(
            "[easyp-completion] enter-handler auto-popup file=${virtualFile.path} offset=${editor.caretModel.offset}"
        )
        return EnterHandlerDelegate.Result.Continue
    }
}
