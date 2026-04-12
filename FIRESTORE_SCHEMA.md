# Firestore Schema – AI Guru

## Collection: `users`

```
users/{user_id}
├── selected_chapter_ids: string[]
├── total_score: number
├── quizzes_attempted: number
├── accuracy: number           // 0–100 running average
├── streak: number             // consecutive quiz days
├── last_quiz_date: string     // ISO date "YYYY-MM-DD"
├── plan: string               // "free" | "premium" | "pro"
│
├── chat_questions_today: number   // questions asked today (resets each UTC day)
├── bb_sessions_today: number      // blackboard sessions today (resets each UTC day)
├── questions_updated_at: number   // epoch-ms of last question counter update
│
└── chapter_progress/{chapter_id}
    ├── subject_id: string
    ├── completed_questions: number
    ├── total_questions: number
    ├── accuracy: number       // last-attempt accuracy for this chapter
    └── last_accessed_at: timestamp
```

---

## Collection: `subjects`

```
subjects/{subject_id}
├── name: string               // e.g. "Mathematics"
├── icon_url: string (optional)
├── grade: string (optional)   // e.g. "9th"
├── description: string (optional)
└── chapter_count: number
```

**Example document (subjects/math_9th):**
```json
{
  "name": "Mathematics",
  "grade": "9th",
  "icon_url": "https://cdn.example.com/icons/math.png",
  "description": "9th standard mathematics syllabus",
  "chapter_count": 15
}
```

---

## Collection: `chapters`

```
chapters/{chapter_id}
├── subject_id: string         // references subjects/{id}
├── title: string              // e.g. "Number Systems"
├── order: number              // display order (1, 2, 3…)
├── description: string (optional)
├── topic_tags: string[]       // e.g. ["algebra", "fractions"]
└── estimated_minutes: number (optional)
```

**Example document (chapters/ch_number_systems):**
```json
{
  "subject_id": "math_9th",
  "title": "Number Systems",
  "order": 1,
  "description": "Rational and irrational numbers, real number line",
  "topic_tags": ["numbers", "rational", "irrational"],
  "estimated_minutes": 30
}
```

---

## Collection: `quizzes`

```
quizzes/{quiz_id}
├── subject: string
├── chapter_id: string
├── chapter_title: string
├── difficulty: "easy" | "medium" | "hard"
├── mode: "normal" | "challenge"
├── question_count: number
├── created_at: timestamp (optional)
└── questions: array
    └── each item is one of:
        ┌─ MCQ ──────────────────────────────────────
        │  type: "mcq"
        │  id: string
        │  question: string
        │  options: string[]        // 2–4 items
        │  correct_answer: string
        │  explanation: string
        │
        ├─ Fill-blank Typed ─────────────────────────
        │  type: "fill_blank_typed"
        │  id: string
        │  question: string         // uses ___ for blanks
        │  correct_answers: string[]
        │  hints: string[]
        │  explanation: string
        │
        ├─ Fill-blank Drag ──────────────────────────
        │  type: "fill_blank_drag"
        │  id: string
        │  question: string         // uses ___ for blanks
        │  blanks_count: number
        │  correct_answers: string[]
        │  draggable_options: string[]
        │  explanation: string
        │
        └─ Short Answer ─────────────────────────────
           type: "short_answer"
           id: string
           question: string
           expected_keywords: string[]
           sample_answer: string
           explanation: string
```

---

## Collection: `attempts`

```
attempts/{attempt_id}
├── user_id: string
├── quiz_id: string
├── mode: "normal" | "challenge"
├── difficulty: "easy" | "medium" | "hard"
├── total_score: number
├── correct_count: number
├── wrong_count: number
├── accuracy: number           // 0–100 %
├── time_taken_seconds: number
├── created_at: string         // ISO-8601 datetime
├── strong_topics: string[]    // chapter titles where accuracy ≥ 70%
├── weak_topics: string[]      // chapter titles where accuracy < 50%
└── question_results: array
    └── each item:
        ├── question_id: string
        ├── question_type: string
        ├── user_answer: string | string[]
        ├── correct_answer: string | string[]
        ├── is_correct: boolean
        ├── score_delta: number  // +1, 0, or -1
        ├── feedback: string
        └── improved_answer: string
```

---

## Recommended Indexes (composite)

| Collection | Fields | Order |
|------------|--------|-------|
| `chapters` | `subject_id` ASC, `order` ASC | needed for chapter listing |
| `attempts` | `user_id` ASC, `created_at` DESC | needed for attempt history |

---

## Security Rules (Firestore)

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {

    // Users can only read/write their own document
    match /users/{userId} {
      allow read, write: if request.auth != null && request.auth.uid == userId;

      match /chapter_progress/{chapterId} {
        allow read, write: if request.auth != null && request.auth.uid == userId;
      }
    }

    // Subjects and chapters are public read, admin write
    match /subjects/{subjectId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.token.admin == true;
    }

    match /chapters/{chapterId} {
      allow read: if true;
      allow write: if request.auth != null && request.auth.token.admin == true;
    }

    // Quizzes: authenticated read, server write only
    match /quizzes/{quizId} {
      allow read: if request.auth != null;
      allow write: if false; // backend service account only
    }

    // Attempts: owner read, server write only
    match /attempts/{attemptId} {
      allow read: if request.auth != null && request.auth.uid == resource.data.user_id;
      allow write: if false; // backend service account only
    }
  }
}
```
