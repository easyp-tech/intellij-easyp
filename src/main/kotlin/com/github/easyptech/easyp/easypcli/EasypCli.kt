package com.github.easyptech.easyp.easypcli

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
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
    private val mapper = jacksonObjectMapper()

    /**
     * Выполняет команду `easyp ls-files --format json`.
     *
     * **Как это работает:**
     * 1. Считывает путь к исполняемому файлу и конфигу из настроек плагина.
     * 2. Формирует командную строку, устанавливая рабочую директорию в корень проекта.
     * 3. Запускает процесс и ожидает завершения (с таймаутом).
     * 4. Парсит JSON из stdout в структуру [LsFilesResponse].
     *
     * @param timeoutMillis Максимальное время ожидания выполнения команды (по умолчанию 3 сек).
     * @return Объект с данными о файлах и корнях или `null`, если произошла ошибка запуска/парсинга.
     */
    fun lsFiles(timeoutMillis: Int = 3000): LsFilesResponse? {
        val settings = EasypSettings.getInstance(project).state
        // Если путь не указан — используем просто 'easyp' и пускаем через PATH
        val exe = settings.easypCliPath?.takeIf { it.isNotBlank() } ?: "easyp"

        // Запускаем из корня проекта, чтобы easyp сам подхватил easyp.yaml
        val baseDir = project.basePath?.let { File(it) }
        // Глобальные флаги должны идти ПЕРЕД подкомандой в easyp
        val params = mutableListOf<String>()
        settings.configPath?.takeIf { it.isNotBlank() }?.let { cfg ->
            params += listOf("--cfg", cfg)
        }
        // Используем json формат (он же по умолчанию)
        params += listOf("ls-files", "--format", "json")
        val cmd = GeneralCommandLine(exe).withParameters(params)

        if (baseDir != null) cmd.withWorkDirectory(baseDir)

        log.info("Calling easyp ls-files with executable: $exe, workDir: $baseDir, cfg=${settings.configPath}")

        return try {
            val out = ExecUtil.execAndGetOutput(cmd, timeoutMillis)
            if (out.exitCode != 0) {
                log.warn("easyp ls-files failed: exitCode=${out.exitCode}, stderr=${out.stderr.trim()}")
                null
            } else {
                val response = mapper.readValue<LsFilesResponse>(out.stdout)
                log.info("easyp ls-files returned: files=${response.files.size}, roots=${response.roots.size}")
                response
            }
        } catch (t: Throwable) {
            log.warn("easyp ls-files error: ${t.message}", t)
            null
        }
    }
}

/**
 * DTO для ответа команды `easyp ls-files`.
 * Содержит списки всех доступных proto-файлов и корневых директорий импорта.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LsFilesResponse(
    val files: List<LsFile> = emptyList(),
    val roots: List<LsRoot> = emptyList()
)

/**
 * Описание отдельного файла.
 *
 * @property absPath Абсолютный путь к файлу на диске.
 * @property importPath Путь, по которому этот файл импортируется в .proto (например, "google/api/http.proto").
 * @property source Источник файла ("workspace", "dependency", "wellknown").
 * @property root Логический корень (опционально).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LsFile(
    @param:JsonProperty("abs_path") val absPath: String,
    @param:JsonProperty("import_path") val importPath: String,
    val source: String,
    val root: String? = null
)

/**
 * Описание корневой директории импорта.
 *
 * @property path Абсолютный путь к директории.
 * @property source Тип источника ("dependency", "workspace", "wellknown").
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class LsRoot(
    val path: String,
    val source: String
)
