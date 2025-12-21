package com.github.easyptech.easyp.easypcli

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Центральный сервис проекта для управления данными об импортах.
 *
 * **Роль в архитектуре:**
 * 1. Выступает посредником между CLI (EasypCli) и компонентами IDE (провайдерами).
 * 2. Хранит в памяти (кэширует) карту соответствия `import_path -> File`.
 * 3. Управляет обновлением VFS (Virtual File System), чтобы IDE "увидела" внешние файлы.
 *
 * Этот сервис должен быть легковесным для чтения (resolveImport), так как вызывается часто
 * при анализе кода. Обновление (refreshCache) происходит редко и асинхронно.
 */
@Service(Service.Level.PROJECT)
class EasypImportResolver(private val project: Project) {
    private val log = Logger.getInstance(EasypImportResolver::class.java)
    
    // Кэшированные данные
    private var cachedResponse: LsFilesResponse? = null
    
    // Быстрый индекс для поиска файла по import "..."
    private var importPathMap: Map<String, File> = emptyMap()
    
    // Индекс файлов проекта для поиска по имени (навигация)
    private var workspaceFiles: Map<String, File> = emptyMap()
    
    // Защита от одновременных запусков обновления
    private val refreshInProgress = AtomicBoolean(false)
    private var lastRefreshMillis: Long = 0L

    /**
     * Запускает процесс обновления данных из `easyp`.
     *
     * **Что делает:**
     * 1. Вызывает `easyp ls-files`.
     * 2. Если успешно — перестраивает внутренние карты (индексы).
     * 3. Принудительно обновляет VFS для всех найденных корней. Это критически важно,
     *    иначе IDE будет считать, что файлов по этим путям не существует, даже если они есть на диске.
     *
     * @return `true`, если обновление прошло успешно.
     */
    fun refreshCache(): Boolean {
        return try {
            val response = EasypCli(project).lsFiles()
            if (response != null) {
                cachedResponse = response
                buildImportPathMap(response)
                buildWorkspaceFilesMap(response)
                // Принудительно освежаем VFS для корневых директорий, чтобы ускорить индексацию
                refreshVfsForRoots(response)
                lastRefreshMillis = System.currentTimeMillis()
                log.info("Easyp cache refreshed: ${importPathMap.size} imports, ${workspaceFiles.size} workspace files")
                true
            } else {
                log.warn("Failed to get response from easyp-cli")
                false
            }
        } catch (t: Throwable) {
            log.warn("Error refreshing easyp import resolver cache: ${t.message}", t)
            false
        }
    }

    /**
     * Обновляет состояние Virtual File System для внешних корней.
     *
     * IDE не следит автоматически за файлами вне проекта. Этот метод говорит IDE:
     * "Посмотри в эти папки, там могли появиться новые файлы".
     */
    private fun refreshVfsForRoots(response: LsFilesResponse) {
        try {
            val roots = mutableSetOf<File>()
            
            response.roots.forEach { rootItem ->
                // Пропускаем wellknown, так как они обрабатываются встроенным плагином
                if (rootItem.source == "wellknown") return@forEach
                
                val dir = File(rootItem.path)
                if (dir.exists() && dir.isDirectory) roots += dir
            }
            
            val lfs = LocalFileSystem.getInstance()
            roots.forEach { dir ->
                lfs.refreshAndFindFileByIoFile(dir)?.let { vDir ->
                    VfsUtil.markDirtyAndRefresh(false, true, true, vDir)
                }
            }
            log.info("Refreshed VFS for ${roots.size} roots")
        } catch (t: Throwable) {
            log.warn("Failed to refresh VFS for roots: ${t.message}", t)
        }
    }

    /**
     * Строит индекс `import "foo/bar.proto" -> /abs/path/to/foo/bar.proto`.
     */
    private fun buildImportPathMap(response: LsFilesResponse) {
        val map = mutableMapOf<String, File>()
        
        response.files.forEach { fileItem ->
            // Пропускаем wellknown, так как они обрабатываются встроенным плагином
            if (fileItem.source == "wellknown") return@forEach
            
            val importPath = normalizeImportPath(fileItem.importPath)
            val file = File(fileItem.absPath)
            
            if (file.exists() && !map.containsKey(importPath)) {
                map[importPath] = file
            }
        }
        
        importPathMap = map
        log.info("Built import path map with ${map.size} entries")
    }

    /**
     * Строит индекс файлов рабочего пространства для навигации по имени файла.
     */
    private fun buildWorkspaceFilesMap(response: LsFilesResponse) {
        val map = mutableMapOf<String, File>()
        
        response.files.filter { it.source == "workspace" }.forEach { fileItem ->
            val file = File(fileItem.absPath)
            if (file.exists()) {
                // Добавляем по имени файла
                if (!map.containsKey(file.name)) {
                    map[file.name] = file
                }
                // Также можно добавить по import_path, если он отличается от имени
                val importPath = normalizeImportPath(fileItem.importPath)
                if (importPath != file.name && !map.containsKey(importPath)) {
                    map[importPath] = file
                }
            }
        }
        
        workspaceFiles = map
    }

    /**
     * Убирает кавычки и лишние пробелы из строки импорта.
     */
    private fun normalizeImportPath(importPath: String): String {
        return importPath.trim().trim('"', '\'')
    }

    /**
     * Основной метод разрешения импорта.
     *
     * **Использование:**
     * Вызывается из `EasypPbFileResolveProvider` когда IDE встречает строку `import "..."`.
     *
     * **Алгоритм:**
     * 1. Ищет точное совпадение в `importPathMap`.
     * 2. Если не найдено — пытается найти частичное совпадение (по суффиксу).
     * 3. Если и это не помогло — ищет просто по имени файла (для workspace файлов).
     *
     * @param importPath Строка из директивы import (например, "google/type/money.proto").
     * @return Объект File на диске или null.
     */
    fun resolveImport(importPath: String): File? {
        val normalized = normalizeImportPath(importPath)
        
        // НЕ вызываем refreshCache() здесь синхронно, так как это может быть вызвано из ReadAction (UI thread)
        
        log.debug("Resolve import: '$normalized', cache size=${importPathMap.size}")
        
        // 1. Точное совпадение (самый частый кейс)
        val cached = importPathMap[normalized]
        if (cached != null && cached.exists()) {
            return cached
        }
        
        // 2. Поиск по суффиксу (если импорт относительный, а в базе полный путь)
        val fileName = normalized.substringAfterLast('/')
        if (fileName != normalized) {
            importPathMap.forEach { (key, file) ->
                if (key.endsWith(normalized) || normalized.endsWith(key)) {
                    if (file.exists()) return file
                }
            }
        } else {
            // 3. Поиск только по имени файла (fallback)
            workspaceFiles[fileName]?.takeIf { it.exists() }?.let { return it }
            
            importPathMap.forEach { (key, file) ->
                if (key.endsWith("/$fileName") || key == fileName) {
                    if (file.exists()) return file
                }
            }
        }
        
        return null
    }

    /**
     * Возвращает список всех корневых директорий, где лежат proto-файлы.
     *
     * **Использование:**
     * Вызывается провайдерами (`EasypRootsProvider`, `EasypPbFileResolveProvider`) для:
     * - Добавления этих папок в "Библиотеки" (External Libraries).
     * - Настройки области поиска (Search Scope).
     *
     * Если кэш пуст, запускает асинхронное обновление.
     */
    fun getImportRoots(): Set<File> {
        // Ленивая инициализация: если данных нет, запускаем фоновое обновление
        if (cachedResponse == null && refreshInProgress.compareAndSet(false, true)) {
            log.info("Easyp cache empty, scheduling async refresh")
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    refreshCache()
                } finally {
                    refreshInProgress.set(false)
                }
            }
        }
        
        val roots = mutableSetOf<File>()
        
        cachedResponse?.let { response ->
            response.roots.forEach { rootItem ->
                if (rootItem.source == "wellknown") return@forEach
                
                val dir = File(rootItem.path)
                if (dir.exists() && dir.isDirectory) {
                    roots.add(dir)
                }
            }
        }
        
        return roots
    }

    fun getLastRefreshMillis(): Long = lastRefreshMillis

    companion object {
        fun getInstance(project: Project): EasypImportResolver = project.service()
    }
}
