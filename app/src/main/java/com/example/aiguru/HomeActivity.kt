package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class HomeActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var subjectsGrid: GridView
    private val subjectsList = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        db = FirebaseFirestore.getInstance()

        subjectsGrid = findViewById(R.id.subjectsGrid)
        val userNameText = findViewById<TextView>(R.id.userNameText)
        val addSubjectButton = findViewById<Button>(R.id.addSubjectButton)

        userNameText.text = "Student"

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, subjectsList)
        subjectsGrid.adapter = adapter

        loadSubjects()

        addSubjectButton.setOnClickListener {
            showAddSubjectDialog()
        }

        subjectsGrid.setOnItemClickListener { _, _, position, _ ->
            val subject = subjectsList[position]
            val intent = Intent(this, SubjectActivity::class.java)
            intent.putExtra("subjectName", subject)
            startActivity(intent)
        }

        subjectsGrid.setOnItemLongClickListener { _, _, position, _ ->
            val subject = subjectsList[position]
            AlertDialog.Builder(this)
                .setTitle("Delete Subject")
                .setMessage("Delete $subject?")
                .setPositiveButton("Delete") { _, _ ->
                    deleteSubject(subject)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
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
                adapter.notifyDataSetChanged()
            }
    }

    private fun showAddSubjectDialog() {
        val input = EditText(this)
        input.hint = "e.g. Science, Maths, History"
        AlertDialog.Builder(this)
            .setTitle("Add Subject")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addSubject(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addSubject(name: String) {
        val subject = hashMapOf("name" to name)
        db.collection("users").document("testuser123")
            .collection("subjects").document(name)
            .set(subject)
            .addOnSuccessListener {
                subjectsList.add(name)
                adapter.notifyDataSetChanged()
            }
    }

    private fun deleteSubject(name: String) {
        db.collection("users").document("testuser123")
            .collection("subjects").document(name)
            .delete()
            .addOnSuccessListener {
                subjectsList.remove(name)
                adapter.notifyDataSetChanged()
            }
    }
}