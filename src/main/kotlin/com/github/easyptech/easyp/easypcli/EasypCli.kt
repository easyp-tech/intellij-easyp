package com.github.easyptech.easyp.easypcli

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Клиент для взаимодействия с CLI-утилитой `easyp`.
 *
 * **Роль в архитектуре:**
 * Этот класс отвечает исключительно за запуск внешнего процесса и десериализацию его вывода.
 * Он не хранит состояние и не знает о внутренней логике IDE (индексах, VFS и т.д.).
 * Используется сервисом [EasypImportResolver] для получения сырых данных.
 */
class EasypCli(private val project: Project) {
    private val log = Logger.getInstance(EasypCli::class.java)

    /**
     * Выполняет команду `easyp ls-files --format json`.
     *
     * @param timeoutMillis Максимальное время ожидания выполнения команды (по умолчанию 3 сек).
     * @return Объект с данными о файлах и корнях или `null`, если произошла ошибка запуска/парсинга.
     */
    fun lsFiles(timeoutMillis: Int = 3000): LsFilesResponse? {
        val stdout = executeJsonCommand(
            subCommand = listOf("ls-files"),
            timeoutMillis = timeoutMillis,
            configFlagMode = ConfigFlagMode.GLOBAL
        ) ?: return null

        return EasypCliJsonParser.parseLsFilesResponse(stdout).also { parsed ->
            if (parsed == null) {
                log.warn(
                    "[easyp-cli] failed to parse ls-files response as JSON raw=${stdout.compactForLog(280)}"
                )
            } else {
                log.warn("[easyp-cli] ls-files parsed files=${parsed.files.size} roots=${parsed.roots.size}")
            }
        }
    }

    /**
     * Выполняет команду `easyp validate-config --format json`.
     *
     * @param cfgOverridePath Путь к временному config файлу (используется для валидации несохраненных правок).
     * @param timeoutMillis Максимальное время ожидания выполнения команды (по умолчанию 3 сек).
     * @return Список ошибок валидации или `null`, если произошла системная ошибка.
     */
    fun validateConfig(cfgOverridePath: String? = null, timeoutMillis: Int = 3000): ValidateConfigResponse? {
        val stdout = executeJsonCommand(
            subCommand = listOf("validate-config"),
            timeoutMillis = timeoutMillis,
            cfgOverridePath = cfgOverridePath,
            configFlagMode = ConfigFlagMode.SUBCOMMAND
        ) ?: return null

        return EasypCliJsonParser.parseValidateConfigResponse(stdout).also { parsed ->
            if (parsed == null) {
                log.warn(
                    "[easyp-cli] failed to parse validate-config response as JSON raw=${stdout.compactForLog(280)}"
                )
            } else {
                log.warn(
                    "[easyp-cli] validate-config parsed valid=${parsed.valid} " +
                        "errors=${parsed.errors.size} warnings=${parsed.warnings.size}"
                )
            }
        }
    }

    private fun executeJsonCommand(
        subCommand: List<String>,
        timeoutMillis: Int,
        cfgOverridePath: String? = null,
        configFlagMode: ConfigFlagMode
    ): String? {
        val settings = EasypSettings.getInstance(project).state
        val exe = settings.easypCliPath?.takeIf { it.isNotBlank() } ?: "easyp"
        val baseDir = project.basePath?.let { File(it) }?.takeIf { it.exists() }
        val configPathForCommand = cfgOverridePath?.takeIf { it.isNotBlank() }
            ?: settings.configPath?.takeIf { it.isNotBlank() }

        val params = mutableListOf<String>()
        if (configFlagMode == ConfigFlagMode.GLOBAL) {
            configPathForCommand?.let { cfg ->
                params += listOf("--cfg", cfg)
            }
        }
        params += listOf("--format", "json")
        params += subCommand
        if (configFlagMode == ConfigFlagMode.SUBCOMMAND) {
            configPathForCommand?.let { cfg ->
                params += listOf("--config", cfg)
            }
        }

        val cmd = GeneralCommandLine(exe).withParameters(params)
        if (baseDir != null) cmd.withWorkDirectory(baseDir)

        log.warn(
            "[easyp-cli] call subCommand=${subCommand.first()} exe=$exe workDir=$baseDir cfg=$configPathForCommand " +
                "params=${params.joinToString(" ")}"
        )

        return try {
            val out = ExecUtil.execAndGetOutput(cmd, timeoutMillis)
            if (out.stdout.isBlank()) {
                log.warn(
                    "[easyp-cli] ${subCommand.first()} empty stdout exitCode=${out.exitCode} " +
                        "stderr=${out.stderr.trim().compactForLog(280)}"
                )
                null
            } else {
                log.warn(
                    "[easyp-cli] ${subCommand.first()} raw stdout=${out.stdout.compactForLog(220)} " +
                        "stderr=${out.stderr.compactForLog(160)} exitCode=${out.exitCode}"
                )
                out.stdout
            }
        } catch (t: Throwable) {
            log.warn("easyp ${subCommand.first()} error: ${t.message}", t)
            null
        }
    }

    private enum class ConfigFlagMode {
        GLOBAL,
        SUBCOMMAND
    }

    private fun String.compactForLog(limit: Int): String {
        return replace('\n', ' ').replace('\r', ' ').take(limit)
    }
}

/**
 * DTO для ответа команды `easyp ls-files`.
 */
data class LsFilesResponse(
    val files: List<LsFile> = emptyList(),
    val roots: List<LsRoot> = emptyList()
)

data class LsFile(
    val absPath: String,
    val importPath: String,
    val source: String,
    val root: String? = null
)

data class LsRoot(
    val path: String,
    val source: String
)

/**
 * DTO для ответа команды `easyp validate-config`.
 */
data class ValidateConfigResponse(
    val valid: Boolean = false,
    val errors: List<ValidationIssue> = emptyList(),
    val warnings: List<ValidationIssue> = emptyList()
)

data class ValidationIssue(
    val code: String? = null,
    val message: String,
    val severity: String = "", // "error", "warn", "warning", "info"
    val line: Int? = null,
    val column: Int? = null
)

internal object EasypCliJsonParser {
    private val mapper = ObjectMapper()

    fun parseLsFilesResponse(raw: String): LsFilesResponse? {
        return runCatching {
            val root = mapper.readTree(raw)
            val files = root.path("files")
                .takeIf { it.isArray }
                ?.mapNotNull(::parseLsFile)
                .orEmpty()
            val roots = root.path("roots")
                .takeIf { it.isArray }
                ?.mapNotNull(::parseLsRoot)
                .orEmpty()
            LsFilesResponse(files = files, roots = roots)
        }.getOrNull()
    }

    fun parseValidateConfigResponse(raw: String): ValidateConfigResponse? {
        return runCatching {
            val root = mapper.readTree(raw)
            val valid = root.path("valid").asBoolean(false)
            val errors = root.path("errors")
                .takeIf { it.isArray }
                ?.mapNotNull(::parseValidationIssue)
                .orEmpty()
            val warnings = root.path("warnings")
                .takeIf { it.isArray }
                ?.mapNotNull(::parseValidationIssue)
                .orEmpty()
            ValidateConfigResponse(
                valid = valid,
                errors = errors,
                warnings = warnings
            )
        }.getOrNull()
    }

    private fun parseLsFile(node: JsonNode): LsFile? {
        val absPath = node.stringOrNull("abs_path") ?: return null
        val importPath = node.stringOrNull("import_path") ?: return null
        val source = node.stringOrNull("source") ?: return null
        val root = node.stringOrNull("root")
        return LsFile(
            absPath = absPath,
            importPath = importPath,
            source = source,
            root = root
        )
    }

    private fun parseLsRoot(node: JsonNode): LsRoot? {
        val path = node.stringOrNull("path") ?: return null
        val source = node.stringOrNull("source") ?: return null
        return LsRoot(path = path, source = source)
    }

    private fun parseValidationIssue(node: JsonNode): ValidationIssue? {
        val message = node.stringOrNull("message") ?: return null
        return ValidationIssue(
            code = node.stringOrNull("code"),
            message = message,
            severity = node.stringOrNull("severity") ?: "",
            line = node.intOrNull("line"),
            column = node.intOrNull("column")
        )
    }

    private fun JsonNode.stringOrNull(field: String): String? {
        val value = get(field) ?: return null
        if (value.isNull) return null
        return value.asText().takeIf { it.isNotBlank() }
    }

    private fun JsonNode.intOrNull(field: String): Int? {
        val value = get(field) ?: return null
        if (value.isNull) return null
        if (value.isInt || value.isLong) {
            return value.asInt()
        }
        return value.asText().toIntOrNull()
    }
}
