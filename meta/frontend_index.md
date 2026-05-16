# Frontend Admin Portal Index
> Documents admin portal files and key symbols
> Updated: 2026-05-16 — Frontend integration with new API endpoints

---

## Overview
**Location:** `server/app/static/admin/`
**Entry point:** `index.html` + `js/app.js` (router)
**Auth:** HTTP Basic Auth via api.js (credentials stored in sessionStorage)
**Sections:** 15 admin modules managed by SECTION_MAP in app.js

---

## Core Files

### index.html
**Purpose:** Page layout, nav bar, section divs, script loading order
**Key elements:**
- `#login-screen` — login card (username/password)
- `#app` — main dashboard (hidden until login)
- `aside.sidebar` — left nav with 15 data-section links
- `#modal-overlay` + `#modal` — global modal helper (forms, confirmations)
- `#toast-container` — toast notifications
- Script load order: api.js → section modules → app.js (router must load last)

**Sections defined:**
1. dashboard, analytics, users, subjects, chapters, plans, **credits**, schools, payments, models, appconfig, offers, notifications, referrals, logs

### js/app.js
**Size:** ~240 lines
**Key symbols:**

| Symbol | Lines | Purpose |
|--------|-------|---------|
| `Toast` module | 17–34 | Global toast helper; `Toast.success/error/info(msg)` |
| `Modal` module | 39–95 | Global modal helper; `Modal.open/close/setBody()` |
| `SECTION_MAP` | 102–117 | Router: maps section name → {title, render function} |
| `navigate(section)` | 123–151 | Switch sections: hide/show DOM, update sidebar active state, call render() |
| `handleLogin(e)` | 155–180 | Form submit: validate creds, call `API.setCredentials()`, try `GET /stats` to verify, show app if success |
| `App.init()` | 206–236 | Boot: setup modal, login form, logout, sidebar nav, auto-login if sessionStorage has creds |

### js/api.js
**Purpose:** HTTP client with Basic Auth
**Key functions:**
- `setCredentials(user, pass)` — encode base64, store in sessionStorage
- `get/post/put/del(path, [payload])` — attach Authorization header, emit `admin:unauthorized` on 401

---

## Admin Modules

### js/users.js
**Purpose:** User management (search, view, edit, delete, credits)
**Size:** ~370 lines (after 2026-05-16 update)
**Pagination:** 25 users per page

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `_filtered` | state | Filtered user list by search + plan + grade |
| `render()` | 25–60 | Shell: search box, filter dropdowns, table div, reload button |
| `loadUsers()` | 64–76 | `GET /users?limit=200` → populate filters → applyFilter |
| `applyFilter()` | 92–110 | Client-side filter by search/plan/grade → _filtered |
| `renderTable()` | 114–163 | Paginated table (25/page): UID, Name, Email, Grade, Plan, **Credits** (—), Actions |
| `viewUser(uid)` | 181–245 | Modal: key-value grid + **Credits card** (balance + lifetime + transactions table) + Chapter Progress + action buttons |
| **Credits card** | ~215–235 | New: shows balance, lifetime earned, recent 5 transactions with type badge + color |
| `editUser(uid)` | 219–235 | Modal: JSON textarea editor for full user doc |
| `_saveUser(uid)` | 237–247 | `PUT /users/{uid}` with merge |
| **`adjustCredits(uid)`** | 249–283 | **NEW:** Modal for grant/deduct credits with reason field; calls `POST /users/{uid}/credits/adjust` |
| **`quickQuota(uid)`** | 285–310 | **NEW:** Focused form for quota-only fields (planId, daily_chat_limit, daily_bb_limit, ai_tts_quota_chars, plan_expiry); calls `PUT /users/{uid}/quota` |
| `deleteUser(uid)` | 312–320 | Confirm + `DELETE /users/{uid}` + reload |
| `esc()` | 322–326 | HTML escape helper |
| Module exports | 328 | `render, viewUser, editUser, deleteUser, adjustCredits, quickQuota, _goPage` |

### js/credits.js
**Purpose:** Credit packs CRUD (topups)
**Size:** ~120 lines
**NEW FILE** added 2026-05-16

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `FIELDS` | 14–21 | Form fields: name, credits, bonus_credits, price_inr, display_order, is_active |
| `render()` | 23–37 | Shell: reload button, +Add button, table div |
| `load()` | 39–49 | `GET /credit-topups` → renderTable |
| `renderTable()` | 51–80 | Table: ID, Name, Credits (bold blue), Bonus (green), Price, Order, Active (badge), Actions |
| `_formHtml(data)` | 82–105 | Render form fields from FIELDS + values from data |
| `_collectForm()` | 107–120 | Collect form values respecting types (checkbox/number/text) |
| `openCreate()` | 122–131 | Modal: blank form + `POST /credit-topups` on save |
| `openEdit(id)` | 133–143 | Modal: pre-filled form + `PUT /credit-topups/{id}` on save |
| `del(id)` | 145–151 | Confirm + `DELETE /credit-topups/{id}` |

### js/plans.js
**Purpose:** Subscription plans CRUD
**Size:** ~180 lines (after 2026-05-16 expansion)

| Symbol | Lines | What it does |
|--------|-------|--------------|
| `FIELDS` | 20–42 | **EXPANDED:** 27 fields total: name, display_name, price, duration, daily_quota, badge, display_order, is_active, + 19 limits.* nested fields under "Limits" section header |
| `_formHtml(data)` | 98–145 | **UPDATED:** Handles nested limits.* keys; renders section header when `f.section` changes; pre-fills values from nested limits object |
| `_collectForm()` | 147–166 | **UPDATED:** Nests dotted keys (limits.daily_chat_questions) into `limits` sub-object on collection |
| `render()` / `load()` / `renderTable()` | — | Standard CRUD shells |
| `openCreate()` / `openEdit()` | — | Modal with form; `POST /plans` or `PUT /plans/{id}` |

### js/content.js (Subjects + Chapters)
**Purpose:** Content hierarchy CRUD
**Symbols:** `Subjects.render()`, `Chapters.render()`

### js/schools.js (in plans.js)
**Purpose:** School management CRUD
**Symbols:** `Schools.render()`, `openCreate()`, `openEdit()`, `del()`

### js/dashboard.js
**Purpose:** Stats + charts overview
**Key render:** Dashboard stats cards, charts (user growth, revenue, plan distribution, token usage)

### js/analytics.js
**Purpose:** Advanced analytics views

### js/payments.js
**Purpose:** Payment/Razorpay management

### js/logs.js
**Purpose:** Activity log viewer (paginated, 50/page)

### js/offers.js
**Purpose:** Promotional offers CRUD

### js/notifications.js
**Purpose:** Push notification management

### js/referrals.js
**Purpose:** Referral code management

### js/models.js / js/config.js
**Purpose:** LLM model tier configuration and app config

---

## Data Flow

### User Viewing Workflow
1. Click "Users" nav → `navigate('users')` → `Users.render()` 
2. Fetch `GET /users?limit=200`
3. Show paginated table (25/page)
4. Click "View" → `Users.viewUser(uid)`
5. Fetch `GET /users/{uid}` + `GET /users/{uid}/credits` + `GET /users/{uid}/credits/transactions?limit=5`
6. Show modal with profile + credits card + transactions + Chapter Progress

### Credit Adjustment Workflow
1. From user view modal, click "Adjust Credits"
2. `Users.adjustCredits(uid)` opens modal with amount + reason inputs
3. Live preview updates as user types amount
4. On Save: `POST /users/{uid}/credits/adjust` {amount, reason}
5. Reload users table on success

### Quick Quota Workflow
1. From user view modal, click "Quick Quota"
2. `Users.quickQuota(uid)` opens modal with quota fields only
3. Pre-fill current values via `_loadCurrentQuota(uid)` (`GET /users/{uid}`)
4. On Save: `PUT /users/{uid}/quota` with whitelisted fields
5. Reload users table on success

### Plans Editing Workflow
1. Click "Plans" nav → `navigate('plans')` → `Plans.render()`
2. Fetch `GET /plans`
3. Show plans table
4. Click "Edit" → `Plans.openEdit(id)`
5. Modal shows form with all 27 fields, including expanded "Limits" section
6. On Save: collect form (nests limits.* into limits object) → `PUT /plans/{id}` with nested structure
7. Example payload: `{ name: "Pro", limits: { daily_chat_questions: 50, ai_tts_enabled: true } }`

### Credit Packs CRUD Workflow
1. Click "Credits" nav → `navigate('credits')` → `Credits.render()`
2. Fetch `GET /credit-topups`
3. Show credit packs table
4. Click "Edit" / "+ Add" → form modal
5. On Save: `POST /credit-topups` or `PUT /credit-topups/{id}`

---

## CSS Classes

**Layout:**
- `.sidebar`, `.sidebar-nav`, `.nav-item` — left navigation
- `.topbar` — top header bar
- `.content-area`, `.section` — main content wrapper
- `.card`, `.card-header` — card containers

**Tables:**
- `.table-wrap` — scrollable table container
- `.td-truncate`, `.td-actions` — table utilities
- `table thead/tbody` — standard table markup

**Forms:**
- `.form-group` — form field wrapper
- `.form-group[style*="flex-direction:row"]` — checkbox/inline fields
- `.json-editor` — textarea for JSON input

**Badges & States:**
- `.badge`, `.badge-blue`, `.badge-green`, `.badge-gray`, `.badge-red` — status badges
- `.text-muted`, `.text-sm` — text utilities
- `.flex`, `.gap-2` — flex layout

**Buttons:**
- `.btn`, `.btn-primary`, `.btn-secondary`, `.btn-danger`, `.btn-ghost` — button styles
- `.btn-sm` — small button variant
- `.btn-full` — full-width button

**Modals:**
- `.modal-overlay` — semi-transparent backdrop
- `.modal`, `.modal-wide` — modal container (wide variant)
- `.modal-header`, `.modal-body`, `.modal-footer` — modal sections

**Loading & Empty:**
- `.loading` → `.spinner` — loading indicator
- `.empty-state` → `<p>` — empty state message

---

## Key Patterns

### Modal Pattern
```js
Modal.open('Title', htmlString, { 
  onSave: async () => { /* call API */ },
  wide: true,
  saveLabel: 'Update'
});
Modal.setBody(newHtml); // update body during load
Modal.close();
```

### Form Collection Pattern (FIELDS)
```js
const FIELDS = [
  { key: 'name', label: 'Name', type: 'text', required: true },
  { key: 'limits.daily_chat', label: 'Daily Chat', type: 'number', section: 'Limits' }
];

function _formHtml(data) {
  // Render inputs by FIELDS, show section headers
}

function _collectForm() {
  // Collect into obj, nest dotted keys
  return obj; // { name: "...", limits: { daily_chat: 50 } }
}
```

### Pagination Pattern
```js
const PAGE_SIZE = 25;
let _page = 1;

function renderTable() {
  const start = (_page - 1) * PAGE_SIZE;
  const rows = _filtered.slice(start, start + PAGE_SIZE);
  // render table
  renderPagination(total);
}

function renderPagination(total) {
  // show prev/next buttons
}

function _goPage(p) { _page = p; renderTable(); }
```

### Search & Filter Pattern
```js
let _allUsers = [];
let _filtered = [];

function applyFilter() {
  const q = document.getElementById('search').value.toLowerCase();
  _filtered = _allUsers.filter(u => 
    !q || u.name.toLowerCase().includes(q) || u.email.includes(q)
  );
  _page = 1;
  renderTable();
}
```

---

## Future Enhancements

1. **Bulk operations:** grant credits to multiple users at once
2. **Export:** user table to CSV
3. **Advanced filters:** by created date, last login, token usage
4. **Real-time updates:** WebSocket for activity logs instead of polling
5. **Audit trail:** show who made what changes and when (requires admin audit logs collection)
