package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.SubjectAdapter
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
        setContentView(R.layout.activity_home)

        db = FirebaseFirestore.getInstance()

        setupGreeting()
        setupRecyclerView()
        loadSubjects()

        findViewById<MaterialButton>(R.id.addSubjectButton).setOnClickListener {
            showAddSubjectDialog()
        }
        findViewById<MaterialButton>(R.id.libraryButton).setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.realTeacherButton).setOnClickListener {
            startActivity(Intent(this, RealTeacherActivity::class.java))
        }
    }

    private fun setupGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val greeting = when {
            hour < 12 -> "Good morning! ☀️"
            hour < 17 -> "Good afternoon! 👋"
            else -> "Good evening! 🌙"
        }
        findViewById<TextView>(R.id.greetingText).text = greeting
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

    private fun loadSubjects() {
        db.collection("users").document("testuser123")
            .collection("subjects")
            .get()
            .addOnSuccessListener { documents ->
                subjectsList.clear()
                for (doc in documents) {
                    subjectsList.add(doc.getString("name") ?: "")
                }
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
        db.collection("users").document("testuser123")
            .collection("subjects").document(name)
            .set(hashMapOf("name" to name))
            .addOnSuccessListener {
                subjectsList.add(name)
                subjectAdapter.notifyItemInserted(subjectsList.size - 1)
                updateSubjectCount()
            }
    }

    private fun deleteSubject(name: String) {
        db.collection("users").document("testuser123")
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