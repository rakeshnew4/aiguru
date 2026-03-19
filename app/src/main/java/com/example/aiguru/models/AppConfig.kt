package com.example.aiguru.models

data class ThemeConfig(
    val primaryColor: String,
    val primaryDarkColor: String,
    val primaryLightColor: String,
    val accentColor: String,
    val backgroundColor: String,
    val headerTextColor: String,
    val headerSubtextColor: String,
    val bodyTextPrimaryColor: String,
    val bodyTextSecondaryColor: String,
    val cardBackgroundColor: String,
    val buttonPrimaryColor: String,
    val buttonSecondaryColor: String,
    val buttonAccentColor: String,
    val successColor: String,
    val warningColor: String
)

data class FeaturesConfig(
    val realTeacher: Boolean,
    val library: Boolean,
    val revision: Boolean,
    val voiceChat: Boolean,
    val pdfAnalysis: Boolean,
    val flashcards: Boolean,
    val mockTests: Boolean,
    val parentReports: Boolean
)

data class HomeScreenConfig(
    val showSubjectGrid: Boolean,
    val showLibraryButton: Boolean,
    val showRealTeacherButton: Boolean,
    val showAddSubjectButton: Boolean,
    val gridColumns: Int,
    val motivationalQuotes: List<String>
)

data class AppConfig(
    val version: String,
    val appName: String,
    val tagline: String,
    val supportEmail: String,
    val appVersion: String,
    val defaultTheme: ThemeConfig,
    val features: FeaturesConfig,
    val greetingMessages: Map<String, String>,
    val homeScreen: HomeScreenConfig
)
