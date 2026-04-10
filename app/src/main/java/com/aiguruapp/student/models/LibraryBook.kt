package com.aiguruapp.student.models

import java.io.Serializable

data class LibraryBook(
    val title: String,       // filename without .pdf, e.g. "iemh101"
    val grade: String,       // folder name, e.g. "9thclass"
    val subject: String,     // folder name, e.g. "maths"
    val assetPath: String,   // full asset path, e.g. "library/9thclass/maths/iemh101.pdf"
    val pdfId: String        // unique cache key, e.g. "9thclass_maths_iemh101"
) : Serializable
