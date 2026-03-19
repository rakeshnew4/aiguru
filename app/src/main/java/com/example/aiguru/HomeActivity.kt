package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.SubjectAdapter
import com.example.aiguru.utils.ConfigManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar

class HomeActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var subjectsRecyclerView: RecyclerView
    private val subjectsList = mutableListOf<String>()
    private lateinit var subjectAdapter: SubjectAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Redirect to login if no session
        if (!SessionManager.isLoggedIn(this)) {
            startActivity(Intent(this, SchoolLoginActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_home)

        db = FirebaseFirestore.getInstance()

        applySchoolBranding()
        setupGreeting()
        setupStudentInfo()
        setupRecyclerView()
        loadSubjects()

        findViewById<MaterialButton>(R.id.addSubjectButton).setOnClickListener {
            showAddSubjectDialog()
        }
        findViewById<MaterialButton>(R.id.libraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.progressButton).setOnClickListener {
            startActivity(Intent(this, ProgressDashboardActivity::class.java))
        }
//        findViewById<MaterialButton>(R.id.realTeacherButton).setOnClickListener {
//            startActivity(Intent(this, RealTeacherActivity::class.java))
//        }
        // Profile / logout button
        findViewById<TextView?>(R.id.profileButton)?.setOnClickListener {
            showProfileDialog()
        }
    }

    private fun applySchoolBranding() {
        val schoolId = SessionManager.getSchoolId(this)
        val school = ConfigManager.getSchool(this, schoolId)
        val branding = school?.branding

        runCatching {
            val primaryColor = Color.parseColor(branding?.primaryColor ?: "#1565C0")
            val accentColor = Color.parseColor(branding?.accentColor ?: "#FF8F00")

            // Header background
            findViewById<LinearLayout?>(R.id.homeHeader)?.setBackgroundColor(primaryColor)

            // Buttons
            findViewById<MaterialButton?>(R.id.libraryButton)?.backgroundTintList =
                ColorStateList.valueOf(primaryColor)
            findViewById<MaterialButton?>(R.id.realTeacherButton)?.backgroundTintList =
                ColorStateList.valueOf(Color.parseColor(branding?.primaryColor?.let {
                    // Slightly dark variant
                    it
                } ?: "#2E7D32"))
            findViewById<MaterialButton?>(R.id.addSubjectButton)?.backgroundTintList =
                ColorStateList.valueOf(accentColor)

            // Header subtext color
            val subtextColor = Color.parseColor(branding?.headerSubtextColor ?: "#B3C5FF")
            findViewById<TextView?>(R.id.greetingText)?.setTextColor(subtextColor)
        }
    }

    private fun setupStudentInfo() {
        val studentName = SessionManager.getStudentName(this)
        val schoolName = SessionManager.getSchoolName(this)
        val planName = SessionManager.getPlanName(this)

        findViewById<TextView?>(R.id.userNameText)?.text = studentName
        // Show school name as subtitle if view exists
        findViewById<TextView?>(R.id.schoolNameSubtitle)?.text = schoolName
        // Show plan badge if view exists
        if (planName.isNotBlank()) {
            findViewById<TextView?>(R.id.planBadgeText)?.apply {
                text = "📋 $planName"
                visibility = android.view.View.VISIBLE
            }
        }
    }

    private fun setupGreeting() {
        val config = ConfigManager.getAppConfig(this)
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> config.greetingMessages["morning"] ?: "Good morning! ☀️"
            hour < 17 -> config.greetingMessages["afternoon"] ?: "Good afternoon! 👋"
            else -> config.greetingMessages["evening"] ?: "Good evening! 🌙"
        }
        findViewById<TextView>(R.id.greetingText).text = greeting
    }

    private fun showProfileDialog() {
        val studentName = SessionManager.getStudentName(this)
        val studentId = SessionManager.getStudentId(this)
        val schoolName = SessionManager.getSchoolName(this)
        val planName = SessionManager.getPlanName(this).ifBlank { "No plan selected" }

        AlertDialog.Builder(this)
            .setTitle("👤 $studentName")
            .setMessage(
                "School: $schoolName\n" +
                "Student ID: $studentId\n" +
                "Plan: $planName"
            )
            .setPositiveButton("Change Plan") { _, _ ->
                startActivity(
                    Intent(this, SubscriptionActivity::class.java)
                        .putExtra("schoolId", SessionManager.getSchoolId(this))
                )
            }
            .setNeutralButton("Logout") { _, _ -> confirmLogout() }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                SessionManager.logout(this)
                startActivity(Intent(this, SchoolLoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupRecyclerView() {
        subjectsRecyclerView = findViewById(R.id.subjectsRecyclerView)
        subjectAdapter = SubjectAdapter(
            subjects = subjectsList,
            onItemClick = { subject ->
                startActivity(
                    Intent(this, SubjectActivity::class.java)
                        .putExtra("subjectName", subject)
                )
            },
            onItemLongClick = { subject -> showDeleteSubjectDialog(subject) }
        )
        subjectsRecyclerView.layoutManager = GridLayoutManager(this, 2)
        subjectsRecyclerView.adapter = subjectAdapter
    }

    private val defaultSubjects = listOf(
        "Mathematics", "Science", "Computer", "English", "History", "Geography"
    )

    private fun loadSubjects() {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects")
            .get()
            .addOnSuccessListener { documents ->
                subjectsList.clear()
                for (doc in documents) {
                    subjectsList.add(doc.getString("name") ?: "")
                }
                if (subjectsList.isEmpty()) {
                    seedDefaultSubjects(userId)
                } else {
                    subjectAdapter.notifyDataSetChanged()
                    updateSubjectCount()
                }
            }
    }

    private fun seedDefaultSubjects(userId: String) {
        val batch = db.batch()
        val colRef = db.collection("users").document(userId).collection("subjects")
        defaultSubjects.forEach { name ->
            batch.set(colRef.document(name), hashMapOf("name" to name))
        }
        batch.commit().addOnSuccessListener {
            subjectsList.addAll(defaultSubjects)
            subjectAdapter.notifyDataSetChanged()
            updateSubjectCount()
        }
    }

    private fun updateSubjectCount() {
        val count = subjectsList.size
        val text = if (count == 1) "1 subject" else "$count subjects"
        findViewById<TextView?>(R.id.subjectCountText)?.text = text
    }

    private fun showAddSubjectDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Science, Maths, History"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("📚 Add Subject")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addSubject(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteSubjectDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Subject")
            .setMessage("Delete \"$name\" and all its chapters?")
            .setPositiveButton("Delete") { _, _ -> deleteSubject(name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSubject(name: String) {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(name)
            .set(hashMapOf("name" to name))
            .addOnSuccessListener {
                subjectsList.add(name)
                subjectAdapter.notifyItemInserted(subjectsList.size - 1)
                updateSubjectCount()
            }
    }

    private fun deleteSubject(name: String) {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(name)
            .delete()
            .addOnSuccessListener {
                val idx = subjectsList.indexOf(name)
                if (idx >= 0) {
                    subjectsList.removeAt(idx)
                    subjectAdapter.notifyItemRemoved(idx)
                    updateSubjectCount()
                }
            }
    }
}