package com.aiguruapp.student.services

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Manages persistent file storage across app reinstalls and updates.
 * Stores data to external storage (shared across app lifecycle).
 *
 * Directory Structure:
 * /storage/emulated/0/AI Guru/
 *   ├── pdfs/
 *   ├── images/
 *   ├── audio/
 *   │   └── tts/
 *   ├── cache/
 *   └── metadata/
 *
 * This data survives:
 * - App uninstall/reinstall
 * - App updates
 * - Device restarts
 */
object StorageService {
    private const val TAG = "StorageService"
    private const val APP_FOLDER_NAME = "AI Guru"
    private const val SUBFOLDER_PDF = "pdfs"
    private const val SUBFOLDER_IMAGES = "images"
    private const val SUBFOLDER_AUDIO = "audio"
    private const val SUBFOLDER_TTS = "tts"
    private const val SUBFOLDER_CACHE = "cache"
    private const val SUBFOLDER_METADATA = "metadata"

    private var appStorageDir: File? = null

    /**
     * Initialize storage directories. Call this in Application.onCreate() or early in app lifecycle.
     */
    fun initialize(context: Context): Boolean {
        return try {
            // Get external storage directory (NOT app-specific, so it survives uninstall)
            val externalDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                APP_FOLDER_NAME
            )

            // Create directories if they don't exist
            if (!externalDir.exists()) {
                val created = externalDir.mkdirs()
                if (created) {
                    Log.d(TAG, "Created app storage directory: ${externalDir.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create app storage directory")
                    return false
                }
            }

            appStorageDir = externalDir

            // Create subdirectories
            createSubdirectory(SUBFOLDER_PDF)
            createSubdirectory(SUBFOLDER_IMAGES)
            createSubdirectory(SUBFOLDER_AUDIO)
            createSubdirectory("$SUBFOLDER_AUDIO/$SUBFOLDER_TTS")
            createSubdirectory(SUBFOLDER_CACHE)
            createSubdirectory(SUBFOLDER_METADATA)

            Log.d(TAG, "Storage initialized successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize storage: ${e.message}", e)
            false
        }
    }

    /**
     * Check if external storage is available for writing.
     */
    fun isStorageAvailable(): Boolean {
        return Environment.getExternalStorageState() == Environment.MEDIA_MOUNTED &&
                appStorageDir?.canWrite() == true
    }

    /**
     * Get or create a PDF file.
     * @param fileName PDF file name (e.g., "chapter_1.pdf")
     * @return File object, creates if doesn't exist
     */
    fun getPdfFile(fileName: String): File? {
        return getFileInSubdirectory(SUBFOLDER_PDF, fileName)
    }

    /**
     * Get or create an image file.
     * @param fileName Image file name (e.g., "lesson_image_001.jpg")
     * @return File object, creates if doesn't exist
     */
    fun getImageFile(fileName: String): File? {
        return getFileInSubdirectory(SUBFOLDER_IMAGES, fileName)
    }

    /**
     * Get or create a TTS audio file.
     * @param fileName Audio file name (e.g., "q_1_answer.mp3")
     * @return File object, creates if doesn't exist
     */
    fun getTtsAudioFile(fileName: String): File? {
        return getFileInSubdirectory("$SUBFOLDER_AUDIO/$SUBFOLDER_TTS", fileName)
    }

    /**
     * Get or create a cache file.
     * @param fileName Cache file name
     * @return File object, creates if doesn't exist
     */
    fun getCacheFile(fileName: String): File? {
        return getFileInSubdirectory(SUBFOLDER_CACHE, fileName)
    }

    /**
     * Get or create a metadata file (JSON config, etc).
     * @param fileName Metadata file name
     * @return File object, creates if doesn't exist
     */
    fun getMetadataFile(fileName: String): File? {
        return getFileInSubdirectory(SUBFOLDER_METADATA, fileName)
    }

    /**
     * Get all files of a specific type.
     * @param subFolder Subdirectory name (e.g., "pdfs", "images")
     * @return List of files in that directory
     */
    fun getFilesInFolder(subFolder: String): List<File> {
        val folder = File(appStorageDir, subFolder)
        return if (folder.exists() && folder.isDirectory) {
            folder.listFiles()?.toList() ?: emptyList()
        } else {
            emptyList()
        }
    }

    /**
     * Get all PDFs.
     */
    fun getAllPdfs(): List<File> {
        return getFilesInFolder(SUBFOLDER_PDF)
    }

    /**
     * Get all images.
     */
    fun getAllImages(): List<File> {
        return getFilesInFolder(SUBFOLDER_IMAGES)
    }

    /**
     * Get all TTS audio files.
     */
    fun getAllTtsAudio(): List<File> {
        return getFilesInFolder("$SUBFOLDER_AUDIO/$SUBFOLDER_TTS")
    }

    /**
     * Delete a file from persistent storage.
     */
    fun deleteFile(file: File): Boolean {
        return try {
            file.delete().also {
                if (it) Log.d(TAG, "Deleted: ${file.absolutePath}")
                else Log.w(TAG, "Failed to delete: ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting file: ${e.message}", e)
            false
        }
    }

    /**
     * Clear a specific folder (e.g., clear all cache files).
     * @param subFolder Subdirectory name
     */
    fun clearFolder(subFolder: String): Boolean {
        return try {
            val folder = File(appStorageDir, subFolder)
            if (folder.exists() && folder.isDirectory) {
                folder.listFiles()?.forEach { file ->
                    if (file.isFile) file.delete()
                }
                Log.d(TAG, "Cleared folder: $subFolder")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing folder: ${e.message}", e)
            false
        }
    }

    /**
     * Get total size of all stored data (in MB).
     */
    fun getTotalStorageSize(): Double {
        return try {
            val totalBytes = appStorageDir?.walkBottomUp()
                ?.filter { it.isFile }
                ?.map { it.length() }
                ?.sum() ?: 0L
            totalBytes / (1024.0 * 1024.0)
        } catch (e: Exception) {
            Log.e(TAG, "Error calculating storage size: ${e.message}", e)
            0.0
        }
    }

    /**
     * Get storage info with breakdown by category.
     */
    fun getStorageInfo(): StorageInfo {
        return try {
            val pdfSize = calculateFolderSize(SUBFOLDER_PDF)
            val imageSize = calculateFolderSize(SUBFOLDER_IMAGES)
            val audioSize = calculateFolderSize("$SUBFOLDER_AUDIO/$SUBFOLDER_TTS")
            val cacheSize = calculateFolderSize(SUBFOLDER_CACHE)
            val total = pdfSize + imageSize + audioSize + cacheSize

            StorageInfo(
                totalSizeMB = total / (1024.0 * 1024.0),
                pdfSizeMB = pdfSize / (1024.0 * 1024.0),
                imageSizeMB = imageSize / (1024.0 * 1024.0),
                audioSizeMB = audioSize / (1024.0 * 1024.0),
                cacheSizeMB = cacheSize / (1024.0 * 1024.0)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error getting storage info: ${e.message}", e)
            StorageInfo(0.0, 0.0, 0.0, 0.0, 0.0)
        }
    }

    /**
     * Get root storage directory path.
     */
    fun getStoragePath(): String? = appStorageDir?.absolutePath

    /**
     * Export all data to a backup file (JSON manifest + directory listing).
     * Useful for manual backups or migration.
     */
    fun createBackupManifest(): String {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val pdfCount = getAllPdfs().size
        val imageCount = getAllImages().size
        val audioCount = getAllTtsAudio().size
        val storageInfo = getStorageInfo()

        return """{
  "backup_timestamp": "$timestamp",
  "app_folder": "${appStorageDir?.absolutePath}",
  "files": {
    "pdfs": $pdfCount,
    "images": $imageCount,
    "audio": $audioCount
  },
  "storage": {
    "total_mb": ${String.format("%.2f", storageInfo.totalSizeMB)},
    "pdf_mb": ${String.format("%.2f", storageInfo.pdfSizeMB)},
    "image_mb": ${String.format("%.2f", storageInfo.imageSizeMB)},
    "audio_mb": ${String.format("%.2f", storageInfo.audioSizeMB)},
    "cache_mb": ${String.format("%.2f", storageInfo.cacheSizeMB)}
  }
}"""
    }

    /**
     * Recover data from previous app installation if available.
     * Checks if app folder exists from previous install.
     */
    fun checkAndRecoverPreviousData(context: Context): Boolean {
        return try {
            val externalDir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
                APP_FOLDER_NAME
            )
            if (externalDir.exists() && externalDir.listFiles()?.isNotEmpty() == true) {
                Log.d(TAG, "Found previous app data, recovering...")
                appStorageDir = externalDir
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking previous data: ${e.message}", e)
            false
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private fun createSubdirectory(subFolderName: String): Boolean {
        return try {
            val subDir = File(appStorageDir, subFolderName)
            if (!subDir.exists()) {
                subDir.mkdirs()
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create subdirectory: $subFolderName", e)
            false
        }
    }

    private fun getFileInSubdirectory(subFolder: String, fileName: String): File? {
        return try {
            val subfolder = File(appStorageDir, subFolder)
            if (!subfolder.exists()) {
                subfolder.mkdirs()
            }
            val file = File(subfolder, fileName)
            if (!file.exists()) {
                file.createNewFile()
            }
            file
        } catch (e: IOException) {
            Log.e(TAG, "Error creating file: $fileName in $subFolder", e)
            null
        }
    }

    private fun calculateFolderSize(folderPath: String): Long {
        return try {
            val folder = File(appStorageDir, folderPath)
            if (folder.exists() && folder.isDirectory) {
                folder.walkBottomUp()
                    .filter { it.isFile }
                    .map { it.length() }
                    .sum()
            } else {
                0L
            }
        } catch (e: Exception) {
            0L
        }
    }

    data class StorageInfo(
        val totalSizeMB: Double,
        val pdfSizeMB: Double,
        val imageSizeMB: Double,
        val audioSizeMB: Double,
        val cacheSizeMB: Double
    )
}
