# Home Screen Redesign Plan
**Goal**: More polished, engaging, consistent UI. Pick one theme, reduce density, replace emoji icons, fix layout gaps.

---

## Phase 1 — Quick Wins (no design assets needed)

### 1.1 Fix subjects RecyclerView background seam
**File**: `app/src/main/res/layout/activity_home.xml`
- Find `subjectsRecyclerView`
- Change `android:background="#FFFFFF"` → `android:background="@color/colorBackground"`

### 1.2 Unhide streak badge in hero header
**File**: `app/src/main/res/layout/activity_home.xml`
- Find `streakBadgeText`
- Remove `android:visibility="gone"` (or change to `visible`)
- **Note**: The Kotlin already sets it in `setupStudentInfo()` — just make it default-visible so it shows even before load

### 1.3 Increase secondary card height
**File**: `app/src/main/res/layout/activity_home.xml`
- `quickActionChatBtn` and `quickActionTasksBtn`: change `android:layout_height="60dp"` → `android:layout_height="72dp"`

### 1.4 Fix "My Sessions" subtitle inconsistency
**File**: `app/src/main/res/layout/activity_home.xml`
- Inside `quickActionTasksBtn` card, find `android:text="Favourites &amp; saved"` → `android:text="History &amp; saved"`

### 1.5 Add personalized greeting in hero header
**File**: `app/src/main/res/layout/activity_home.xml`
- Find `greetingText` (currently `0dp` hidden stub)
- Change to: `android:layout_width="wrap_content"`, `android:layout_height="wrap_content"`, `android:visibility="visible"`
- Set `android:textSize="13sp"`, `android:textColor="#FFFFFFBB"`, `android:layout_marginTop="2dp"`

**File**: `app/src/main/java/com/aiguruapp/student/HomeActivity.kt`
- In `setupStudentInfo()` find where `userNameText.text = name` is set
- Add below it:
```kotlin
val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
val greeting = when {
    hour < 12 -> "Good morning"
    hour < 17 -> "Good afternoon"
    else -> "Good evening"
}
greetingText.text = "$greeting 👋"
greetingText.visibility = View.VISIBLE
```

### 1.6 Wire profile button in top bar
**File**: `app/src/main/res/layout/activity_home.xml`
- Find `profileButton` (currently `0dp x 0dp`, `visibility="gone"`)
- Change: `android:layout_width="36dp"`, `android:layout_height="36dp"`, remove `visibility="gone"`, text = first letter of name (handled in Kotlin)
- Set `android:textSize="15sp"`, `android:background="@drawable/bg_avatar_circle"`, `android:textColor="#FFFFFF"`, `android:gravity="center"`

**File**: `HomeActivity.kt` — in `setupStudentInfo()`:
```kotlin
profileButton.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "P"
profileButton.visibility = View.VISIBLE
profileButton.setOnClickListener { startActivity(Intent(this, UserProfileActivity::class.java)) }
```

---

## Phase 2 — Theme Unification (full dark mode)

**Decision: Go full dark.** The BB screen and app core are already dark; the light body is the odd one out.

### 2.1 Update color values
**File**: `app/src/main/res/values/colors.xml`

Change:
```xml
<!-- Before -->
<color name="colorBackground">#F5F7FA</color>
<color name="colorSurface">#FFFFFF</color>
<color name="colorSurface2">#F9FAFB</color>
<color name="colorTextPrimary">#1A1A2E</color>
<color name="colorTextSecondary">#666B8A</color>
<color name="colorDivider">#E0E4F0</color>

<!-- After -->
<color name="colorBackground">#0D0D1F</color>
<color name="colorSurface">#161628</color>
<color name="colorSurface2">#1E1E35</color>
<color name="colorTextPrimary">#EEEEF5</color>
<color name="colorTextSecondary">#8888AA</color>
<color name="colorDivider">#2A2A45</color>
```

### 2.2 Update drawer background
**File**: `app/src/main/res/layout/activity_home.xml`
- `navDrawer` LinearLayout: `android:background="@color/colorSurface"` — already uses the token, will auto-update

### 2.3 Update subjects RecyclerView card background
- The `SubjectCardAdapter` item layout will also need `@color/colorSurface` instead of any hard-coded whites
- **File**: search for `subject_card.xml` or `item_subject.xml` in `res/layout/`
- Change any `#FFFFFF` or `android:background="@color/colorSurface"` to `@color/colorSurface2` for card items

### 2.4 Update "Ask AI" card color
**File**: `app/src/main/res/layout/activity_home.xml`
- `quickActionChatBtn`: change `app:cardBackgroundColor="#1565C0"` → `app:cardBackgroundColor="#1A2E6E"` (darker, less jarring on dark bg)

### 2.5 Adjust status bar to match
**File**: `HomeActivity.kt` — in `onCreate()`:
```kotlin
window.statusBarColor = android.graphics.Color.parseColor("#0D0D1F")
WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
```

---

## Phase 3 — Replace Emoji Icons with Vector Icons

### 3.1 Generate icons
Use the prompts below with any AI image/vector generator (Adobe Firefly, Iconify, or hand-draw in Figma). Export as SVG, then convert to Android VectorDrawable using **Android Studio's SVG importer** (`File > New > Vector Asset > Local file`).

Target: 24x24dp, single color `#EEEEF5` (light), stroke weight ~1.8px, no fill, rounded line caps.

| Icon name (file) | Prompt |
|---|---|
| `ic_nav_profile.xml` | Flat minimal outline icon of a person silhouette, circular head, shoulder arc below, single line stroke 1.8px, no fill, white on transparent, 24x24 |
| `ic_nav_progress.xml` | Flat minimal outline bar chart, three bars increasing left to right with upward arrow above rightmost bar, single stroke 1.8px, no fill, white |
| `ic_nav_history.xml` | Flat minimal outline clock face with a small triangular play button in the center instead of hands, single stroke 1.8px, no fill, white |
| `ic_nav_friends.xml` | Flat minimal outline two person silhouettes side by side, slightly overlapping shoulders, single stroke 1.8px, no fill, white |
| `ic_nav_share_earn.xml` | Flat minimal outline gift box with a ribbon bow on top, clean rectangular box, single stroke 1.8px, no fill, white |
| `ic_nav_teacher.xml` | Flat minimal outline person standing at a rectangular board/screen, small star or sparkle on the board, single stroke 1.8px, no fill, white |
| `ic_nav_tasks.xml` | Flat minimal outline clipboard rectangle with three horizontal check lines, single stroke 1.8px, no fill, white |
| `ic_nav_school.xml` | Flat minimal outline school building: rectangular base, triangular roof, small arch door, single stroke 1.8px, no fill, white |
| `ic_nav_plans.xml` | Flat minimal outline credit card rectangle with two horizontal lines and a small chip square, single stroke 1.8px, no fill, white |
| `ic_nav_signout.xml` | Flat minimal outline door with an arrow pointing right/outward through the doorframe, single stroke 1.8px, no fill, white |
| `ic_bb_hero.xml` | Flat vector icon of a blackboard/chalkboard easel with a small AI sparkle (three lines radiating from a star), bold stroke 2px, white on transparent, 30x30 |

### 3.2 Replace emoji in drawer XML
**File**: `app/src/main/res/layout/activity_home.xml`

For each drawer item, replace:
```xml
<!-- Before -->
<TextView
    android:layout_width="32dp"
    android:layout_height="wrap_content"
    android:text="👤"
    android:textSize="18sp"
    android:gravity="center" />

<!-- After -->
<ImageView
    android:layout_width="22dp"
    android:layout_height="22dp"
    android:layout_gravity="center"
    android:src="@drawable/ic_nav_profile"
    android:tint="#AAAACC"
    android:contentDescription="Profile" />
```

Apply this pattern to all 10 drawer items using their respective drawable names.

### 3.3 Replace blackboard hero icon in BB card
**File**: `app/src/main/res/layout/activity_home.xml`
- Find `android:src="@drawable/blackboard_icon"` in `quickActionBbBtn`
- Change to `android:src="@drawable/ic_bb_hero"` once generated
- Or keep existing if `blackboard_icon.xml` is already a good vector — check its quality first

---

## Phase 4 — BB Hero Card Simplification

### 4.1 Move quota strip out of the BB card
**File**: `app/src/main/res/layout/activity_home.xml`

- Inside `quickActionBbBtn` card, find `homeQuotaContainer` LinearLayout (currently `visibility="gone"`)
- **Delete** it from inside the card
- **Add** a new slim quota row (36dp) directly below the closing `</com.google.android.material.card.MaterialCardView>` of `quickActionBbBtn`:

```xml
<!-- Slim quota strip — sits below BB card, shown when quota loads -->
<LinearLayout
    android:id="@+id/homeQuotaContainer"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:gravity="center_vertical"
    android:paddingStart="4dp"
    android:paddingEnd="4dp"
    android:paddingTop="6dp"
    android:paddingBottom="2dp"
    android:visibility="gone">

    <TextView
        android:layout_width="32dp"
        android:layout_height="wrap_content"
        android:text="🎓"
        android:textSize="13sp"
        android:gravity="center" />

    <TextView
        android:id="@+id/homeQuotaBbLeftText"
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:textSize="12sp"
        android:textStyle="bold"
        android:textColor="#AAFFCC" />

    <ProgressBar
        android:id="@+id/homeQuotaBbBar"
        style="?android:attr/progressBarStyleHorizontal"
        android:layout_width="100dp"
        android:layout_height="4dp"
        android:layout_marginStart="8dp"
        android:max="100"
        android:progress="100"
        android:progressTint="#81C784"
        android:progressBackgroundTint="#33FFFFFF" />

</LinearLayout>
```

**Note**: Keep all the hidden ghost `TextView` stubs (0dp) that Kotlin references — only move the visible parts.

### 4.2 Remove inner topic chips padding fix
**File**: `app/src/main/res/layout/activity_home.xml`
- Inside `bbInnerTopicsScroll`, remove `android:paddingStart="-8dp"` and `android:paddingEnd="-8dp"` (negative padding is unreliable). Use `android:paddingStart="0dp"` instead.

---

## Phase 5 — Avatar Upgrade (no external asset needed)

### 5.1 Drawer header avatar
**File**: `HomeActivity.kt` — in `updateDrawerHeader()`:
```kotlin
// Replace the emoji with user's initial in a colored circle
val initial = currentUser?.displayName?.firstOrNull()?.uppercaseChar()?.toString() ?: "S"
drawerAvatar.text = initial
drawerAvatar.setTextColor(android.graphics.Color.WHITE)
// background is already bg_avatar_circle (colorPrimary circle)
```

### 5.2 Top bar profile button (same as Phase 1.6)
Already covered — use first letter of name.

---

## File Change Summary

| File | Changes |
|---|---|
| `res/values/colors.xml` | 6 color values updated (dark theme) |
| `res/layout/activity_home.xml` | Streak visible, greeting visible, card heights, profile button, drawer icons, quota strip moved, subject bg fix |
| `HomeActivity.kt` | Greeting logic, profile button wiring, avatar initial, status bar color |
| `res/drawable/` | 11 new vector icon files added |

---

## What NOT to change
- `BlackboardActivity` layout — already full dark, no changes needed
- `FriendsActivity`, `UserProfileActivity` — out of scope
- Drawer item IDs — keep all existing IDs, Kotlin wires them
- Any hidden 0dp ghost stubs — they must stay for Kotlin compile compat
- `bg_daily_challenge_gradient` — keep as-is, looks fine on dark background

---

## Testing Checklist
- [ ] Dark background shows on home scroll area
- [ ] Drawer items show vector icons (not emoji) at correct size
- [ ] Greeting updates to correct time of day
- [ ] Streak badge shows (when streak > 0)
- [ ] Profile button in top bar navigates to profile
- [ ] Quota strip shows below BB card (not inside it)
- [ ] Subject cards readable on dark background
- [ ] No hard-coded white backgrounds visible anywhere
- [ ] Status bar matches dark background (no white flash)
