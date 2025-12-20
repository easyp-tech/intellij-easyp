package com.github.yakwilik.intellijeasyp.startup

import com.github.yakwilik.intellijeasyp.easyp.EasypImportResolver
import com.github.yakwilik.intellijeasyp.settings.EasypSettings
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class MyProjectActivity : ProjectActivity {
    
    init {
        // Логируем при загрузке класса
        thisLogger().warn("=== Easyp Plugin: MyProjectActivity class loaded ===")
    }

    override suspend fun execute(project: Project) {
        // Инициализируем кеш easyp-cli данных при старте проекта
        val logger = thisLogger()
        logger.warn("=== Easyp Plugin: ProjectActivity.execute() called for project: ${project.name} ===")
        try {
            val settings = EasypSettings.getInstance(project)
            val enabled = settings.state.enableProtoImportResolve
            val easypCliPath = settings.state.easypCliPath
            logger.warn("Easyp project activity: enableProtoImportResolve=$enabled, easypCliPath=$easypCliPath")
            
            if (enabled) {
                val resolver = EasypImportResolver.getInstance(project)
                logger.warn("Calling refreshCache()...")
                val success = resolver.refreshCache()
                logger.warn("Easyp import resolver cache refresh: success=$success")
            } else {
                logger.warn("Easyp import resolver is DISABLED, skipping cache refresh")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize easyp import resolver cache: ${e.message}", e)
        }
        logger.warn("=== Easyp Plugin: ProjectActivity.execute() completed ===")
    }
}