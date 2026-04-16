package com.aiguruapp.student.services

import android.content.Context
import android.util.Log
import com.google.firebase.storage.FirebaseStorage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Optional cloud backup service using Firebase Storage.
 * Backs up and restores PDFs, images, and TTS audio files.
 *
 * Usage:
 * - Call backup() periodically (e.g., after quiz completion, on app update)
 * - Call restore() when app is reinstalled
 */
class CloudBackupService {
    companion object {
        private const val TAG = "CloudBackupService"
        private const val BUCKET_PATH = "user_data_backups"
    }

    private val firebaseStorage = FirebaseStorage.getInstance()

    /**
     * Backup all persistent data to Firebase Storage.
     * Called automatically after app updates or manual sync.
     */
    fun backupAllData(
        context: Context,
        userId: String,
        onProgress: (message: String) -> Unit = {},
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            onProgress("Starting backup...")

            val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
            val backupFolder = "$BUCKET_PATH/$userId/$timestamp"

            // Get all files to backup
            val pdfFiles = StorageService.getAllPdfs()
            val imageFiles = StorageService.getAllImages()
            val audioFiles = StorageService.getAllTtsAudio()

            val allFiles = pdfFiles + imageFiles + audioFiles
            var uploadedCount = 0
            val totalCount = allFiles.size

            if (totalCount == 0) {
                onProgress("No files to backup")
                onSuccess()
                return
            }

            // Upload files
            allFiles.forEach { file ->
                val category = when {
                    pdfFiles.contains(file) -> "pdfs"
                    imageFiles.contains(file) -> "images"
                    audioFiles.contains(file) -> "audio"
                    else -> "other"
                }

                val remotePath = "$backupFolder/$category/${file.name}"
                uploadFile(file, remotePath,
                    onSuccess = {
                        uploadedCount++
                        onProgress("Uploading... ($uploadedCount/$totalCount)")
                        if (uploadedCount == totalCount) {
                            Log.d(TAG, "Backup completed: $uploadedCount files uploaded")
                            onSuccess()
                        }
                    },
                    onError = { exception ->
                        Log.e(TAG, "Failed to upload ${file.name}: ${exception.message}")
                        onError(exception)
                    }
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Backup error: ${e.message}", e)
            onError(e)
        }
    }

    /**
     * Restore data from Firebase Storage backup.
     * Called when app is reinstalled or on manual restore.
     */
    fun restoreLatestBackup(
        userId: String,
        onProgress: (message: String) -> Unit = {},
        onSuccess: (fileCount: Int) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            onProgress("Finding latest backup...")

            val backupRef = firebaseStorage.reference.child("$BUCKET_PATH/$userId")

            backupRef.listAll().addOnSuccessListener { result ->
                if (result.prefixes.isEmpty()) {
                    onProgress("No backup found")
                    onSuccess(0)
                    return@addOnSuccessListener
                }

                // Get the latest backup folder (sorted by name)
                val latestBackup = result.prefixes.sortedByDescending { it.name }.firstOrNull()
                if (latestBackup != null) {
                    restoreFromFolder(latestBackup, onProgress, onSuccess, onError)
                } else {
                    onError(Exception("No backup folders found"))
                }
            }.addOnFailureListener { exception ->
                Log.e(TAG, "Failed to list backups: ${exception.message}")
                onError(exception)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore error: ${e.message}", e)
            onError(e)
        }
    }

    /**
     * Delete old backups (keep only recent ones).
     * Call this periodically to clean up Firebase Storage.
     */
    fun deleteOldBackups(
        userId: String,
        keepLastN: Int = 3,
        onSuccess: (deletedCount: Int) -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            val backupRef = firebaseStorage.reference.child("$BUCKET_PATH/$userId")

            backupRef.listAll().addOnSuccessListener { result ->
                val sortedBackups = result.prefixes.sortedByDescending { it.name }
                val backupsToDelete = sortedBackups.drop(keepLastN)
                var deletedCount = 0

                backupsToDelete.forEach { backup ->
                    backup.listAll().addOnSuccessListener { files ->
                        files.items.forEach { file ->
                            file.delete().addOnSuccessListener {
                                deletedCount++
                                Log.d(TAG, "Deleted old backup file: ${file.path}")
                            }
                        }
                    }
                }

                onSuccess(deletedCount)
            }.addOnFailureListener { exception ->
                Log.e(TAG, "Failed to delete old backups: ${exception.message}")
                onError(exception)
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    // ==================== PRIVATE HELPERS ====================

    private fun uploadFile(
        file: File,
        remotePath: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            val fileRef = firebaseStorage.reference.child(remotePath)
            fileRef.putFile(android.net.Uri.fromFile(file))
                .addOnSuccessListener {
                    Log.d(TAG, "Uploaded: ${file.name}")
                    onSuccess()
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "Failed to upload ${file.name}: ${exception.message}")
                    onError(exception)
                }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun restoreFromFolder(
        folderRef: com.google.firebase.storage.StorageReference,
        onProgress: (message: String) -> Unit,
        onSuccess: (fileCount: Int) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            folderRef.listAll().addOnSuccessListener { result ->
                val allFiles = result.items
                var downloadedCount = 0
                val totalCount = allFiles.size

                if (totalCount == 0) {
                    onSuccess(0)
                    return@addOnSuccessListener
                }

                allFiles.forEach { fileRef ->
                    val category = fileRef.parent?.name ?: "other"
                    val localFile = when (category) {
                        "pdfs" -> StorageService.getPdfFile(fileRef.name)
                        "images" -> StorageService.getImageFile(fileRef.name)
                        "audio" -> StorageService.getTtsAudioFile(fileRef.name)
                        else -> StorageService.getCacheFile(fileRef.name)
                    }

                    if (localFile != null) {
                        downloadFile(fileRef, localFile,
                            onSuccess = {
                                downloadedCount++
                                onProgress("Restoring... ($downloadedCount/$totalCount)")
                                if (downloadedCount == totalCount) {
                                    Log.d(TAG, "Restore completed: $downloadedCount files")
                                    onSuccess(downloadedCount)
                                }
                            },
                            onError = onError
                        )
                    }
                }
            }.addOnFailureListener { exception ->
                onError(exception)
            }
        } catch (e: Exception) {
            onError(e)
        }
    }

    private fun downloadFile(
        sourceRef: com.google.firebase.storage.StorageReference,
        destFile: File,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            sourceRef.getBytes(Long.MAX_VALUE).addOnSuccessListener { bytes ->
                destFile.writeBytes(bytes)
                Log.d(TAG, "Downloaded: ${sourceRef.name}")
                onSuccess()
            }.addOnFailureListener { exception ->
                Log.e(TAG, "Failed to download ${sourceRef.name}: ${exception.message}")
                onError(exception)
            }
        } catch (e: Exception) {
            onError(e)
        }
    }
}
