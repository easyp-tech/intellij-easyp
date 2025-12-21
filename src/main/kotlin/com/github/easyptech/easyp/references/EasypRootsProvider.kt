package com.github.easyptech.easyp.references

import com.github.easyptech.easyp.easypcli.EasypImportResolver
import com.github.easyptech.easyp.settings.EasypSettings
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.AdditionalLibraryRootsProvider
import com.intellij.openapi.roots.SyntheticLibrary
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager

/**
 * Провайдер дополнительных корней библиотек (AdditionalLibraryRootsProvider).
 *
 * **Роль в архитектуре:**
 * Этот класс сообщает IntelliJ IDEA о существовании внешних директорий с исходным кодом,
 * которые не являются частью структуры модулей проекта (например, скачанные зависимости в `~/.easyp/mod`).
 *
 * Без этого класса:
 * 1. IDE не будет индексировать содержимое внешних proto-файлов.
 * 2. "Go to definition" будет открывать файлы как "чужие" (без подсветки и навигации).
 * 3. Файловая система (VFS) не будет следить за изменениями в этих папках.
 */
class EasypRootsProvider : AdditionalLibraryRootsProvider() {

    /**
     * Возвращает список директорий, за которыми IDE должна следить (индексировать).
     *
     * Вызывается платформой при инициализации проекта и при обновлении корней.
     */
    override fun getRootsToWatch(project: Project): Collection<VirtualFile> {
        if (!EasypSettings.getInstance(project).state.enableProtoImportResolve) {
            return emptyList()
        }
        
        val resolver = EasypImportResolver.getInstance(project)
        val rootDirs = resolver.getImportRoots()
        val fs = VirtualFileManager.getInstance()
        
        // Преобразуем java.io.File в VirtualFile
        return rootDirs.mapNotNull { dir ->
            fs.findFileByNioPath(dir.toPath())
        }
    }

    /**
     * Создает "Синтетические библиотеки" для отображения в дереве проекта (External Libraries).
     *
     * Это позволяет пользователю видеть внешние зависимости в UI и искать по ним.
     */
    override fun getAdditionalProjectLibraries(project: Project): Collection<SyntheticLibrary> {
        if (!EasypSettings.getInstance(project).state.enableProtoImportResolve) {
            return emptyList()
        }
        
        val resolver = EasypImportResolver.getInstance(project)
        val rootDirs = resolver.getImportRoots()
        val fs = VirtualFileManager.getInstance()
        
        return rootDirs.mapNotNull { dir ->
            val rootFile = fs.findFileByNioPath(dir.toPath()) ?: return@mapNotNull null
            EasypProtoImportLibrary(rootFile.url, rootFile)
        }
    }
    
    /**
     * Простая реализация SyntheticLibrary, представляющая одну корневую папку с proto-файлами.
     */
    private class EasypProtoImportLibrary(
        private val url: String,
        private val root: VirtualFile
    ) : SyntheticLibrary() {
        override fun getSourceRoots(): Collection<VirtualFile> = listOf(root)
        
        // Важно реализовать equals/hashCode, чтобы IDE не пересоздавала библиотеки лишний раз
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is EasypProtoImportLibrary) return false
            return url == other.url
        }
        
        override fun hashCode(): Int {
            return url.hashCode()
        }
    }
}
