package com.example.aiguru.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MediaManager(private val context: Context) {

    private val TAG = "MediaManager"

    /**
     * Get the file size in a human-readable format
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
        return try {\n            if (uri != null) {\n                val cursor = context.contentResolver.query(uri, null, null, null, null)\n                cursor?.use {\n                    val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)\n                    it.moveToFirst()\n                    it.getString(nameIndex)\n                }\n            } else {\n                null\n            }\n        } catch (e: Exception) {\n            Log.e(TAG, \"Error getting file name\", e)\n            null\n        }\n    }\n\n    /**\n     * Get file size from URI\n     */\n    fun getFileSizeFromUri(uri: Uri?): Long {\n        return try {\n            if (uri != null) {\n                val cursor = context.contentResolver.query(uri, null, null, null, null)\n                cursor?.use {\n                    val sizeIndex = it.getColumnIndex(android.provider.OpenableColumns.SIZE)\n                    it.moveToFirst()\n                    it.getLong(sizeIndex)\n                }\n            } else {\n                0L\n            }\n        } catch (e: Exception) {\n            Log.e(TAG, \"Error getting file size\", e)\n            0L\n        }\n    }\n\n    /**\n     * Check if file is a valid image\n     */\n    fun isValidImage(uri: Uri): Boolean {\n        return try {\n            val mimeType = context.contentResolver.getType(uri)\n            mimeType?.startsWith(\"image/\") == true\n        } catch (e: Exception) {\n            Log.e(TAG, \"Error checking image\", e)\n            false\n        }\n    }\n\n    /**\n     * Check if file is a valid PDF\n     */\n    fun isValidPdf(uri: Uri): Boolean {\n        return try {\n            val mimeType = context.contentResolver.getType(uri)\n            mimeType == \"application/pdf\"\n        } catch (e: Exception) {\n            Log.e(TAG, \"Error checking PDF\", e)\n            false\n        }\n    }\n\n    /**\n     * Create a temporary file for image capture\n     */\n    fun createImageFile(): File {\n        val timeStamp: String = SimpleDateFormat(\"yyyyMMdd_HHmmss\", Locale.US).format(Date())\n        val storageDir: File? = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)\n        return File.createTempFile(\"IMG_$timeStamp\", \".jpg\", storageDir)\n    }\n\n    /**\n     * Compress and convert image URI to Base64\n     */\n    fun uriToBase64(uri: Uri, maxSizeKb: Int = 500): String? {\n        return try {\n            val inputStream = context.contentResolver.openInputStream(uri)\n            val bitmap = android.graphics.BitmapFactory.decodeStream(inputStream)\n\n            // Calculate compression quality based on size\n            var quality = 100\n            var outputStream = java.io.ByteArrayOutputStream()\n            while (outputStream.size() / 1024 > maxSizeKb && quality > 10) {\n                outputStream = java.io.ByteArrayOutputStream()\n                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, quality, outputStream)\n                quality -= 10\n            }\n\n            android.util.Base64.encodeToString(outputStream.toByteArray(), android.util.Base64.NO_WRAP)\n        } catch (e: Exception) {\n            Log.e(TAG, \"Error converting URI to Base64\", e)\n            null\n        }\n    }\n\n    /**\n     * Get readable file info string\n     */\n    fun getFileInfo(uri: Uri?): String {\n        val fileName = getFileNameFromUri(uri) ?: \"Unknown file\"\n        val fileSize = getFileSizeFromUri(uri)\n        val fileSizeStr = getFileSizeString(fileSize)\n        return \"$fileName ($fileSizeStr)\"\n    }\n}\n