package com.github.easyptech.easyp.references

import com.github.easyptech.easyp.easypcli.EasypImportResolver
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.protobuf.lang.resolve.FileResolveProvider
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.GlobalSearchScopesCore
import java.io.File

/**
 * Реализация интерфейса `FileResolveProvider` из официального плагина Protobuf.
 *
 * **Роль в архитектуре:**
 * Это точка расширения (Extension Point), через которую наш плагин встраивается в процесс
 * разрешения импортов внутри .proto файлов.
 *
 * Когда IDE видит строку `import "google/api/annotations.proto";`, она опрашивает всех
 * зарегистрированных провайдеров. Наш провайдер использует данные от `easyp`, чтобы найти файл.
 */
class EasypPbFileResolveProvider : FileResolveProvider {
    private val log = Logger.getInstance(EasypPbFileResolveProvider::class.java)

    /**
     * Находит файл по пути импорта.
     *
     * @param path Строка импорта (например, "google/protobuf/timestamp.proto").
     * @return VirtualFile найденного файла или null.
     */
    override fun findFile(path: String, project: Project): VirtualFile? {
        val resolver = EasypImportResolver.getInstance(project)
        val normalized = path.trim('"', '\'', ' ')
        
        // Делегируем поиск нашему резолверу
        val file = resolver.resolveImport(normalized) ?: return null
        
        // Преобразуем IO File в VirtualFile (нужен для IDE)
        val vFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        
        if (vFile == null) log.debug("findFile: Vf is null for ${file.absolutePath}")
        return vFile
    }

    /**
     * Возвращает список возможных продолжений пути (для автодополнения).
     *
     * Например, если пользователь написал `import "google/api/`, этот метод должен вернуть
     * список файлов и папок внутри `google/api` во всех доступных корнях.
     */
    override fun getChildEntries(path: String, project: Project): Collection<FileResolveProvider.ChildEntry> {
        val resolver = EasypImportResolver.getInstance(project)
        val normalized = path.trim().trim('/').replace('\\', '/')
        val roots = resolver.getImportRoots()
        val names = LinkedHashSet<FileResolveProvider.ChildEntry>()

        // Пробегаем по всем корням и ищем подпапки/файлы, соответствующие пути
        for (root in roots) {
            val base = if (normalized.isEmpty()) root else File(root, normalized)
            if (!base.exists() || !base.isDirectory) continue
            val children = base.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    names.add(FileResolveProvider.ChildEntry.directory(child.name))
                } else if (child.isFile && child.name.endsWith(".proto", ignoreCase = true)) {
                    names.add(FileResolveProvider.ChildEntry.file(child.name))
                }
            }
        }
        return names
    }

    /**
     * Возвращает файл дескриптора (обычно descriptor.proto).
     * Мы возвращаем null, так как используем стандартный механизм или другие провайдеры.
     */
    override fun getDescriptorFile(project: Project): VirtualFile? {
        return null
    }

    /**
     * Определяет область поиска (Scope) для ссылок.
     *
     * Это важно для производительности и корректности "Find Usages".
     * Мы включаем в область поиска все директории, которые вернул `easyp ls-files`.
     */
    override fun getSearchScope(project: Project): GlobalSearchScope {
        val resolver = EasypImportResolver.getInstance(project)
        val roots = resolver.getImportRoots()
        val lfs = LocalFileSystem.getInstance()
        var scope: GlobalSearchScope? = null
        
        // Объединяем все корни в один GlobalSearchScope
        for (root in roots) {
            val vDir = lfs.refreshAndFindFileByIoFile(root) ?: continue
            val dirScope = GlobalSearchScopesCore.directoryScope(project, vDir, true)
            scope = scope?.union(dirScope) ?: dirScope
        }
        return scope ?: GlobalSearchScope.EMPTY_SCOPE
    }
}
