package com.aiguruapp.student.utils

import android.content.Context
import com.aiguruapp.student.config.AppStartRepository
import com.aiguruapp.student.models.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Loads and caches school and app configuration.
 * Primary source: Firestore (via AppStartRepository, fetched during SplashActivity).
 * Fallback: bundled assets/schools_config.json (always available offline).
 */
object ConfigManager {

    private var schoolsJson: List<School>? = null  // JSON fallback cache
    private var appConfig: AppConfig? = null

    // ── Schools ──────────────────────────────────────────────────────────────

    fun getSchools(context: Context): List<School> {
        // Prefer Firestore-fetched schools (loaded during SplashActivity splash)
        val remote = AppStartRepository.schools
        if (remote.isNotEmpty()) return remote
        // Fall back to bundled JSON
        if (schoolsJson == null) schoolsJson = loadSchools(context)
        return schoolsJson!!
    }

    fun getSchool(context: Context, schoolId: String): School? =
        getSchools(context).find { it.id == schoolId }

    /** Find a school by its short code (case-insensitive). Used in SchoolJoinActivity. */
    fun findSchoolByCode(context: Context, code: String): School? {
        if (code.length < 2) return null
        return getSchools(context).find { it.code.equals(code, ignoreCase = true) }
    }

    fun searchSchools(context: Context, query: String): List<School> {
        if (query.isBlank()) return getSchools(context)
        val q = query.lowercase()
        return getSchools(context).filter {
            it.name.lowercase().contains(q) ||
                it.city.lowercase().contains(q) ||
                it.code.lowercase().contains(q) ||
                it.shortName.lowercase().contains(q)
        }
    }

    // ── App Config ────────────────────────────────────────────────────────────

    fun getAppConfig(context: Context): AppConfig {
        if (appConfig == null) appConfig = loadAppConfig(context)
        return appConfig!!
    }

    // ── Cache invalidation (call when switching to remote source) ─────────────

    fun clearCache() {
        schoolsJson = null
        appConfig = null
    }

    // ── Private loaders ───────────────────────────────────────────────────────

    private fun loadSchools(context: Context): List<School> {
        return try {
            val json = context.assets.open("schools_config.json")
                .bufferedReader().readText()
            val root = JSONObject(json)
            val arr = root.getJSONArray("schools")
            (0 until arr.length()).map { parseSchool(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun parseSchool(obj: JSONObject): School {
        val branding = obj.getJSONObject("branding").let { b ->
            SchoolBranding(
                primaryColor = b.getString("primaryColor"),
                primaryDarkColor = b.optString("primaryDarkColor", b.getString("primaryColor")),
                accentColor = b.getString("accentColor"),
                backgroundColor = b.getString("backgroundColor"),
                headerTextColor = b.optString("headerTextColor", "#FFFFFF"),
                headerSubtextColor = b.optString("headerSubtextColor", "#B3C5FF"),
                bodyTextPrimaryColor = b.optString("bodyTextPrimaryColor", "#1A237E"),
                logoText = b.optString("logoText", ""),
                logoEmoji = b.optString("logoEmoji", "🏫"),
                logoUrl = b.optString("logoUrl", "")
            )
        }

        val plans = obj.getJSONArray("plans").let { arr ->
            (0 until arr.length()).map { parsePlan(arr.getJSONObject(it)) }
        }

        val testIds = obj.optJSONArray("testStudentIds")?.let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        } ?: emptyList()

        return School(
            id = obj.getString("id"),
            name = obj.getString("name"),
            shortName = obj.optString("shortName", obj.getString("name")),
            city = obj.optString("city", ""),
            state = obj.optString("state", ""),
            code = obj.optString("code", ""),
            contactEmail = obj.optString("contactEmail", ""),
            branding = branding,
            plans = plans,
            testStudentIds = testIds
        )
    }

    private fun parsePlan(obj: JSONObject): SchoolPlan {
        val features = obj.getJSONArray("features").let { arr ->
            (0 until arr.length()).map { arr.getString(it) }
        }
        return SchoolPlan(
            id = obj.getString("id"),
            name = obj.getString("name"),
            badge = obj.optString("badge", ""),
            priceINR = obj.optInt("priceINR", 0),
            duration = obj.optString("duration", ""),
            features = features
        )
    }

    private fun loadAppConfig(context: Context): AppConfig {
        return try {
            val json = context.assets.open("app_config.json")
                .bufferedReader().readText()
            val root = JSONObject(json)
            parseAppConfig(root)
        } catch (e: Exception) {
            e.printStackTrace()
            defaultAppConfig()
        }
    }

    private fun parseAppConfig(root: JSONObject): AppConfig {
        val theme = root.getJSONObject("defaultTheme").let { t ->
            ThemeConfig(
                primaryColor = t.optString("primaryColor", "#1565C0"),
                primaryDarkColor = t.optString("primaryDarkColor", "#003C8F"),
                primaryLightColor = t.optString("primaryLightColor", "#5E92F3"),
                accentColor = t.optString("accentColor", "#1A1A2E"),
                backgroundColor = t.optString("backgroundColor", "#EEF2FF"),
                headerTextColor = t.optString("headerTextColor", "#FFFFFF"),
                headerSubtextColor = t.optString("headerSubtextColor", "#B3C5FF"),
                bodyTextPrimaryColor = t.optString("bodyTextPrimaryColor", "#1A237E"),
                bodyTextSecondaryColor = t.optString("bodyTextSecondaryColor", "#546E7A"),
                cardBackgroundColor = t.optString("cardBackgroundColor", "#FFFFFF"),
                buttonPrimaryColor = t.optString("buttonPrimaryColor", "#1565C0"),
                buttonSecondaryColor = t.optString("buttonSecondaryColor", "#2E7D32"),
                buttonAccentColor = t.optString("buttonAccentColor", "#1A1A2E"),
                successColor = t.optString("successColor", "#2E7D32"),
                warningColor = t.optString("warningColor", "#F57F17")
            )
        }

        val features = root.optJSONObject("features")?.let { f ->
            FeaturesConfig(
               
                library = f.optBoolean("library", true),
                revision = f.optBoolean("revision", true),
                voiceChat = f.optBoolean("voiceChat", true),
                pdfAnalysis = f.optBoolean("pdfAnalysis", true),
                flashcards = f.optBoolean("flashcards", true),
                mockTests = f.optBoolean("mockTests", false),
                parentReports = f.optBoolean("parentReports", false)
            )
        } ?: FeaturesConfig(true, true, true, true, true, true, false)

        val greetings = mutableMapOf<String, String>()
        root.optJSONObject("greetingMessages")?.let { g ->
            g.keys().forEach { key -> greetings[key] = g.getString(key) }
        }

        val homeScreen = root.optJSONObject("homeScreen")?.let { h ->
            val quotes = h.optJSONArray("motivationalQuotes")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
            HomeScreenConfig(
                showSubjectGrid = h.optBoolean("showSubjectGrid", true),
                showLibraryButton = h.optBoolean("showLibraryButton", true),
        
                showAddSubjectButton = h.optBoolean("showAddSubjectButton", true),
                gridColumns = h.optInt("gridColumns", 2),
                motivationalQuotes = quotes
            )
        } ?: HomeScreenConfig(true, true, true, 2, emptyList())

        return AppConfig(
            version = root.optString("version", "1.0"),
            appName = root.optString("appName", "AI Guru"),
            tagline = root.optString("tagline", "Your Smart Study Companion"),
            supportEmail = root.optString("supportEmail", "support@aiguru.app"),
            appVersion = root.optString("appVersion", "1.0.0"),
            defaultTheme = theme,
            features = features,
            greetingMessages = greetings,
            homeScreen = homeScreen
        )
    }

    private fun defaultAppConfig() = AppConfig(
        version = "1.0",
        appName = "AI Guru",
        tagline = "Your Smart Study Companion",
        supportEmail = "support@aiguru.app",
        appVersion = "1.0.0",
        defaultTheme = ThemeConfig(
            primaryColor = "#1565C0", primaryDarkColor = "#003C8F",
            primaryLightColor = "#5E92F3", accentColor = "#1A1A2E",
            backgroundColor = "#EEF2FF", headerTextColor = "#FFFFFF",
            headerSubtextColor = "#B3C5FF", bodyTextPrimaryColor = "#1A237E",
            bodyTextSecondaryColor = "#546E7A", cardBackgroundColor = "#FFFFFF",
            buttonPrimaryColor = "#1565C0", buttonSecondaryColor = "#2E7D32",
            buttonAccentColor = "#1A1A2E", successColor = "#2E7D32",
            warningColor = "#F57F17"
        ),
        features = FeaturesConfig(true, true, true, true, true, true, false),
        greetingMessages = mapOf(
            "morning" to "Good morning! ☀️",
            "afternoon" to "Good afternoon! 👋",
            "evening" to "Good evening! 🌙"
        ),
        homeScreen = HomeScreenConfig(true, true, true, 2, emptyList())
    )
}
