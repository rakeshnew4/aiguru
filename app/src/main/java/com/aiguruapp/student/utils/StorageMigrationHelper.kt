package com.aiguruapp.student.utils

import android.content.Context
import android.util.Log
import com.aiguruapp.student.services.StorageService
import java.io.File
import java.io.IOException

/**
 * Handles migration from internal app storage to persistent external storage.
 * Call this once during app initialization to recover data from previous installs
 * or migrate from old storage location.
 */
object StorageMigrationHelper {
    private const val TAG = "StorageMigrationHelper"

    /**
     * Initialize storage and check for data to recover.
     * Call this in Application.onCreate() or SplashActivity.onCreate()
     */
    fun initializeStorageAndRecover(context: Context): Boolean {
        return try {
            // Step 1: Initialize external storage directories
            if (!StorageService.initialize(context)) {
                Log.w(TAG, "Failed to initialize external storage")
                return false
            }

            // Step 2: Check if external storage is available
            if (!StorageService.isStorageAvailable()) {
                Log.w(TAG, "External storage not available")
                return false
            }

            // Step 3: Migrate data from internal cache (if exists)
            migrateFromInternalCache(context)

            // Step 4: Recover data from previous app installation
            StorageService.checkAndRecoverPreviousData(context)

            Log.d(TAG, "Storage initialized and recovery completed")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Storage initialization error: ${e.message}", e)
            false
        }
    }

    /**
     * Migrate files from app internal cache to persistent external storage.
     * This handles upgrades from older app versions.
     */
    private fun migrateFromInternalCache(context: Context) {
        try {
            val cacheDir = context.cacheDir
            if (!cacheDir.exists()) return

            // Look for known cache subdirectories
            val sourceDirs = listOf(
                File(cacheDir, "pdfs"),
                File(cacheDir, "images"),
                File(cacheDir, "audio"),
                File(cacheDir, "tts_audio"),
                File(cacheDir, "downloads")
            )

            sourceDirs.forEach { sourceDir ->
                if (sourceDir.exists() && sourceDir.isDirectory) {
                    when {
                        sourceDir.name.contains("pdf", ignoreCase = true) -> {
                            migratePdfs(sourceDir)
                        }
                        sourceDir.name.contains("image", ignoreCase = true) -> {
                            migrateImages(sourceDir)
                        }
                        sourceDir.name.contains("audio", ignoreCase = true) ||
                        sourceDir.name.contains("tts", ignoreCase = true) -> {
                            migrateAudio(sourceDir)
                        }
                    }
                }
            }

            Log.d(TAG, "Migration from internal cache completed")
        } catch (e: Exception) {
            Log.e(TAG, "Migration error: ${e.message}", e)
        }
    }

    private fun migratePdfs(sourceDir: File) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension == "pdf") {
                try {
                    val destFile = StorageService.getPdfFile(file.name)
                    if (destFile != null && !destFile.exists()) {
                        file.copyTo(destFile, overwrite = false)
                        Log.d(TAG, "Migrated PDF: ${file.name}")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to migrate PDF ${file.name}: ${e.message}")
                }
            }
        }
    }

    private fun migrateImages(sourceDir: File) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension in listOf("jpg", "jpeg", "png", "gif", "webp")) {
                try {
                    val destFile = StorageService.getImageFile(file.name)
                    if (destFile != null && !destFile.exists()) {
                        file.copyTo(destFile, overwrite = false)
                        Log.d(TAG, "Migrated image: ${file.name}")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to migrate image ${file.name}: ${e.message}")
                }
            }
        }
    }

    private fun migrateAudio(sourceDir: File) {
        sourceDir.listFiles()?.forEach { file ->
            if (file.isFile && file.extension in listOf("mp3", "wav", "aac", "ogg")) {
                try {
                    val destFile = StorageService.getTtsAudioFile(file.name)
                    if (destFile != null && !destFile.exists()) {
                        file.copyTo(destFile, overwrite = false)
                        Log.d(TAG, "Migrated audio: ${file.name}")
                    }
                } catch (e: IOException) {
                    Log.e(TAG, "Failed to migrate audio ${file.name}: ${e.message}")
                }
            }
        }
    }

    /**
     * Get a summary of recovered/migrated data.
     */
    fun getRecoverySummary(): String {
        val storageInfo = StorageService.getStorageInfo()
        return """
Storage Recovery Summary:
- Total Storage: ${String.format("%.2f", storageInfo.totalSizeMB)} MB
- PDFs: ${String.format("%.2f", storageInfo.pdfSizeMB)} MB (${StorageService.getAllPdfs().size} files)
- Images: ${String.format("%.2f", storageInfo.imageSizeMB)} MB (${StorageService.getAllImages().size} files)
- Audio: ${String.format("%.2f", storageInfo.audioSizeMB)} MB (${StorageService.getAllTtsAudio().size} files)
- Cache: ${String.format("%.2f", storageInfo.cacheSizeMB)} MB
Storage Path: ${StorageService.getStoragePath()}
        """.trimIndent()
    }
}
