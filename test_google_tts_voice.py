# #!/usr/bin/env python3
# """
# Test script to verify Google Cloud TTS voice quality.
# This will synthesize sample text in multiple languages and save MP3 files.

# Usage:
#     python test_google_tts_voice.py

# Prerequisites:
#     pip install google-cloud-texttospeech
#     export GOOGLE_APPLICATION_CREDENTIALS="/path/to/firebase_serviceaccount.json"
# """

# import os
# from google.cloud import texttospeech

# # Ensure service account is set
# SERVICE_ACCOUNT = os.path.join(
#     os.path.dirname(__file__),
#     "firebase_serviceaccount.json"
# )

# if not os.getenv("GOOGLE_APPLICATION_CREDENTIALS"):
#     os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = SERVICE_ACCOUNT

# print(f"✓ Using service account: {SERVICE_ACCOUNT}")
# print(f"✓ File exists: {os.path.isfile(SERVICE_ACCOUNT)}\n")


# # Sample texts in different languages
# SAMPLES = {
#     "hi-IN": {
#         "text": "नमस्ते, मैं गूगल क्लाउड टेक्स्ट टू स्पीच हूँ। यह एक परीक्षण है।",
#         "voice_name": "hi-IN-Neural2-A",
#         "description": "Hindi (India) - Neural2-A"
#     },
#     "en-IN": {
#         "text": "Hello, I am Google Cloud Text to Speech. This is a test in Indian English.",
#         "voice_name": "en-IN-Neural2-A", 
#         "description": "English (India) - Neural2-A"
#     },
#     "ta-IN": {
#         "text": "வணக்கம், நான் கூகுள் கிளவுட் நூல் முதன்மை பேச்சு ஆகும். இது தமிழ் மொழிতে ஒரு சோதனை.",
#         "voice_name": "ta-IN-Neural2-A",
#         "description": "Tamil (India) - Neural2-A"
#     },
# }

# def synthesize_speech(text, language_code, voice_name, speaking_rate=1.0):
#     """Synthesize speech and return MP3 bytes."""
#     client = texttospeech.TextToSpeechClient()
    
#     synthesis_input = texttospeech.SynthesisInput(text=text)
#     voice = texttospeech.VoiceSelectionParams(
#         language_code=language_code,
#         name=voice_name,
#         ssml_gender=texttospeech.SsmlVoiceGender.NEUTRAL
#     )
#     audio_config = texttospeech.AudioConfig(
#         audio_encoding=texttospeech.AudioEncoding.MP3,
#         speaking_rate=speaking_rate
#     )
    
#     response = client.synthesize_speech(
#         input=synthesis_input,
#         voice=voice,
#         audio_config=audio_config
#     )
    
#     return response.audio_content


# def main():
#     output_dir = os.path.dirname(__file__)
    
#     print("=" * 70)
#     print("GOOGLE CLOUD TTS - VOICE QUALITY TEST")
#     print("=" * 70)
#     print()
    
#     for lang_code, sample in SAMPLES.items():
#         output_file = os.path.join(output_dir, f"test_tts_{lang_code}.mp3")
        
#         print(f"🔊 Testing: {sample['description']}")
#         print(f"   Text: {sample['text'][:60]}...")
#         print(f"   Output: {output_file}")
        
#         try:
#             audio_bytes = synthesize_speech(
#                 text=sample['text'],
#                 language_code=lang_code,
#                 voice_name=sample['voice_name']
#             )
            
#             with open(output_file, "wb") as f:
#                 f.write(audio_bytes)
            
#             print(f"   ✅ SUCCESS! Size: {len(audio_bytes)} bytes")
#             print()
            
#         except Exception as e:
#             print(f"   ❌ ERROR: {e}")
#             print()
    
#     print("=" * 70)
#     print("FILES CREATED:")
#     print("=" * 70)
#     for filename in sorted(os.listdir(output_dir)):
#         if filename.startswith("test_tts_") and filename.endswith(".mp3"):
#             filepath = os.path.join(output_dir, filename)
#             size = os.path.getsize(filepath)
#             print(f"  📄 {filename} ({size:,} bytes)")
#             print(f"     → Open with: VLC, Windows Media Player, or any MP3 player")
    
#     print()
#     print("💡 WHAT TO LISTEN FOR:")
#     print("  - Clear pronunciation")
#     print("  - Natural inflection (not robotic)")
#     print("  - Good audio quality (no crackling/distortion)")
#     print("  - Language-appropriate accent")
#     print()


# if __name__ == "__main__":
#     main()

from google.cloud import aiplatform

aiplatform.init(project="ai-app-8ebd0", location="us-east1")
models = aiplatform.Model.list()