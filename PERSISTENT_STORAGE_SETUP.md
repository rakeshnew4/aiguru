# Persistent Data Storage Integration Guide

This guide explains how to integrate the new persistent storage system into the AI Guru app. Data stored via `StorageService` survives app uninstalls, updates, and device restarts.

## 📦 Storage Structure

```
/storage/emulated/0/AI Guru/
├── pdfs/              ← Downloaded course PDFs
├── images/            ← Course images, diagrams
├── audio/
│   └── tts/          ← Generated TTS audio (MP3 files)
├── cache/            ← Temporary cached data
└── metadata/         ← JSON configs, manifests
```

This directory is NOT deleted when:
- ✅ App is uninstalled
- ✅ App is updated
- ✅ App cache is cleared
- ✅ Device is restarted

---

## 🚀 Setup Instructions

### Step 1: Initialize StorageService in Application Class

Create or update your app's `Application` class:

```kotlin
package com.aiguruapp.student

import android.app.Application
import com.aiguruapp.student.utils.StorageMigrationHelper
import com.google.firebase.FirebaseApp

class AiGuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Initialize Firebase
        FirebaseApp.initializeApp(this)

        // Initialize persistent storage (call FIRST before any other init)
        val storageReady = StorageMigrationHelper.initializeStorageAndRecover(this)
        
        if (storageReady) {
            val summary = StorageMigrationHelper.getRecoverySummary()
            Log.d("AiGuruApp", "Storage initialized:\n$summary")
        } else {
            Log.w("AiGuruApp", "Storage initialization failed")
        }

        // ... rest of initialization (Firebase, Crashlytics, etc.)
    }
}
```

Add to `AndroidManifest.xml`:
```xml
<application
    android:name=".AiGuruApplication"
    ...
</application>
```

### Step 2: Update TTS Service (audio/mp3)

**File to modify**: `server/app/services/litellm_service.py` or wherever TTS files are generated.

When generating TTS audio, return the local storage path:

```python
# Old code
def generate_tts(text: str) -> str:
    response = client.text_to_speech(
        model="google_tts_voice",
        voice="en-US-Neural2-C",
        input_text=text
    )
    temp_file = f"/tmp/tts_{uuid.uuid4()}.mp3"
    with open(temp_file, "wb") as f:
        f.write(response)
    return temp_file  # ❌ Temporary - lost on uninstall

# New code - Return persistent storage path
def generate_tts(text: str, user_id: str, file_id: str) -> str:
    response = client.text_to_speech(
        model="google_tts_voice",
        voice="en-US-Neural2-C",
        input_text=text
    )
    # Save to persistent location
    persistent_path = f"user_data/{user_id}/audio/tts/{file_id}.mp3"
    with open(persistent_path, "wb") as f:
        f.write(response)
    return persistent_path  # ✅ Survives uninstall
```

### Step 3: Update Android Components

#### Example: TTS Playback Service

```kotlin
package com.aiguruapp.student.services

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.aiguruapp.student.services.StorageService
import java.io.File

class TtsPlaybackService(private val context: Context) {
    
    fun playTtsAudio(fileName: String, onCompletion: () -> Unit = {}) {
        try {
            // Get file from persistent storage
            val audioFile = StorageService.getTtsAudioFile(fileName)
            
            if (audioFile == null || !audioFile.exists()) {
                Log.w("TtsPlayback", "Audio file not found: $fileName")
                return
            }

            val mediaPlayer = MediaPlayer().apply {
                setDataSource(audioFile.absolutePath)
                setOnCompletionListener {
                    release()
                    onCompletion()
                }
                prepare()
                start()
            }
            
            Log.d("TtsPlayback", "Playing: ${audioFile.absolutePath}")
        } catch (e: Exception) {
            Log.e("TtsPlayback", "Error playing audio: ${e.message}", e)
        }
    }
    
    fun downloadAndPlayTtsAudio(
        questionId: String,
        audioUrl: String,
        onSuccess: () -> Unit = {},
        onError: (Exception) -> Unit = {}
    ) {
        try {
            val fileName = "${questionId}_tts.mp3"
            val audioFile = StorageService.getTtsAudioFile(fileName)
            
            if (audioFile != null && audioFile.exists()) {
                // Already cached
                playTtsAudio(fileName, onSuccess)
                return
            }

            // Download from server
            downloadFile(audioUrl, audioFile) { success ->
                if (success && audioFile != null) {
                    playTtsAudio(fileName, onSuccess)
                } else {
                    onError(Exception("Failed to download audio"))
                }
            }
        } catch (e: Exception) {
            Log.e("TtsPlayback", "Error: ${e.message}", e)
            onError(e)
        }
    }

    private fun downloadFile(
        url: String,
        destFile: File?,
        onComplete: (Boolean) -> Unit
    ) {
        // Use your HTTP client (Retrofit, OkHttp, etc.)
        // Example with coroutines:
        // viewModel.scope.launch {
        //     try {
        //         val response = apiService.downloadFile(url)
        //         destFile?.writeBytes(response.bytes())
        //         onComplete(true)
        //     } catch (e: Exception) {
        //         onComplete(false)
        //     }
        // }
    }
}
```

#### Example: Library/PDF Management

```kotlin
package com.aiguruapp.student.ui.library

import android.content.Context
import androidx.compose.runtime.Composable
import com.aiguruapp.student.services.StorageService
import java.io.File

class LibraryViewModel(private val context: Context) {
    
    fun downloadPdf(fileName: String, url: String): File? {
        return try {
            // Get file from persistent storage
            val pdfFile = StorageService.getPdfFile(fileName)
            
            if (pdfFile != null && pdfFile.exists()) {
                // Already downloaded
                return pdfFile
            }

            // Download to persistent storage
            downloadFileFromUrl(url, pdfFile) { success ->
                if (success) {
                    Log.d("Library", "PDF downloaded: $fileName")
                }
            }
            
            pdfFile
        } catch (e: Exception) {
            Log.e("Library", "Error downloading PDF: ${e.message}", e)
            null
        }
    }

    fun getAllDownloadedPdfs(): List<File> {
        return StorageService.getAllPdfs()
    }

    fun deletePdf(file: File): Boolean {
        return StorageService.deleteFile(file)
    }

    fun getStorageInfo(): StorageService.StorageInfo {
        return StorageService.getStorageInfo()
    }

    fun clearOldCache() {
        // Keep PDFs, but clear temporary cache older than 7 days
        StorageService.getFilesInFolder("cache").forEach { file ->
            val ageInDays = (System.currentTimeMillis() - file.lastModified()) / (1000 * 60 * 60 * 24)
            if (ageInDays > 7) {
                StorageService.deleteFile(file)
            }
        }
    }
}
```

#### Example: Quiz Questions with TTS

```kotlin
// In QuizSetupActivity or QuestionActivity
class QuestionViewModel(private val context: Context) {
    private val ttsService = TtsPlaybackService(context)

    fun setupQuestion(question: QuizQuestion) {
        // Load and cache TTS for this question
        if (question.ttsAudioUrl != null) {
            val audioFileName = "q_${question.id}_audio.mp3"
            ttsService.downloadAndPlayTtsAudio(
                questionId = question.id,
                audioUrl = question.ttsAudioUrl,
                onSuccess = {
                    Log.d("Question", "TTS ready for question ${question.id}")
                    // UI can now show "Play" button
                },
                onError = { error ->
                    Log.e("Question", "TTS download failed: ${error.message}")
                    // Fall back to text
                }
            )
        }
    }

    fun playQuestionAudio(questionId: String) {
        val fileName = "q_${questionId}_audio.mp3"
        ttsService.playTtsAudio(fileName)
    }
}
```

### Step 4: Add Cloud Backup (Optional)

For optional Firebase cloud backup, add to your settings/account activity:

```kotlin
class AccountSettingsActivity : AppCompatActivity() {
    private val backupService = CloudBackupService()
    
    fun backupDataToCloud() {
        val userId = getCurrentUserId()
        
        backupService.backupAllData(
            context = this,
            userId = userId,
            onProgress = { message ->
                updateUI("Backup: $message")
            },
            onSuccess = {
                showToast("Backup completed!")
            },
            onError = { error ->
                showToast("Backup failed: ${error.message}")
            }
        )
    }

    fun restoreDataFromCloud() {
        val userId = getCurrentUserId()
        
        backupService.restoreLatestBackup(
            userId = userId,
            onProgress = { message ->
                updateUI("Restore: $message")
            },
            onSuccess = { fileCount ->
                showToast("Restored $fileCount files")
                // Restart app to reload cache
                restartApp()
            },
            onError = { error ->
                showToast("Restore failed: ${error.message}")
            }
        )
    }

    fun deleteOldCloudBackups() {
        val userId = getCurrentUserId()
        
        backupService.deleteOldBackups(
            userId = userId,
            keepLastN = 3,
            onSuccess = { deletedCount ->
                showToast("Deleted $deletedCount old backups")
            }
        )
    }
}
```

### Step 5: Add Settings UI

Add storage information to app settings:

```kotlin
@Composable
fun StorageSettingsScreen() {
    val storageInfo = StorageService.getStorageInfo()
    
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Storage Usage", style = MaterialTheme.typography.headlineSmall)
        
        StorageProgressBar(
            used = storageInfo.totalSizeMB,
            max = 2048.0,  // 2GB
            label = "Total Storage"
        )
        
        Row {
            StorageCard("PDFs", storageInfo.pdfSizeMB)
            StorageCard("Images", storageInfo.imageSizeMB)
            StorageCard("Audio", storageInfo.audioSizeMB)
            StorageCard("Cache", storageInfo.cacheSizeMB)
        }
        
        Button(onClick = { StorageService.clearFolder("cache") }) {
            Text("Clear Cache (${String.format("%.1f", storageInfo.cacheSizeMB)} MB)")
        }
        
        Button(onClick = { /* Cloud Backup */ }) {
            Text("Backup to Cloud")
        }
        
        Text(
            "Storage Path: ${StorageService.getStoragePath()}",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp
        )
    }
}
```

---

## 🔍 Runtime Permissions

For Android 12+, add permission prompt in your MainActivity or SplashActivity:

```kotlin
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class SplashActivity : AppCompatActivity() {
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("Permissions", "MANAGE_EXTERNAL_STORAGE granted")
            // Initialize storage
            StorageMigrationHelper.initializeStorageAndRecover(this)
        } else {
            Log.w("Permissions", "MANAGE_EXTERNAL_STORAGE denied")
            // Fallback to app-specific directory (less ideal)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(
                    android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
                )
            }
        } else {
            // For Android 11 and below
            StorageMigrationHelper.initializeStorageAndRecover(this)
        }
    }
}
```

---

## 📝 Testing Checklist

- [ ] App stores PDFs to `/storage/emulated/0/AI Guru/pdfs/`
- [ ] App stores images to `/storage/emulated/0/AI Guru/images/`
- [ ] App stores TTS audio to `/storage/emulated/0/AI Guru/audio/tts/`
- [ ] Uninstall app → files remain on device
- [ ] Reinstall app → files are recovered
- [ ] App update → cache not cleared
- [ ] Clear app data → storage not affected
- [ ] Offline mode → can access cached files
- [ ] Device restart → files accessible after reboot
- [ ] Storage info displayed correctly in settings
- [ ] Cloud backup uploads files to Firebase
- [ ] Cloud restore downloads files back

---

## 🛠️ Debugging

### Check Storage Contents
```bash
adb shell ls -la /storage/emulated/0/"AI Guru"/
adb shell ls -la /storage/emulated/0/"AI Guru"/audio/tts/
```

### Monitor File Creation
```bash
adb logcat | grep StorageService
```

### Test Uninstall/Reinstall
```bash
adb uninstall com.aiguruapp.student
# Files remain in /storage/emulated/0/"AI Guru"/
adb install app-release.apk
# App recovers files on launch
```

---

## 📊 Performance Notes

- **First-time initialization**: ~200-500ms (depends on number of files to migrate)
- **File access**: ~1-5ms (local storage, very fast)
- **Cloud backup**: Depends on file size and network speed
- **Migration from internal cache**: Automatic on first launch

---

## ❌ Common Issues & Solutions

### Issue: "MANAGE_EXTERNAL_STORAGE permission denied"
**Solution**: User must grant permission at runtime (Android 12+)
```kotlin
// Already handled in SplashActivity code above
```

### Issue: "Storage not available"
**Solution**: Check if device is connected to USB in MTP mode, or storage is being wiped
```kotlin
if (!StorageService.isStorageAvailable()) {
    Log.e("Storage", "External storage not mounted")
}
```

### Issue: "Files not recovered after reinstall"
**Solution**: Ensure `checkAndRecoverPreviousData()` is called in initialization
```kotlin
StorageMigrationHelper.initializeStorageAndRecover(context)  // ← Must call
```

### Issue: "Cache grows too large"
**Solution**: Implement cleanup policy
```kotlin
fun cleanOldCacheFiles() {
    StorageService.getFilesInFolder("cache").forEach { file ->
        val ageInDays = (System.currentTimeMillis() - file.lastModified()) / 86400000
        if (ageInDays > 30) {
            StorageService.deleteFile(file)
        }
    }
}
```

