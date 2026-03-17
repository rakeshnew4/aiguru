package com.example.aiguru.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.BitmapFactory

class MediaManager(private val context: Context) {

    private val TAG = "MediaManager"

    /**
     * Get file size in readable format
     */
    fun getFileSizeString(bytes: Long): String {
        return when {
            bytes <= 0 -> "0 B"
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "${bytes / (1024 * 1024 * 1024)} GB"
        }
    }

    /**
     * Get file name from URI
     */
    fun getFileNameFromUri(uri: Uri?): String? {
        if (uri == null) return null

        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex != -1 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file name", e)
            null
        }
    }

    /**
     * Get file size from URI
     */
    fun getFileSizeFromUri(uri: Uri?): Long {
        if (uri == null) return 0L

        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex != -1 && cursor.moveToFirst()) {
                    cursor.getLong(sizeIndex)
                } else 0L
            } ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size", e)
            0L
        }
    }

    /**
     * Check if valid image
     */
    fun isValidImage(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType?.startsWith("image/") == true
        } catch (e: Exception) {
            Log.e(TAG, "Error checking image", e)
            false
        }
    }

    /**
     * Check if valid PDF
     */
    fun isValidPdf(uri: Uri): Boolean {
        return try {
            val mimeType = context.contentResolver.getType(uri)
            mimeType == "application/pdf"
        } catch (e: Exception) {
            Log.e(TAG, "Error checking PDF", e)
            false
        }
    }

    /**
     * Create temp image file
     */
    fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val storageDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile("IMG_$timeStamp", ".jpg", storageDir)
    }

    /**
     * Convert URI to Base64 (compressed)
     */
    fun uriToBase64(uri: Uri, maxSizeKb: Int = 500): String? {
        return try {
            val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream?.close()

            var quality = 100
            var outputStream: ByteArrayOutputStream

            do {
                outputStream = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)
                quality -= 10
            } while (outputStream.size() / 1024 > maxSizeKb && quality > 10)

            Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)

        } catch (e: Exception) {
            Log.e(TAG, "Error converting URI to Base64", e)
            null
        }
    }

    /**
     * Get readable file info
     */
    fun getFileInfo(uri: Uri?): String {
        val name = getFileNameFromUri(uri) ?: "Unknown file"
        val size = getFileSizeString(getFileSizeFromUri(uri))
        return "$name ($size)"
    }
}