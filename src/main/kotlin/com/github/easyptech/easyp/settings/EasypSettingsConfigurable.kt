package com.github.easyptech.easyp.settings

import com.github.easyptech.easyp.easypcli.EasypImportResolver
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JButton
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import java.time.Instant

class EasypSettingsConfigurable(private val project: Project) : Configurable {
    private var panel: JPanel? = null
    private val easypPathField = TextFieldWithBrowseButton()
    private val configPathField = JBTextField()
    private val enableResolve = JBCheckBox("Enable .proto import resolve via Easyp", true)
    private val refreshButton = JButton("Refresh now")
    private val statusLabel = JBLabel("")

    override fun getDisplayName(): String = "Tools | Easyp"

    override fun createComponent(): JComponent? {
        if (panel == null) {
            try {
                val p = JPanel(VerticalLayout(8))

                easypPathField.addBrowseFolderListener(
                    project,
                    FileChooserDescriptorFactory.createSingleFileOrExecutableAppDescriptor()
                        .withTitle("Select Easyp CLI")
                )

                p.add(JBLabel("Path to easyp CLI (optional)"))
                p.add(easypPathField)

                p.add(JBLabel("Config file (optional, default: easyp.yaml in project root; relative path is from project root)"))
                p.add(configPathField)

                p.add(enableResolve)
                refreshButton.addActionListener {
                    val taskTitle = "Refreshing easyp Cache"
                    ProgressManager.getInstance().runProcessWithProgressSynchronously({
                        val indicator: ProgressIndicator = ProgressManager.getInstance().progressIndicator ?: return@runProcessWithProgressSynchronously
                        indicator.text = "Calling easyp ls-files"
                        val ok = try {
                            EasypImportResolver.getInstance(project).refreshCache()
                        } catch (t: Throwable) {
                            Logger.getInstance(EasypSettingsConfigurable::class.java)
                                .warn("Manual refresh failed: ${t.message}", t)
                            false
                        }
                        val type = if (ok) NotificationType.INFORMATION else NotificationType.WARNING
                        val content = if (ok) "Easyp cache refreshed" else "Easyp refresh failed. See idea.log"
                        Notifications.Bus.notify(Notification("Easyp", "Easyp", content, type), project)
                    }, taskTitle, false, project)
                    updateStatus()
                }
                p.add(refreshButton)
                p.add(statusLabel)
                updateStatus()

                panel = p
                reset()
            } catch (e: Exception) {
                return JPanel().apply {
                    add(JBLabel("Error loading settings: ${e.message}"))
                }
            }
        }
        return panel
    }

    override fun isModified(): Boolean {
        val s = EasypSettings.getInstance(project).state
        val path = easypPathField.text.ifBlank { null }
        val cfg = configPathField.text.ifBlank { null }
        return s.easypCliPath != path || s.configPath != cfg || s.enableProtoImportResolve != enableResolve.isSelected
    }

    override fun apply() {
        try {
            val service = EasypSettings.getInstance(project)
            val s = service.state
            val oldPath = s.easypCliPath
            val oldCfg = s.configPath
            val oldEnabled = s.enableProtoImportResolve
            s.easypCliPath = easypPathField.text.ifBlank { null }
            s.configPath = configPathField.text.ifBlank { null }
            s.enableProtoImportResolve = enableResolve.isSelected

            val shouldRefresh = oldPath != s.easypCliPath || oldCfg != s.configPath || (!oldEnabled && s.enableProtoImportResolve)
            if (shouldRefresh) {
                val app = ApplicationManager.getApplication()
                app.executeOnPooledThread {
                    val ok = try {
                        EasypImportResolver.getInstance(project).refreshCache()
                    } catch (t: Throwable) {
                        Logger.getInstance(EasypSettingsConfigurable::class.java)
                            .warn("Apply-triggered refresh failed: ${t.message}", t)
                        false
                    }
                    val type = if (ok) NotificationType.INFORMATION else NotificationType.WARNING
                    val content = if (ok) "Easyp cache refreshed" else "Easyp refresh failed. See idea.log"
                    Notifications.Bus.notify(Notification("Easyp", "Easyp", content, type), project)
                    app.invokeLater { updateStatus() }
                }
            }
        } catch (e: Exception) {
            // Логируем ошибку, но не падаем
            Logger.getInstance(EasypSettingsConfigurable::class.java).error("Error applying settings", e)
        }
    }

    override fun reset() {
        try {
            val s = EasypSettings.getInstance(project).state
            easypPathField.text = s.easypCliPath ?: ""
            configPathField.text = s.configPath ?: ""
            enableResolve.isSelected = s.enableProtoImportResolve
            updateStatus()
        } catch (e: Exception) {
            // Логируем ошибку, но не падаем
            Logger.getInstance(EasypSettingsConfigurable::class.java).error("Error resetting settings", e)
        }
    }

    override fun disposeUIResources() {
        panel = null
    }

    private fun updateStatus() {
        val r = EasypImportResolver.getInstance(project)
        val ts = r.getLastRefreshMillis()
        val time = if (ts > 0) Instant.ofEpochMilli(ts).toString() else "N/A"
        statusLabel.text = "Last refresh: $time"
    }
}
