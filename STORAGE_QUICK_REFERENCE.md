# StorageService Quick Reference

## Core API

### Initialize (Call Once on App Start)
```kotlin
StorageMigrationHelper.initializeStorageAndRecover(context)
```

### File Operations

#### Get Files
```kotlin
// Get or create a file
val pdfFile = StorageService.getPdfFile("chapter_1.pdf")
val imageFile = StorageService.getImageFile("lesson_diagram.jpg")
val audioFile = StorageService.getTtsAudioFile("q_1_answer.mp3")
val cacheFile = StorageService.getCacheFile("temp_data.json")
val metaFile = StorageService.getMetadataFile("config.json")

// Get all files of a type
val allPdfs = StorageService.getAllPdfs()  // → List<File>
val allImages = StorageService.getAllImages()
val allAudio = StorageService.getAllTtsAudio()

// Get files in a folder
val customFiles = StorageService.getFilesInFolder("audio/tts")
```

#### Write Files
```kotlin
val file = StorageService.getPdfFile("chapter.pdf")
file?.writeBytes(pdfBytes)  // Write data
file?.writeText(jsonText)   // Write text
```

#### Read Files
```kotlin
val file = StorageService.getPdfFile("chapter.pdf")
val data = file?.readBytes()      // Read bytes
val text = file?.readText()       // Read as string
```

#### Delete Files
```kotlin
val file = StorageService.getPdfFile("chapter.pdf")
StorageService.deleteFile(file)  // Delete single file

StorageService.clearFolder("cache")  // Delete all cache files
```

---

## Storage Info

```kotlin
// Get detailed storage info
val info = StorageService.getStorageInfo()
println("Total: ${info.totalSizeMB} MB")
println("PDFs: ${info.pdfSizeMB} MB")
println("Images: ${info.imageSizeMB} MB")
println("Audio: ${info.audioSizeMB} MB")
println("Cache: ${info.cacheSizeMB} MB")

// Get storage path
val path = StorageService.getStoragePath()
// → "/storage/emulated/0/AI Guru"

// Check if storage available
val available = StorageService.isStorageAvailable()

// Get total size in MB
val totalMB = StorageService.getTotalStorageSize()
```

---

## Cloud Backup

```kotlin
val backupService = CloudBackupService()

// Backup all data to Firebase
backupService.backupAllData(
    context = this,
    userId = "user123",
    onProgress = { message -> println(message) },
    onSuccess = { println("Backup done") },
    onError = { error -> println("Failed: ${error.message}") }
)

// Restore from backup
backupService.restoreLatestBackup(
    userId = "user123",
    onProgress = { message -> println(message) },
    onSuccess = { fileCount -> println("Restored $fileCount files") },
    onError = { error -> println("Failed: ${error.message}") }
)

// Delete old backups (keep last 3)
backupService.deleteOldBackups(
    userId = "user123",
    keepLastN = 3,
    onSuccess = { deletedCount -> println("Deleted $deletedCount") }
)
```

---

## Directory Structure

```
/storage/emulated/0/AI Guru/
├── pdfs/                    ← PDFs survive uninstall
├── images/                  ← Images survive uninstall
├── audio/
│   └── tts/                 ← MP3/audio files
├── cache/                   ← Temporary cache
└── metadata/                ← Config files
```

---

## Common Patterns

### Download & Cache PDF
```kotlin
fun downloadPdf(url: String, fileName: String) {
    // Check if already cached
    val cachedFile = StorageService.getPdfFile(fileName)
    if (cachedFile?.exists() == true) {
        return cachedFile  // Use cached
    }

    // Download to persistent storage
    val data = downloadFromServer(url)
    cachedFile?.writeBytes(data)
    return cachedFile
}
```

### Generate TTS Audio
```kotlin
fun generateAndCacheTts(text: String, questionId: String): File? {
    val fileName = "q_${questionId}_audio.mp3"
    val audioFile = StorageService.getTtsAudioFile(fileName)
    
    // Generate TTS
    val mp3Data = generateTtsFromServer(text)
    audioFile?.writeBytes(mp3Data)
    
    return audioFile  // Save to persistent storage
}
```

### Play Cached Audio
```kotlin
fun playAudio(questionId: String) {
    val fileName = "q_${questionId}_audio.mp3"
    val audioFile = StorageService.getTtsAudioFile(fileName)
    
    if (audioFile?.exists() == true) {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setDataSource(audioFile.absolutePath)
        mediaPlayer.prepare()
        mediaPlayer.start()
    }
}
```

### Clean Old Cache
```kotlin
fun cleanupOldCache() {
    StorageService.getFilesInFolder("cache").forEach { file ->
        val ageInDays = (System.currentTimeMillis() - file.lastModified()) / 86400000
        if (ageInDays > 7) {  // Delete files older than 7 days
            StorageService.deleteFile(file)
        }
    }
}
```

---

## What Survives What?

| Event | PDFs | Images | Audio | Cache |
|-------|------|--------|-------|-------|
| **App Uninstall** | ✅ YES | ✅ YES | ✅ YES | ✅ YES |
| **App Update** | ✅ YES | ✅ YES | ✅ YES | ✅ YES |
| **Clear App Cache** | ✅ YES | ✅ YES | ✅ YES | ⚠️ Not auto-cleared |
| **Clear App Data** | ✅ YES | ✅ YES | ✅ YES | ✅ YES |
| **Device Restart** | ✅ YES | ✅ YES | ✅ YES | ✅ YES |
| **Manual Delete** | If not deleted | If not deleted | If not deleted | If not deleted |

---

## Error Handling

```kotlin
// All file operations return nullable File
val file = StorageService.getPdfFile("file.pdf")
if (file != null) {
    // File created/retrieved successfully
    file.writeBytes(data)
} else {
    // Storage not available
    Log.e("Storage", "Failed to get file")
}

// Check storage before operations
if (!StorageService.isStorageAvailable()) {
    Log.w("Storage", "External storage not available")
    // Fallback to internal storage or cache
}
```

---

## Permissions Required

**AndroidManifest.xml:**
```xml
<!-- Android 11+ -->
<uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" />

<!-- Older versions -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

**Runtime (Android 12+):**
```kotlin
if (ContextCompat.checkSelfPermission(context, 
    android.Manifest.permission.MANAGE_EXTERNAL_STORAGE
) != PackageManager.PERMISSION_GRANTED) {
    launcher.launch(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
}
```

---

## Useful Commands

```bash
# Check storage contents
adb shell ls -la "/storage/emulated/0/AI Guru/"

# View specific folder
adb shell ls -la "/storage/emulated/0/AI Guru/audio/tts/"

# Check file size
adb shell du -sh "/storage/emulated/0/AI Guru/"

# Monitor storage (live)
adb shell watch -n 1 "du -sh /storage/emulated/0/AI\ Guru/"

# Pull entire app folder to desktop
adb pull "/storage/emulated/0/AI Guru/" ./ai_guru_backup/

# Push folder back
adb push ./ai_guru_backup/ "/storage/emulated/0/AI Guru/"
```

---

## Initialization Example

```kotlin
// In Application.onCreate()
class AiGuruApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        
        // Initialize storage - MUST be first
        if (StorageMigrationHelper.initializeStorageAndRecover(this)) {
            Log.d("App", "Storage ready")
            Log.d("App", StorageMigrationHelper.getRecoverySummary())
        } else {
            Log.w("App", "Storage initialization failed")
        }
    }
}
```

---

## Performance

| Operation | Time |
|-----------|------|
| Initialize storage | 200-500ms |
| Get file | 1-5ms |
| Read file (1MB) | 5-20ms |
| Write file (1MB) | 10-50ms |
| List files (100 files) | 10-30ms |
| Calculate size | 50-200ms |

