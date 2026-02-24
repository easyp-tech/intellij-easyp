package com.github.easyptech.easyp.easypcli

import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path
import java.nio.file.Paths

private const val DEFAULT_CONFIG_FILE = "easyp.yaml"

object EasypConfigTarget {
    fun resolvedConfigPath(project: Project, settings: EasypSettings.State): Path? {
        val rawPath = configuredPath(settings)
        val configured = runCatching { Paths.get(rawPath) }.getOrNull() ?: return null
        if (configured.isAbsolute) {
            return configured.normalize()
        }

        val basePath = project.basePath ?: return null
        return runCatching { Paths.get(basePath).resolve(configured).normalize() }.getOrNull()
    }

    fun isTargetConfigFile(virtualFile: VirtualFile, project: Project, settings: EasypSettings.State): Boolean {
        val configuredFileName = runCatching {
            Paths.get(configuredPath(settings)).fileName?.toString()
        }.getOrNull() ?: configuredPath(settings)

        if (virtualFile.fileSystem.protocol != "file") {
            return virtualFile.name == configuredFileName
        }

        val resolved = resolvedConfigPath(project, settings)
        if (resolved != null) {
            val resolvedAbsolute = resolved.toAbsolutePath().normalize()
            val virtualAbsolute = runCatching {
                Paths.get(virtualFile.path).toAbsolutePath().normalize()
            }.getOrNull() ?: return false
            return virtualAbsolute == resolvedAbsolute
        }

        // Only fallback to name matching when project base is unavailable.
        if (project.basePath == null) {
            return virtualFile.name == configuredFileName
        }
        return false
    }

    private fun configuredPath(settings: EasypSettings.State): String {
        return settings.configPath?.trim().takeUnless { it.isNullOrEmpty() } ?: DEFAULT_CONFIG_FILE
    }
}
