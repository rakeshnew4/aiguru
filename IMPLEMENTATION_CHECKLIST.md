# Implementation Checklist - Persistent Storage

Complete this checklist to implement persistent storage in AI Guru app.

---

## ✅ Phase 1: Setup (Required)

- [ ] **Created StorageService.kt** - Main service for file management
  - Location: `app/src/main/java/com/aiguruapp/student/services/StorageService.kt`
  - Status: ✅ DONE

- [ ] **Created CloudBackupService.kt** - Optional Firebase backup
  - Location: `app/src/main/java/com/aiguruapp/student/services/CloudBackupService.kt`
  - Status: ✅ DONE

- [ ] **Created StorageMigrationHelper.kt** - Recovery on app launch
  - Location: `app/src/main/java/com/aiguruapp/student/utils/StorageMigrationHelper.kt`
  - Status: ✅ DONE

- [ ] **Updated AndroidManifest.xml** - Added permissions
  - Added: `android.permission.MANAGE_EXTERNAL_STORAGE`
  - Status: ✅ DONE

---

## ✅ Phase 2: Application Class (Required)

**File**: `app/src/main/java/com/aiguruapp/student/AiGuruApplication.kt` (or equivalent)

### Required Change:
```kotlin
import com.aiguruapp.student.utils.StorageMigrationHelper

class AiGuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // ADD THIS LINE (initialize storage first)
        StorageMigrationHelper.initializeStorageAndRecover(this)
        
        // ... rest of your initialization
        FirebaseApp.initializeApp(this)
        // ... other setup
    }
}
```

### AndroidManifest.xml:
```xml
<application
    android:name=".AiGuruApplication"  <!-- Ensure your app uses this class -->
    ...
</application>
```

**Checklist**:
- [ ] Application class has `StorageMigrationHelper.initializeStorageAndRecover(this)` call
- [ ] Application class is registered in AndroidManifest.xml
- [ ] Check logcat for "Storage initialized" message when app starts

---

## ⏳ Phase 3: TTS Audio Service (Update existing)

**File**: Find where TTS audio is generated or downloaded (likely in server)

### Before (❌ Lost on uninstall):
```kotlin
fun downloadTtsAudio(audioUrl: String, questionId: String): File {
    val cacheDir = context.cacheDir  // ← Deleted on uninstall!
    val audioFile = File(cacheDir, "tts_${questionId}.mp3")
    // Download to cacheDir
    return audioFile
}
```

### After (✅ Survives uninstall):
```kotlin
fun downloadTtsAudio(audioUrl: String, questionId: String): File? {
    // Get file from persistent storage
    val audioFile = StorageService.getTtsAudioFile("q_${questionId}_audio.mp3")
    
    if (audioFile?.exists() == true) {
        return audioFile  // Already cached
    }
    
    // Download to persistent storage
    try {
        val audioBytes = downloadFromServer(audioUrl)
        audioFile?.writeBytes(audioBytes)
        return audioFile
    } catch (e: Exception) {
        Log.e("TTS", "Failed to download audio: ${e.message}")
        return null
    }
}

fun playTtsAudio(questionId: String) {
    val audioFile = StorageService.getTtsAudioFile("q_${questionId}_audio.mp3")
    if (audioFile?.exists() == true) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(audioFile.absolutePath)
        mediaPlayer.prepare()
        mediaPlayer.start()
    }
}
```

**Checklist**:
- [ ] Find all places where TTS audio is cached
- [ ] Replace `context.cacheDir` with `StorageService.getTtsAudioFile()`
- [ ] Update playback to read from `StorageService`
- [ ] Test: Play a question's TTS → verify file in `/storage/emulated/0/AI Guru/audio/tts/`

---

## ⏳ Phase 4: PDF/Image Downloads (Update existing)

**File**: Library.kt, LibraryViewModel.kt, or similar

### Before (❌ Lost on uninstall):
```kotlin
fun downloadPdf(pdfUrl: String, fileName: String): File {
    val pdfDir = File(context.cacheDir, "pdfs")  // ← Deleted!
    pdfDir.mkdirs()
    val pdfFile = File(pdfDir, fileName)
    // Download
    return pdfFile
}
```

### After (✅ Survives uninstall):
```kotlin
fun downloadPdf(pdfUrl: String, fileName: String): File? {
    // Get file from persistent storage
    val pdfFile = StorageService.getPdfFile(fileName)
    
    if (pdfFile?.exists() == true) {
        return pdfFile  // Already cached
    }
    
    // Download to persistent storage
    try {
        val pdfBytes = downloadFromServer(pdfUrl)
        pdfFile?.writeBytes(pdfBytes)
        return pdfFile
    } catch (e: Exception) {
        Log.e("Library", "Failed to download PDF: ${e.message}")
        return null
    }
}

fun downloadImage(imageUrl: String, fileName: String): File? {
    val imageFile = StorageService.getImageFile(fileName)
    
    if (imageFile?.exists() == true) {
        return imageFile
    }
    
    try {
        val imageBytes = downloadFromServer(imageUrl)
        imageFile?.writeBytes(imageBytes)
        return imageFile
    } catch (e: Exception) {
        Log.e("Library", "Failed to download image: ${e.message}")
        return null
    }
}

fun getAllDownloadedPdfs(): List<File> {
    return StorageService.getAllPdfs()
}

fun deletePdf(file: File) {
    StorageService.deleteFile(file)
}
```

**Checklist**:
- [ ] Find all PDF/image download functions
- [ ] Replace `context.cacheDir` with `StorageService.get*File()`
- [ ] Update list/display functions to use `StorageService.getAllPdfs()`, etc.
- [ ] Test: Download PDF → verify file in `/storage/emulated/0/AI Guru/pdfs/`
- [ ] Test: Uninstall app → files still visible in `/storage/emulated/0/AI Guru/`

---

## ⏳ Phase 5: Permission Handling (Update SplashActivity)

**File**: `app/src/main/java/com/aiguruapp/student/SplashActivity.kt`

### Add Permission Request:
```kotlin
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager

class SplashActivity : AppCompatActivity() {
    
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            Log.d("SplashScreen", "MANAGE_EXTERNAL_STORAGE granted")
            continueToMainScreen()
        } else {
            Log.w("SplashScreen", "MANAGE_EXTERNAL_STORAGE denied - using fallback")
            continueToMainScreen()  // Can still work with limited storage
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)
        
        // Check and request storage permission
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(
                    android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
                )
            } else {
                continueToMainScreen()
            }
        } else {
            continueToMainScreen()
        }
    }

    private fun continueToMainScreen() {
        // Check app version, show onboarding, etc.
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
```

**Checklist**:
- [ ] Add permission request logic to SplashActivity
- [ ] Test on Android 12+ device → permission prompt appears
- [ ] Test accept → app continues normally
- [ ] Test deny → app still works (with fallback)

---

## ⏳ Phase 6: Settings Screen (Optional)

**File**: Create or update `SettingsFragment.kt` or `StorageSettingsActivity.kt`

### Display Storage Info:
```kotlin
@Composable
fun StorageSettingsScreen() {
    val storageInfo = StorageService.getStorageInfo()
    
    Column(modifier = Modifier.padding(16.dp)) {
        Text("Storage Information", style = MaterialTheme.typography.headlineSmall)
        
        // Total storage bar
        LinearProgressIndicator(
            progress = (storageInfo.totalSizeMB / 2048.0).toFloat(),  // 2GB max
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
        )
        Text("${String.format("%.1f", storageInfo.totalSizeMB)} MB of 2 GB used")
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Breakdown
        Text("Breakdown:", style = MaterialTheme.typography.titleMedium)
        StorageInfoRow("PDFs", storageInfo.pdfSizeMB)
        StorageInfoRow("Images", storageInfo.imageSizeMB)
        StorageInfoRow("Audio", storageInfo.audioSizeMB)
        StorageInfoRow("Cache", storageInfo.cacheSizeMB)
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Actions
        Button(onClick = { 
            StorageService.clearFolder("cache")
            Toast.makeText(context, "Cache cleared", Toast.LENGTH_SHORT).show()
        }) {
            Text("Clear Cache (${String.format("%.1f", storageInfo.cacheSizeMB)} MB)")
        }
        
        Button(onClick = { /* Backup to cloud */ }) {
            Text("Backup to Cloud")
        }
        
        Text(
            "Path: ${StorageService.getStoragePath()}",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 10.sp
        )
    }
}

@Composable
fun StorageInfoRow(label: String, sizeMB: Double) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label:", modifier = Modifier.weight(1f))
        Text("${String.format("%.1f", sizeMB)} MB")
    }
}
```

**Checklist**:
- [ ] Add storage info display to settings screen
- [ ] Add "Clear Cache" button
- [ ] Test: Button clears cache files without affecting PDFs/images
- [ ] Optional: Add "Backup to Cloud" button

---

## ⏳ Phase 7: Cloud Backup (Optional)

**File**: `AccountSettingsActivity.kt` or similar

### Add Backup/Restore UI:
```kotlin
fun backupAllDataToCloud() {
    val userId = getCurrentUserId()
    val backupService = CloudBackupService()
    
    showProgressDialog("Backing up...")
    
    backupService.backupAllData(
        context = this,
        userId = userId,
        onProgress = { message ->
            updateProgressDialog(message)
        },
        onSuccess = {
            dismissProgressDialog()
            showToast("Backup completed successfully!")
        },
        onError = { error ->
            dismissProgressDialog()
            showError("Backup failed: ${error.message}")
        }
    )
}

fun restoreDataFromCloud() {
    val userId = getCurrentUserId()
    val backupService = CloudBackupService()
    
    showProgressDialog("Restoring...")
    
    backupService.restoreLatestBackup(
        userId = userId,
        onProgress = { message ->
            updateProgressDialog(message)
        },
        onSuccess = { fileCount ->
            dismissProgressDialog()
            showToast("Restored $fileCount files. Restarting app...")
            // Restart app to reload cache
            Thread.sleep(2000)
            restartApp()
        },
        onError = { error ->
            dismissProgressDialog()
            showError("Restore failed: ${error.message}")
        }
    )
}
```

**Checklist**:
- [ ] Add backup button to account settings
- [ ] Add restore button
- [ ] Test: Click backup → files uploaded to Firebase Storage
- [ ] Test: Verify files in Firebase Console → `user_data_backups/`
- [ ] Test: Click restore → files downloaded back

---

## 📋 Verification Checklist

### After Completing All Phases:

- [ ] **App Launch**
  - [ ] App starts without crashes
  - [ ] Logcat shows "Storage initialized successfully"
  - [ ] Check: `/storage/emulated/0/AI Guru/` folder exists and contains subdirectories

- [ ] **Download Files**
  - [ ] Download a PDF → verify in `/storage/emulated/0/AI Guru/pdfs/`
  - [ ] Download an image → verify in `/storage/emulated/0/AI Guru/images/`
  - [ ] Generate TTS audio → verify in `/storage/emulated/0/AI Guru/audio/tts/`

- [ ] **Uninstall/Reinstall**
  - [ ] Use: `adb uninstall com.aiguruapp.student`
  - [ ] Files still visible in `/storage/emulated/0/AI Guru/`
  - [ ] Reinstall app: `adb install app-release.apk`
  - [ ] App recovers files on launch
  - [ ] Verify files accessible in app

- [ ] **App Update**
  - [ ] Increment `versionCode` in build.gradle
  - [ ] Build new APK
  - [ ] Install via `adb install -r app-release.apk`
  - [ ] Verify files still intact

- [ ] **Storage Settings**
  - [ ] Open Settings → Storage
  - [ ] Verify storage usage displayed correctly
  - [ ] Click "Clear Cache" → cache deleted but PDFs/images remain

- [ ] **Permissions (Android 12+)**
  - [ ] First launch shows permission prompt
  - [ ] Accept → app continues normally
  - [ ] Deny → app still works but with fallback

- [ ] **Offline Access**
  - [ ] Put device in airplane mode
  - [ ] Open app → cached files accessible
  - [ ] Can play TTS audio, view PDFs, etc.

---

## 🚀 Deployment Steps

### 1. Build Release APK
```bash
cd /home/administrator/mywork/Work_Space/aiguru
./gradlew clean assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

### 2. Test on Device
```bash
adb install app-release.apk
# Verify all functionality from checklist above
```

### 3. Upload to Play Store
```
Firebase Console → App Distribution → Upload APK
Or: Play Store Console → Internal Testing
```

### 4. Release Notes
```
✨ New Features:
- Persistent file storage across app updates
- PDFs, images, and TTS audio now survive uninstall
- Optional cloud backup to Firebase
- Storage info available in Settings

🔧 Technical:
- Files stored in: /storage/emulated/0/AI Guru/
- No data lost on app update
- Automatic recovery on reinstall
```

---

## 📞 Support & Debugging

### Enable Verbose Logging
```kotlin
// In StorageService and CloudBackupService, all operations are logged
adb logcat | grep -E "StorageService|CloudBackupService"
```

### Common Issues

**Q: Files not found after reinstall?**  
A: Ensure `StorageMigrationHelper.initializeStorageAndRecover()` is called in Application.onCreate()

**Q: Permission denied?**  
A: Run on Android 12+ → grant MANAGE_EXTERNAL_STORAGE permission when prompted

**Q: Storage not available?**  
A: Check if device is connected via USB in file transfer mode (not charging only)

**Q: Files not recovered from cloud?**  
A: Ensure user is signed in to Firebase, check internet connection

---

## 📞 Contact & Support

- **Logs**: Check logcat for "StorageService" messages
- **Firebase**: Check `user_data_backups/` folder in Firebase Storage
- **Device**: Check `/storage/emulated/0/AI Guru/` with file manager

