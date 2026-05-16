# AI Guru - Comprehensive Admin Dashboard

## Overview

This is a complete admin dashboard for managing users, plans, credits, offers, schools, subjects, chapters, and activity logs.

## Architecture

### Backend (FastAPI)
- All endpoints in `server/app/api/admin.py`
- HTTP Basic Auth protection
- Firestore database access
- Caching for frequently accessed data (5 min TTL for stats)

### Frontend
- Vue.js-like modular JavaScript
- Responsive tables with pagination (50 items/page)
- Modal editors for CRUD operations
- Real-time search and filtering

## Key Collections

| Collection | Purpose | CRUD |
|-----------|---------|------|
| `users_table/{uid}` | User accounts, quotas, plans | ✓ |
| `user_credits/{uid}` | Credit balance | ✓ (grant/deduct) |
| `credit_transactions` | Credit audit log | Read only |
| `plans/{id}` | Subscription plans | ✓ |
| `credit_topups/{id}` | Credit pack definitions | ✓ |
| `app_offers/{id}` | Promotional banners | ✓ |
| `schools/{id}` | School entities | ✓ |
| `subjects/{id}` | Subject catalog | ✓ |
| `chapters/{id}` | Chapter content | ✓ |
| `activity_logs` | User activity | Read only (paginated) |

## API Endpoints

### Users (`users_table`)
```
GET    /admin/api/users_table?page=1&limit=50&search=&plan=&grade=&school=
GET    /admin/api/users_table/{uid}
PUT    /admin/api/users_table/{uid}
DELETE /admin/api/users_table/{uid}

POST   /admin/api/users_table/{uid}/quota
POST   /admin/api/users_table/{uid}/credits/grant
POST   /admin/api/users_table/{uid}/credits/deduct
```

### Plans
```
GET    /admin/api/plans_new
POST   /admin/api/plans_new
PUT    /admin/api/plans_new/{plan_id}
DELETE /admin/api/plans_new/{plan_id}
```

### Credit Topups
```
GET    /admin/api/credit-topups_new
POST   /admin/api/credit-topups_new
PUT    /admin/api/credit-topups_new/{pack_id}
DELETE /admin/api/credit-topups_new/{pack_id}
```

### Offers
```
GET    /admin/api/offers_new
POST   /admin/api/offers_new
PUT    /admin/api/offers_new/{offer_id}
DELETE /admin/api/offers_new/{offer_id}
```

### Schools
```
GET    /admin/api/schools_new
POST   /admin/api/schools_new
PUT    /admin/api/schools_new/{school_id}
DELETE /admin/api/schools_new/{school_id}
```

### Subjects
```
GET    /admin/api/subjects_new
POST   /admin/api/subjects_new
PUT    /admin/api/subjects_new/{subject_id}
DELETE /admin/api/subjects_new/{subject_id}
```

### Chapters
```
GET    /admin/api/chapters_new?subject_id=
POST   /admin/api/chapters_new
PUT    /admin/api/chapters_new/{chapter_id}
DELETE /admin/api/chapters_new/{chapter_id}
```

### Activity Logs
```
GET    /admin/api/activity-logs_new?page=1&limit=50&uid=&event_type=
```

## User Fields (`users_table`)

### Identity
- `userId` - Firebase UID
- `name` - User full name
- `email` - User email
- `grade` - School grade/level
- `schoolId` - Associated school
- `schoolName` - School name
- `created_at` - Registration timestamp (ms)

### Plan Information
- `planId` - Current plan ID
- `planName` - Plan name
- `plan_start_date` - Plan activation date (ms)
- `plan_expiry_date` - Plan expiry (0 = never)
- `plan_daily_chat_limit` - Daily chat questions limit
- `plan_daily_bb_limit` - Daily blackboard sessions limit
- `plan_tts_enabled` - TTS feature enabled
- `plan_ai_tts_enabled` - AI TTS feature enabled
- `plan_blackboard_enabled` - Blackboard mode enabled
- `plan_image_enabled` - Image upload enabled

### Daily Counters (Reset at UTC midnight)
- `chat_questions_today` - Chat questions used today
- `bb_sessions_today` - BB sessions used today
- `questions_updated_at` - Last update timestamp (ms)
- `free_bb_remaining` - BB sessions remaining today
- `free_chat_remaining` - Chat questions remaining today

### Token Usage
- `tokens_today` - Total tokens used today
- `input_tokens_today` - Input tokens today
- `output_tokens_today` - Output tokens today
- `tokens_this_month` - Total tokens this month
- `input_tokens_this_month` - Input tokens this month
- `output_tokens_this_month` - Output tokens this month
- `tokens_lifetime` - Lifetime token usage
- `tokens_updated_at` - Last token update (ms)

### TTS Usage
- `tts_chars_today` - TTS characters used today
- `tts_chars_this_month` - TTS characters this month
- `tts_chars_lifetime` - Lifetime TTS characters
- `tts_updated_at` - Last TTS update (ms)
- `ai_tts_quota_chars` - Monthly AI TTS character quota

### Referral
- `referredBy` - Referrer user ID
- `bonus_questions_today` - Bonus questions from referral
- `referral_bb_bonus_per_day` - Daily BB bonus from referral
- `referral_bb_bonus_expiry_at` - Referral bonus expiry (ms)

## Plan Fields

### Basic Info
- `id` - Plan identifier
- `name` - Plan name
- `badge` - Badge text (e.g., "Popular", "Best Value")
- `tagline` - Short description
- `price_inr` - Price in INR
- `duration` - Duration text
- `validity_days` - Days valid (0 = forever)
- `is_active` - Published/hidden
- `is_public` - Public listing
- `display_order` - Sort order
- `accent_color` - UI color

### Features
- `features` - Array of feature strings

### Limits (All optional)
- `daily_chat_questions` - Daily chat limit (0 = unlimited)
- `daily_bb_sessions` - Daily BB limit (0 = unlimited)
- `daily_token_limit` - Daily token limit
- `monthly_token_limit` - Monthly token limit (0 = unlimited)
- `context_window_messages` - Max messages in context
- `context_window_chars` - Max chars in context
- `image_upload_enabled` - Image upload allowed
- `voice_mode_enabled` - Voice mode allowed
- `pdf_enabled` - PDF upload allowed
- `blackboard_enabled` - Blackboard allowed
- `flashcards_enabled` - Flashcards allowed
- `tts_enabled` - TTS allowed
- `ai_tts_enabled` - AI TTS allowed
- `ai_tts_quota_chars` - Monthly AI TTS character quota
- `max_quiz_questions` - Maximum quiz questions
- `credits_on_activation` - Credits granted on activation
- `starter_credits` - Starting credit balance
- `starter_tts_credits` - Starting TTS credit balance

## Usage

### Login
1. Navigate to `/admin`
2. Enter admin credentials (default: admin / admin123)
3. You'll see the dashboard

### Manage Users
1. Go to "Users" section
2. Use search/filters to find users
3. Click "View" to see full details
4. Click "Edit" to modify user fields
5. Click "Grant Credits" or "Deduct Credits" to adjust balance
6. Click "Edit Quota" to modify plan/daily limits

### Manage Plans
1. Go to "Plans" section
2. Click "Add Plan" or edit existing
3. Fill in all fields
4. Edit "Limits" section for feature flags and quotas

### Activity Logs
1. Go to "Activity Logs" section
2. Logs show last 50 entries per page
3. Filter by user UID or event type
4. Pagination: 50 items per page

## Caching

- **Dashboard stats**: 5 minute TTL
- **Plans list**: 5 minute TTL
- **Schools list**: 5 minute TTL
- **Everything else**: Real-time

Clear cache by reloading the dashboard tab.

## Security

- HTTP Basic Auth (env vars: ADMIN_USERNAME, ADMIN_PASSWORD)
- Change defaults in production!
- All operations logged in activity_logs
- No user deletion confirmation required (be careful!)

## Common Tasks

### Grant Credits to User
1. Users → Find user → Grant Credits button
2. Enter amount (positive number)
3. Enter reason (e.g., "Support refund", "Promo")
4. Click Save

### Change User's Plan
1. Users → Find user → Edit Quota
2. Select new plan ID
3. Update daily limits if needed
4. Click Save

### Create New Offer
1. Offers → Add Offer
2. Fill title, subtitle, emoji, color
3. Set display order
4. Click Save

### Add Credit Pack
1. Credit Topups → Add Pack
2. Set name, credits, bonus, price
3. Set display order
4. Toggle active
5. Click Save

## Troubleshooting

**Users not loading?**
- Check browser console for errors
- Verify admin credentials
- Check Firestore connectivity

**Changes not saving?**
- Check for validation errors in modal
- Verify required fields are filled
- Check browser console

**Pagination not working?**
- Reload the page
- Clear browser cache
- Check Firestore has enough documents

