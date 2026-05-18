# School Campaign Implementation Plan
> Last updated: 2026-05-18
> Split: 60% Copilot | 40% Claude
> Prerequisite reading: meta/android_index.md, meta/server_index.md, meta/frontend_index.md

---

## Overview

Three tiers of work. Tier 1 = deal-closers (nothing else matters until these exist).
Tier 2 = demo wow moments. Tier 3 = renewal and retention.

**What already exists (don't rebuild):**
- `schools` Firestore collection + admin CRUD (`api/admin.py` lines 390–416)
- `school_tasks` collection + `FirestoreManager.saveTask()` / `loadTasksForSchool()` / `loadTasksByTeacher()`
- `student_school` + `teacher` roles with AccessGate.kt / PlanEnforcer.kt
- Teacher BB lessons in `bb_cache` (teacher_id, school_id, topic, steps_json)
- School branding: `SchoolTheme.kt` + `ConfigManager.kt` (primary color, logo URL, school name)
- 4-letter join code on every school doc
- Admin portal schools.js (super-admin only)

---

## TIER 1 — Deal Closers

### T1-A: Class Progress API + Teacher Dashboard (Android) + Grade/Section Drill-down

**Why:** A principal's first question is "Can I see if students are actually using it?" — and specifically "show me Grade 10-A, not the whole school."

#### Firestore schema — new collection
```
school_activity/{school_id}/student_stats/{uid}
  └── uid: String
  └── display_name: String
  └── grade: String
  └── section: String
  └── bb_sessions_total: Int        (increment on each BB lesson completed)
  └── chat_sessions_total: Int      (increment on each chat send)
  └── quiz_correct_total: Int
  └── quiz_attempted_total: Int
  └── last_active: Timestamp
  └── bb_topics_seen: List<String>  (capped at 50, for subject coverage)
  └── streak_days: Int
```

**When to write:** In `BlackboardActivity.showCompletionCard()` AND `FullChatFragment.proceedWithMessageSendAfterQuotaCheck()` — if user doc has `schoolId` field, write a batch update to this subcollection.

#### Server endpoint — NEW
```
GET /school/{school_id}/class-progress
  Auth: Firebase token; role must be teacher or school_admin
  Returns:
    {
      school_id, school_name, total_enrolled, active_last_7d, active_last_30d,
      students: [
        { uid, name, grade, section, bb_sessions, chat_sessions,
          quiz_accuracy_pct, last_active, streak_days }
      ],
      subject_coverage: { "Physics": 12, "Chemistry": 8, ... },
      generated_at: ISO timestamp
    }
  Logic: query school_activity/{school_id}/student_stats, aggregate
```

#### Android — NEW file: TeacherProgressActivity.kt
```
Location: app/src/main/java/com/aiguruapp/student/school/TeacherProgressActivity.kt
Entry point: HomeActivity — teacher role shows "Class Dashboard" card (already has teacher card area)

Screens:
  1. Overview tab — cards: Total Students, Active This Week, Avg BB Sessions, Top Subject
  2. Student List tab — RecyclerView; each row: avatar initial, name, grade/sec,
                         bb_sessions count, last_active "2d ago", colored dot (green/yellow/red)
  3. Student Detail — bottom sheet or new screen: all stats + topic list + quiz accuracy bar

API call: GET /school/{school_id}/class-progress via ServerProxyClient (new method)
Data class: ClassProgressResponse (Kotlin)
```

#### Android — Layout files needed
- `activity_teacher_progress.xml` — TabLayout + ViewPager2
- `fragment_teacher_overview.xml` — 4 stat cards + subject bar chart (simple View drawing)
- `fragment_teacher_student_list.xml` — RecyclerView
- `item_student_progress.xml` — row layout

**Note on grade/section breakdown:** School portal dashboard must have a Grade filter (dropdown: All / 6th / 7th … 12th) and Section filter (All / A / B / C). The `/school-portal/dashboard` endpoint already accepts `grade` and `section` query params — portal HTML just needs to wire up the filter dropdowns and re-fetch on change.

**Copilot does:** All Kotlin + XML + server endpoint + Firestore writes + portal filter dropdowns
**Claude does:** Nothing in T1-A

---

### T1-B: School Admin Web Portal

**Why:** Teacher can see student stats on the app. But the principal needs a web dashboard — no app install.

**Approach:** Standalone HTML page at `/school-portal` (separate from super-admin portal).
Auth: school_id + admin_pin (stored in `schools/{school_id}.admin_pin` — bcrypt hashed).

#### Server endpoints — NEW file: `api/school_portal.py`
```python
POST /school-portal/login
  Body: { school_id, admin_pin }
  Returns: { token: JWT(school_id, exp=8h) }   # separate JWT secret, NOT Firebase

GET /school-portal/dashboard
  Auth: Bearer JWT from above
  Returns: same shape as /school/{id}/class-progress + school branding fields

GET /school-portal/students/export.csv
  Auth: Bearer JWT
  Returns: CSV: Name, Grade, Section, BB Sessions, Chat Sessions, Quiz %, Last Active

PUT /school-portal/school/branding
  Auth: Bearer JWT
  Body: { primary_color, logo_url, school_name }   # principal can update their own branding
```

#### Web — NEW file: `server/app/static/school-portal/index.html`
Single-page, self-contained HTML (no framework dependency — matches existing admin style).
```
Sections:
  1. Login form (school_id + PIN)
  2. Dashboard: 4 stat cards + active-students-per-day bar chart (plain SVG/CSS bars)
  3. Student table: sortable by name/grade/sessions/last-active, search box
  4. Download CSV button
  5. Branding settings: color picker + logo URL input
```

**Copilot does:** `api/school_portal.py` (all Python endpoints, JWT logic, CSV generation)
**Claude does:** `school-portal/index.html` (full HTML/CSS/JS, single-file, matching admin portal color scheme but school-branded) — see Claude prompt at bottom of this doc

---

### T1-C: Privacy & Consent (DPDP Act compliance)

**Why:** Without a privacy policy, schools won't sign (legal team will block it).

#### Android — registration flow update
- In `RegisterActivity.kt` (or wherever registration happens): add a checkbox
  `"I agree to the Privacy Policy and give consent for my data to be processed for educational purposes"`
  Link opens a WebView to `/privacy-policy`
- Store `consent_given: true, consent_date: Timestamp` on user doc on registration

#### Server — NEW route
```
GET /privacy-policy   →  serves static HTML privacy policy page
```

#### Privacy policy content
**Claude does:** Draft the full privacy policy HTML (DPDP Act 2023 compliant, covers: data collected, purpose, retention, deletion rights, school as data processor). Also draft the school Data Processing Agreement (1-page PDF template).

---

## TIER 2 — Demo Wow Moments

### T2-A: School-Branded Welcome Flow (Android)

**Why:** When a student enters a school join code, they should feel the school's identity — shows principals it's "their app."

#### Flow
```
Registration / Profile → Enter School Code (existing) →
  Server validates code → returns school doc (name, primary_color, logo_url, grade_list) →
  SchoolWelcomeActivity launches (full-screen, school's primary color as background) →
    Shows: school logo (or initial avatar), "Welcome to [School Name]!", grade picker →
    "Let's go →" button → HomeActivity
```

#### Android — NEW file: `SchoolWelcomeActivity.kt`
```
Location: app/src/main/java/com/aiguruapp/student/school/SchoolWelcomeActivity.kt
Launched from: wherever school code is validated (search for SCHOOL_CODE in RegisterActivity / ProfileActivity)
Receives extras: SCHOOL_NAME, SCHOOL_COLOR, SCHOOL_LOGO_URL, GRADE_LIST (JSON array)
Layout: full-screen, school primary color bg, white text, logo at top, grade dropdown, CTA button
On confirm: calls FirestoreManager to save grade + school join, then startActivity(HomeActivity)
```

**Copilot does:** Kotlin + XML layout
**Claude does:** Nothing in T2-A

---

### T2-B: Teacher Assignment Feature

**Why:** Teachers want to push lessons to students. This is the killer retention feature for schools.

#### Flow
```
Teacher in BB session → completion card has new "Assign to Class" button →
  Bottom sheet: choose grade, section, set optional deadline, add note →
  Creates school_tasks entry (already has saveTask() in FirestoreManager) →

Student opens app → HomeActivity: new "Assigned" banner card above BB card →
  "Your teacher assigned: [topic] in [Subject]" →
  Tap → launches BlackboardActivity with that topic pre-filled →
  On BB completion → mark task as completed for this student
```

#### Firestore — extend school_tasks schema
```
school_tasks/{task_id}   (fields already exist: task_id, teacher_id, school_id, grade,
                          title, description, bb_topic, bb_cache_id, is_active)
ADD:
  section: String              # e.g. "A", "B", "All"
  deadline: Timestamp?
  teacher_note: String?
  completion_count: Int        # incremented when a student marks done
  assigned_at: Timestamp

student_task_completions/{uid}_{task_id}   (new subcollection under school_tasks or flat)
  task_id, uid, completed_at, bb_session_id
```

#### Android changes
- `BlackboardActivity.showCompletionCard()` — after BB done, check if this topic matches an active assigned task → if yes, show "Task Complete! Your teacher will see this." → call `FirestoreManager.markTaskComplete(uid, taskId)`
- `HomeActivity` — new `loadAssignedTasks()` on `onResume()` → if tasks exist, show assignment banner card (use existing `homeQuotaContainer` style but different color — amber/gold)
- `BlackboardActivity.showCompletionCard()` — add "Assign to Class" button (visible only if user role == teacher)
- New bottom sheet: `AssignTaskBottomSheet.kt`

#### Server — extend tasks.py
```
GET /tasks/assigned/{uid}    # returns active tasks for this student's school+grade+section
POST /tasks/{task_id}/complete    # uid, bb_session_id → writes student_task_completions
GET /tasks/teacher/{teacher_id}/progress    # returns each task + completion_count + student list
```

**Copilot does:** All Kotlin changes (HomeActivity, BlackboardActivity, AssignTaskBottomSheet), server endpoints, Firestore schema
**Claude does:** Nothing in T2-B

---

### T2-C: Parent Notification / Visibility (High Priority)

**Why:** Most students in schools using this app are minors. Parents hold veto power over whether the school adopts it. Giving parents *any* visibility — even just a weekly SMS — removes the "black box" fear.

#### What to build (minimal viable — no new app needed)

1. **Parent phone field** — add `parent_phone: String` optional field to user profile
   - `ProfileActivity.kt`: add "Parent's WhatsApp number" EditText (shown only for student accounts)
   - Store in `users_table/{uid}.parent_phone`

2. **Weekly SMS / WhatsApp message** — extend `server/scripts/weekly_school_report.py`
   - For each active student who has `parent_phone` set: send a short SMS/WhatsApp
   - Message: `"[Student Name] used AIGuru this week: [N] lessons, [M] chats. Top subject: [Subject]. Reply STOP to unsubscribe."`
   - Send via MSG91 / Twilio (same as existing OTP service — check `server/app/core/`)

3. **Parent opt-out** — `users_table/{uid}.parent_notifications_enabled: bool` (default true)
   - Settings screen shows toggle: "Send weekly report to parent"
   - STOP reply handler: set flag to false (webhook endpoint)

4. **School portal parent view** (stretch) — school portal `/school-portal/dashboard` response already has student list; add a `parent_notified_count` field showing how many parents received the weekly SMS

**Firestore changes:**
```
users_table/{uid}:
  ADD: parent_phone: String?         (E.164 format, e.g. "+919876543210")
  ADD: parent_notifications_enabled: Boolean = true
```

**Copilot does:** ProfileActivity field + Firestore writes + weekly script SMS loop + opt-out toggle in Settings
**Claude does:** Nothing in T2-C

---

### T2-D: Class Leaderboard (Optional / Toggle)

**Why:** Principals love showing this to parents. Students engage competitively.

#### Implementation
- Opt-in: teacher enables leaderboard for class in TeacherProgressActivity settings toggle
- When enabled: `HomeActivity` shows a "Class Leaderboard" card — top 3 students (anonymized: "Student #1, #2, #3") + logged-in user's rank
- Data source: `school_activity/{school_id}/student_stats` ordered by `bb_sessions_total` desc
- Privacy: names shown only to teacher view; student view shows anonymized ranks unless user opts in

**Copilot does:** Full implementation
**Claude does:** Nothing in T2-D

---

## TIER 3 — Renewal & Retention

### T3-A: Curriculum Mapping Document

**Why:** Principals need to justify adopting AIGuru to their teachers. They'll ask: "Which chapters does this cover?" A one-page map of school topics → AIGuru BB topics closes that objection.

**What to build:** Static PDF/HTML document (not a feature — a sales asset).
- Format: table per subject → Chapter name → AIGuru topic keyword → "Works best for" note
- Example row: Physics / Ch 5 Laws of Motion / "Newton's Laws, Force and Acceleration" / "Concepts + problem setup"
- Covers: Physics, Chemistry, Biology, Maths (NCERT 6–10)

**Copilot does:** Nothing (this is Claude's job)
**Claude does:** Full curriculum mapping table as an HTML page (`server/app/static/curriculum-map.html`) — serve it at `/curriculum-map` as a public route (no auth). Also add a Claude prompt at the bottom of this doc.

---

### T3-B: Weekly Progress Report Email

**Why:** Keeps teachers engaged without opening the app. Surfaces value passively.

#### Implementation
- Python script: `server/scripts/weekly_school_report.py`
- Runs via cron (Sunday 8pm) or Firebase Cloud Function trigger
- For each active school: fetch `school_activity/{id}/student_stats`, compute weekly delta (last_active >= 7 days ago), build email HTML
- Send via SendGrid / SMTP (same credentials as existing notification service)

**Copilot does:** Python script logic (Firestore queries, stat computation, email dispatch)
**Claude does:** Email HTML template (school-branded, shows: active students bar, top 5 performers, subject coverage pie, "View full dashboard" CTA button — clean, mobile-responsive)

---

### T3-C: Anti-Cheat / Responsible Use Policy

**Why:** First question from any teacher or principal: "Won't students just use this to cheat on homework?" Having a written policy + in-app messaging answers it before they ask.

**What to build:**
1. **Policy page** — `server/app/static/responsible-use.html` served at `/responsible-use` (public, no auth) — Claude writes this (see prompt below)
2. **In-app disclosure** — one-liner shown below the chat input in `FullChatFragment`: "AIGuru helps you learn, not answer tests. Use it to understand, not copy." (static TextView, no logic needed)
3. **Teacher controls** (stretch) — teacher can toggle `disable_direct_answers: bool` on school doc → server reads this flag and adds a system prompt suffix: "Do not give direct homework answers; guide the student to think instead."

**Copilot does:** In-app disclosure TextView in `FullChatFragment`; `disable_direct_answers` flag reading in `chat.py` system prompt builder
**Claude does:** `responsible-use.html` policy page + Claude prompt at bottom of this doc

---

### T3-D: Offline Lesson Download

**Why:** Schools often have poor WiFi. Teachers want students to download before class.

#### Flow
```
BB lesson completion card → "Download for Offline" button →
  Saves steps_json + images to local storage (Room DB or flat JSON file) →
  HomeActivity → "Saved Lessons" section shows downloaded lessons →
  Tap → launches BB player in offline mode (no server calls, uses local steps)
```

**Note:** `bb_watch_history` already saves history locally (see `appendLocalWatchHistory()` in BlackboardActivity). The download feature extends this to save full steps_json, not just metadata.

**Copilot does:** Full implementation (Room entity or JSON file storage, offline BB launch path)
**Claude does:** Nothing in T3-D

---

## Execution Order

```
Week 1:
  - T1-A server endpoint (class progress API)       ← Copilot
  - T1-A Firestore writes in BlackboardActivity      ← Copilot
  - T1-A TeacherProgressActivity (Android)           ← Copilot
  - T1-C privacy policy + consent checkbox           ← Copilot (Android) + Claude (legal text)

Week 2:
  - T1-B school portal Python endpoints              ← Copilot
  - T1-B school portal HTML page                     ← Claude
  - T2-A SchoolWelcomeActivity                       ← Copilot

Week 3:
  - T2-B teacher assignment bottom sheet             ← Copilot
  - T2-B task completion tracking                    ← Copilot
  - T2-C parent phone field + opt-out toggle         ← Copilot
  - T3-C anti-cheat: in-app disclosure TextView      ← Copilot
  - Grade/section filter in school portal HTML       ← Copilot

Week 4:
  - T2-D class leaderboard                           ← Copilot
  - T3-A curriculum map HTML                         ← Claude
  - T3-B weekly email script (+ parent SMS)          ← Copilot + Claude (template)
  - T3-C responsible-use.html                        ← Claude
  - T3-D offline download                            ← Copilot
```

---

## Claude Prompts — Ready to Use

### Claude Prompt 1: School Admin Web Portal HTML

```
You are building a single-file school admin portal for a mobile learning app called AIGuru.
The file is: server/app/static/school-portal/index.html

Requirements:
- Pure HTML + vanilla JS + inline CSS (no React, no Vue, no CDN links except for one font)
- Google Font: Inter (import from Google Fonts)
- Color palette: primary=#1A1A2E (navy), accent=#1E9B6B (green), surface=#FFFFFF, bg=#F5F7FA,
  text=#1A1A2E, secondary=#666B8A, divider=#E0E4F0
- Must work standalone (no other JS files needed)

Pages/screens (SPA with JS section switching):
1. LOGIN SCREEN
   - School ID input + PIN input (6-digit, number)
   - "View Dashboard" button
   - POST to /school-portal/login → store JWT in sessionStorage
   - On success → show DASHBOARD

2. DASHBOARD SCREEN
   - Top nav: "AIGuru School Portal", school name (from JWT/API), logout link
   - 4 stat cards: Total Students | Active This Week | Avg BB Sessions | Top Subject
   - Simple bar chart (pure CSS, no canvas): active students per day for last 7 days
     (array of {day, count} from API, rendered as flex divs with height proportional to count)
   - Student table: Name | Grade | Section | BB Sessions | Chat | Quiz % | Last Active | Status dot
     - Status dot: green=active <3 days, yellow=3–7 days, red=>7 days
     - Sortable by clicking column header
     - Search input to filter rows
   - "Download CSV" button → GET /school-portal/students/export.csv

3. BRANDING SETTINGS (tab or section)
   - School Name input
   - Primary Color (color picker input)
   - Logo URL input + preview img
   - Save button → PUT /school-portal/school/branding

API base: /school-portal
JWT stored in sessionStorage key: "school_portal_token"
All API calls attach: Authorization: Bearer {token}
On 401: clear token, show login screen

Match the clean, minimal style of the existing admin portal (no shadows, flat cards with 1px borders).
Return only the complete HTML file content, no explanation.
```

---

### Claude Prompt 2: Privacy Policy HTML Page

```
Write a complete HTML page for the privacy policy of "AIGuru" — an AI-powered educational
app for school students in India.

Requirements:
- Standalone HTML (inline CSS, no external deps)
- Compliant with India's DPDP Act 2023 (Digital Personal Data Protection Act)
- Sections required:
  1. What data we collect (name, email, grade, usage activity, device ID)
  2. Why we collect it (personalized learning, quota tracking, school reporting to teachers)
  3. Who can see your data (only the student, their school teachers/principal, and AIGuru staff)
  4. Data retention (active account: indefinitely; deleted account: 30 days purge)
  5. Student/parent rights (right to access, correct, and delete data — email: privacy@aiguruapp.com)
  6. School as Data Fiduciary (school agrees to our data processing terms before using school features)
  7. Children's data (students under 18 require parent/guardian consent at registration)
  8. Contact us

Style: clean white page, Inter font, navy headings (#1A1A2E), readable 16px body text.
Return only the HTML file content.
```

---

### Claude Prompt 3: Weekly Progress Email HTML Template

```
Write an HTML email template for a weekly school progress report for "AIGuru".
The template uses inline CSS only (for email client compatibility).

Dynamic placeholders (use {{variable}} syntax):
  {{school_name}}, {{week_start}}, {{week_end}},
  {{active_students}}, {{total_enrolled}}, {{bb_sessions_this_week}},
  {{top_subject}}, {{top_student_name}}, {{top_student_sessions}},
  {{dashboard_url}}

Sections:
1. Header: AIGuru logo text + "Weekly Class Report" + school name + date range
2. Key numbers row (inline-block cells): Active Students | BB Sessions | Avg Sessions/Student
3. Top Subject this week (badge/pill with subject name)
4. Top Performer: name + sessions count + "Keep it up!" 
5. Simple progress bar for "Class Activity" (percentage = active/total*100, rendered as
   a table with background-color bar — email-safe approach)
6. Footer: "View full dashboard →" button (navy background, white text, links to {{dashboard_url}})
           + "Manage notification preferences" link

Keep it professional, school-appropriate, and mobile-responsive (max-width 600px).
Return only the HTML template content.
```

---

### Claude Prompt 5: Curriculum Mapping HTML Page

```
You are creating a curriculum mapping page for "AIGuru" — an AI-powered Blackboard learning app
for NCERT students in India (grades 6–12).

File: server/app/static/curriculum-map.html
Served at: /curriculum-map (public page, no auth)

Create a clean, printable HTML page with:
- AIGuru header (navy #1A1A2E, white text)
- Intro: "This map shows how AIGuru's Blackboard Mode covers NCERT chapters.
  Teachers can enter any topic from this list to launch an instant AI lesson."
- One section per subject (Physics, Chemistry, Biology, Mathematics)
  Each section: table with columns: Class | Chapter | Topic to type in AIGuru | Learning outcome
  Cover classes 8–10 for Science, 8–10 for Maths. At least 6 rows per subject.
- Footer: "All topics are generated dynamically by AI — this list is indicative, not exhaustive."

Style: white background, Inter font, navy headings, 1px border table, alternating row shading.
Return only the HTML file content.
```

---

### Claude Prompt 6: Responsible Use Policy HTML Page

```
Write a Responsible Use Policy page for "AIGuru" — an AI learning assistant used by school
students in India.

File: server/app/static/responsible-use.html
Served at: /responsible-use (public)

Sections:
1. What AIGuru is for — helping students understand concepts, not providing homework answers
2. What it should NOT be used for — copying answers, cheating in exams, submitting AI text as own work
3. How teachers can see usage — school teachers can view session counts and topics covered
4. Student responsibilities — engage genuinely, ask follow-up questions, verify AI explanations
5. Parent guidance — monitor usage, encourage discussion of topics covered
6. Consequences — schools may revoke access for misuse; AIGuru may flag unusual usage patterns
7. Contact — responsible-use@aiguruapp.com

Tone: friendly, not threatening. Aimed at students ages 12–18.
Style: white page, Inter font, navy headings, clean layout matching privacy policy style.
Return only the HTML file content.
```

---

### Claude Prompt 4: School Data Processing Agreement Template

```
Write a 1-page School Data Processing Agreement (DPA) for "AIGuru" (operated by [Company Name]).

This agreement is between:
- AIGuru ("Data Processor")
- The School ("Data Fiduciary / Institution")

Cover:
1. Purpose: processing student activity data for personalized learning and class progress reporting
2. Data categories: names, grades, app usage activity, quiz scores (no biometric, no financial data)
3. Retention: data deleted within 30 days of school contract termination
4. Sub-processors: Firebase (Google), LiteLLM (AI routing) — both comply with GDPR/SOC2
5. Security: TLS in transit, Firebase security rules, Firebase Auth token-gated API
6. School obligations: obtain parent/guardian consent for students under 18
7. AIGuru obligations: not sell data, not use for advertising, provide data export on request
8. Governing law: India, DPDP Act 2023
9. Signature blocks

Format as a clean business document. Use plain English, not legal jargon.
```

---

## Key File Map (where to make changes)

| Task | Files to touch |
|------|---------------|
| T1-A server | `server/app/api/` → new `school_progress.py` |
| T1-A Android writes | `BlackboardActivity.kt` ~showCompletionCard() line ~2769; `FullChatFragment.kt` ~proceedWithMessageSendAfterQuotaCheck() line ~1366 |
| T1-A Android screen | NEW `school/TeacherProgressActivity.kt`, `activity_teacher_progress.xml`, `fragment_teacher_overview.xml`, `fragment_teacher_student_list.xml`, `item_student_progress.xml` |
| T1-B server | NEW `api/school_portal.py` (JWT login + dashboard + CSV + branding) |
| T1-B web | NEW `static/school-portal/index.html` ← Claude |
| T1-C Android | `RegisterActivity.kt` or `OnboardingActivity.kt` (consent checkbox) |
| T1-C server | `main.py` → `app.get("/privacy-policy")` static route |
| T2-A Android | NEW `school/SchoolWelcomeActivity.kt` + `activity_school_welcome.xml` |
| T2-B Android | `BlackboardActivity.kt` ~showCompletionCard() — add assign button; NEW `school/AssignTaskBottomSheet.kt`; `HomeActivity.kt` ~onResume() — add task banner |
| T2-B server | Extend `api/tasks.py`: add `/tasks/assigned/{uid}`, `/tasks/{id}/complete` |
| T2-C parent | `SignupActivity.kt` + `ProfileActivity.kt` → `parent_phone` field; Settings toggle `parent_notifications_enabled` |
| T2-C script | Extend `server/scripts/weekly_school_report.py` → parent SMS/WhatsApp loop |
| T3-A curriculum | NEW `static/curriculum-map.html` ← Claude; `main.py` → `/curriculum-map` public route |
| T3-B weekly email | NEW `server/scripts/weekly_school_report.py` |
| T3-C anti-cheat | `FullChatFragment.kt` → static disclaimer TextView; `chat.py` → `disable_direct_answers` flag in system prompt; NEW `static/responsible-use.html` ← Claude |
| T3-D offline | Extend `bb_watch_history` local storage + offline launch path in `BlackboardActivity.kt` |

---

## Notes

- **Never touch** `build/`, `.gradle/`, `__pycache__`, `svg_builder_original_backup.py`
- **Expensive files** — read slices only: `BlackboardActivity.kt`, `FullChatFragment.kt`, `HomeActivity.kt`, `FirestoreManager.kt`
- All new server endpoints go in separate files, imported in `main.py` via `app.include_router()`
- All new Android activities extend `AppCompatActivity`, use `@color/colorPrimary` for theme
- Firestore composite index trap: use single-field filter + in-memory sort (see FirestoreManager pattern)
- School portal JWT secret must be different from Firebase; store in env var `SCHOOL_PORTAL_JWT_SECRET`
