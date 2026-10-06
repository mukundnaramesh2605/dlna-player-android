package com.dlnaplayer.android.repository

import android.os.Environment
import android.util.Log
import com.dlnaplayer.android.model.FileItem
import com.dlnaplayer.android.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

enum class SortBy {
    NAME,
    DATE,
    SIZE
}

enum class SortOrder {
    ASCENDING,
    DESCENDING
}

/**
 * Repository responsible for traversing the real device filesystem using Java File APIs
 * starting at Environment.getExternalStorageDirectory().
 */
class FileRepository {

    /**
     * Returns the root folder of the user's primary shared external storage (/storage/emulated/0).
     */
    fun getStorageRoot(): File {
        return Environment.getExternalStorageDirectory() ?: File("/storage/emulated/0")
    }

    /**
     * Lists files and directories in the specified folder with background IO dispatching,
     * sorting, and playable media filtering.
     */
    suspend fun listFolder(
        directory: File,
        searchQuery: String = "",
        onlyPlayableMedia: Boolean = false,
        sortBy: SortBy = SortBy.NAME,
        sortOrder: SortOrder = SortOrder.ASCENDING
    ): Result<List<FileItem>> = withContext(Dispatchers.IO) {
        try {
            if (!directory.exists() || !directory.isDirectory) {
                return@withContext Result.failure(IllegalArgumentException("Path is not a directory: ${directory.absolutePath}"))
            }

            val rawFiles = directory.listFiles() ?: emptyArray()

            // Map files to FileItems
            val items = ArrayList<FileItem>(rawFiles.size)
            for (file in rawFiles) {
                // Ignore hidden files starting with .
                if (file.name.startsWith(".")) continue

                val isDir = file.isDirectory
                val childCount = if (isDir) {
                    try {
                        file.list()?.count { !it.startsWith(".") } ?: 0
                    } catch (e: Exception) {
                        0
                    }
                } else {
                    0
                }

                val item = FileItem.fromFile(file, childCount = childCount)

                // Apply media filter if enabled
                if (onlyPlayableMedia && !item.isDirectory && !item.isCastable) {
                    continue
                }

                // Apply search filter if query is present
                if (searchQuery.isNotBlank() && !item.name.contains(searchQuery, ignoreCase = true)) {
                    continue
                }

                items.add(item)
            }

            // Separate directories and files: directories always grouped at top
            val directories = items.filter { it.isDirectory }
            val files = items.filter { !it.isDirectory }

            val sortedDirectories = sortItems(directories, sortBy, sortOrder)
            val sortedFiles = sortItems(files, sortBy, sortOrder)

            Result.success(sortedDirectories + sortedFiles)
        } catch (e: Exception) {
            Log.e(TAG, "Error listing directory ${directory.absolutePath}", e)
            Result.failure(e)
        }
    }

    private fun sortItems(
        list: List<FileItem>,
        sortBy: SortBy,
        sortOrder: SortOrder
    ): List<FileItem> {
        val comparator = when (sortBy) {
            SortBy.NAME -> compareBy<FileItem> { it.name.lowercase(Locale.ROOT) }
            SortBy.DATE -> compareBy<FileItem> { it.lastModified }
            SortBy.SIZE -> compareBy<FileItem> { it.size }
        }

        return if (sortOrder == SortOrder.ASCENDING) {
            list.sortedWith(comparator)
        } else {
            list.sortedWith(comparator.reversed())
        }
    }

    /**
     * Resolves the parent directory while not navigating above the external storage root.
     */
    fun getParentDirectory(currentDir: File): File? {
        val root = getStorageRoot()
        if (currentDir.absolutePath == root.absolutePath) {
            return null
        }
        val parent = currentDir.parentFile
        return if (parent != null && parent.canRead()) parent else null
    }

    /**
     * Builds breadcrumb hierarchy for quick path jump navigation.
     */
    fun buildBreadcrumbs(currentDir: File): List<File> {
        val root = getStorageRoot()
        val crumbs = mutableListOf<File>()
        var curr: File? = currentDir

        while (curr != null) {
            crumbs.add(0, curr)
            if (curr.absolutePath == root.absolutePath || curr.parentFile == null) {
                break
            }
            curr = curr.parentFile
        }

        return crumbs
    }

    companion object {
        private const val TAG = "FileRepository"
    }
}
