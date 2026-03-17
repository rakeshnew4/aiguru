# AI Guru - Enhanced Features Guide

## 🎯 What's New

AI Guru has been significantly upgraded with modern UI, voice capabilities, and media support!

### **Phase 1: Modern User Interface** ✅
- Clean, modern chat interface with Material Design 3
- RecyclerView-based message display for better performance
- Beautiful message cards with proper styling
- Modern buttons and input fields
- Animated loading indicators

### **Phase 2: Voice & Media Support** ✅  
- 🎤 **Voice Input** - Speak messages instead of typing
- 🔊 **Voice Output** - AI responses read aloud
- 🖼️ **Image Support** - Upload and share images
- 📄 **PDF Support** - Upload study documents
- 🎬 **Rich Preview** - Better message display

---

## 🚀 Quick Start

### Prerequisites
- Android API 26+
- Groq API key (for text generation)
- Microphone permission (for voice features)
- Storage permission (for media files)

### Installation

1. **Clone the repository:**
```bash
git clone https://github.com/rakeshnew4/aiguru.git
cd AIguru
```

2. **Add API Keys:**
   - Create `local.properties`:
   ```
   GROQ_API_KEY=your_groq_api_key_here
   ```

3. **Configure Firebase:**
   - Download `google-services.json` from Firebase Console
   - Place in `app/` directory

4. **Build & Run:**
```bash
./gradlew installDebug
```

---

## 🎤 Using Voice Features

### Voice Input (Speech-to-Text)

1. Click the **🎤 microphone button** in the input area
2. Grant microphone permission when prompted
3. Speak clearly - your speech will be transcribed
4. Click **⏹️ Stop** or wait for auto-stop
5. Your message appears in the input field
6. Click **Send** to submit

**Tips:**
- Speak within 10 feet of phone
- Use clear, distinct pronunciation  
- Pause between sentences for better recognition
- Supported languages: English (US)

### Voice Output (Text-to-Speech)

1. Click the **🔊 Play Voice** button on any AI response
2. AI response will be read aloud
3. Click again to stop playback

**Features:**
- Natural sounding voice
- Multiple speaker options
- Adjustable speed (1.0x by default)

---

## 🖼️ Using Media Features

### Image Upload

1. Click the **🖼️ image button** in the input area
2. Select image from gallery
3. Image is compressed automatically (max 500KB)
4. Image appears in chat as attachment
5. Send the message

**Supported Formats:**
- JPEG, PNG, WebP, GIF, BMP
- Max size: 500KB (auto-compressed)

### PDF Upload

1. Click the **📄 PDF button** in the input area
2. Select PDF from file browser
3. File info displays in chat
4. Send to share with AI Guru

**Features:**
- File validation
- Size display
- AI can analyze PDF content

---

## 💬 Chat Interface Guide

### Message Display
- **User messages:** Blue bubbles on the right
- **AI responses:** Gray bubbles on the left  
- **Timestamps:** Small gray text below each message
- **Media indicators:** Special icons for images/PDFs/voice

### Quick Action Buttons
Located at the top of the chat:

1. **📄 Summarize** - Get chapter summary
2. **💡 Explain** - Simple explanations  
3. **❓ Quiz** - Practice questions
4. **📝 Notes** - Study notes

### Input Area
- **🎤 Voice** - Record message
- **🖼️ Image** - Attach photo
- **📄 PDF** - Upload document
- **Text field** - Type or paste text
- **→ Send** - Submit message

---

## 💾 Data & History

### Chat History
- All messages automatically saved to Firebase
- History persists across app sessions
- Messages organized by Subject & Chapter
- Accessible in future versions

### Data Storage
- User data: Firebase Authentication
- Messages: Firestore Database
- Media: Base64 encoded in messages
- Auto-sync with cloud

---

## ⚙️ Settings & Permissions

### Required Permissions
- **RECORD_AUDIO** - For voice input
- **READ_EXTERNAL_STORAGE** - For media selection
- **WRITE_EXTERNAL_STORAGE** - For temporary files
- **INTERNET** - For API calls
- **POST_NOTIFICATIONS** - For future notifications

### Optional Enhancements
- Enable notifications for study reminders
- Dark mode (coming soon)
- Custom voice speed
- Language preferences

---

## 🐛 Troubleshooting

### Voice Input Not Working
**Problem:** Microphone not recording
**Solutions:**
1. Check microphone permission in Settings
2. Ensure microphone is not muted
3. Test microphone in other apps
4. Restart app and try again

### Voice Output Silent
**Problem:** Text-to-Speech not speaking
**Solutions:**
1. Check device volume
2. Enable TTS in system settings
3. Select supported language (English)
4. Install system TTS voice pack if needed

### Image Not Uploading
**Problem:** Image fails to attach
**Solutions:**
1. Check file size (must be < 10MB)
2. Verify file format (JPEG, PNG, etc.)
3. Check storage permissions
4. Clear app cache and retry

### PDF Upload Issues
**Problem:** PDF not attaching
**Solutions:**
1. Verify it's a valid PDF file
2. Check file size
3. Ensure storage permission granted
4. Try different PDF file

---

## 🎨 UI Features Overview

### Material Design 3
- Modern color scheme
- Rounded corners and shadows
- Smooth animations
- Accessible contrast ratios

### Responsive Layout
- Works on phones and tablets  
- Adapts to screen size
- Handles keyboard input
- Landscape/portrait support

### Performance
-Optimized RecyclerView
- Efficient message loading
- Lazy image loading
- Smooth scrolling

---

## 📊 Upcoming Features (Roadmap)

### Phase 3: Chat History
- [x] Persist chats to Firestore
- [ ] Search message history
- [ ] Filter by date/subject
- [ ] Export conversations
- [ ] Archive old chats

### Phase 4: Analytics
- [ ] Study statistics
- [ ] Progress tracking
- [ ] Learning streak counter
- [ ] Performance dashboard
- [ ] Time analysis

### Phase 5: Advanced
- [ ] Dark mode
- [ ] Offline mode
- [ ] Multiple AI models
- [ ] Custom study plans
- [ ] Collaboration features

---

## 💡 Tips & Tricks

1. **Faster Input** - Use voice for longer messages
2. **Study Aids** - Upload textbook images for analysis
3. **Quick Responses** - Use quick buttons for common tasks
4. **Review History** - Check past messages for reference
5. **Compare Responses** - Ask same question differently

---

## 🆘 Getting Help

### Report Issues
Submit bug reports with:
- Screenshot or video
- Steps to reproduce
- Device model & Android version
- Error message (if any)

### Feature Requests
Suggest features on GitHub Issues with:
- Clear description
- Use case
- Priority level
- Related features

---

## 📄 License

AI Guru is developed for educational purposes.  
See LICENSE file for details.

---

## 🙏 Credits

Built with:
- Android Jetpack (RecyclerView, Lifecycle, Coroutines)
- Material Design 3 Components
- Firebase (Auth, Firestore)
- Groq API
- Lottie Animations
- OkHttp Client

---

**Version:** 1.0+Phase2  
**Last Updated:** March 17, 2026  
**Status:** Active Development  

Happy Learning! 🎓📚
