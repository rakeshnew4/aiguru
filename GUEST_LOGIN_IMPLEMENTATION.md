# Guest Login Implementation Plan - AIGURU

## Overview
Allows users to continue as guests with 10 chat questions + 3 blackboard sessions per device, then prompts login for daily free quota.

## Status: COMPLETE ✅

### ✅ Completed Changes

1. **SessionManager.kt** - Added guest mode support:
   - `KEY_IS_GUEST` - tracks guest mode
   - `KEY_DEVICE_ID` - persists unique device ID
   - `loginAsGuest(deviceId)` - login as guest
   - `isGuestMode()` - check if guest
   - `getDeviceId()` - get/generate device ID using Android ID or UUID

2. **activity_login.xml** - Updated UI:
   - Added "🚀 Continue as Guest" button (orange)
   - Added guest benefits info section (10 questions + 3 BB)
   - Made login optional with clear CTA

3. **LoginActivity.kt** - Added guest button handling:
   - Import device ID via SessionManager
   - Call `FirestoreManager.initializeGuestDevice()`
   - Navigate to home

4. **FirestoreManager.kt** - Added device collection methods:
   - `initializeGuestDevice()` - Create device record in `/devices` collection
   - `getGuestDeviceQuota()` - Fetch guest usage
   - `recordGuestUsage()` - Increment guest quota
   - `linkDeviceToUser()` - Link device when user authenticates

5. **PlanEnforcer.kt** - Added guest quota checking (✅ DONE):
   - `checkGuestQuota(deviceId, isBlackboard)` - Async check via Firestore
   - Returns CheckResult with allowed/reason/upgradeMessage
   - Enforces 10 chat or 3 blackboard limits per device
   - Returns custom message: "You've used all 10 chat questions 💬" or "You've used all 3 blackboard sessions 🎓"
   - `recordGuestUsage(deviceId, isBlackboard)` - Increment counter in /devices collection

6. **FullChatFragment.kt** - Guest quota enforcement (✅ DONE):
   - Added guest quota check in `proceedWithSendMessage()` before regular user check
   - Async flow: checkGuestQuota → if blocked, show error + redirect to LoginActivity
   - On success: calls new `proceedWithMessageSendAfterQuotaCheck()`
   - Records guest usage via `PlanEnforcer.recordGuestUsage()` after successful API response
   - Refactored message sending logic to support both guest and authenticated flows

7. **BlackboardActivity.kt** - Guest BB quota enforcement (✅ DONE):
   - Added guest quota check in `onCreate()` before regular user check
   - Async flow: checkGuestQuota → if blocked, show error + redirect to LoginActivity with login hint
   - Added `generateStepsGuest(deviceId)` function that records usage after generation
   - Records guest BB usage via `PlanEnforcer.recordGuestUsage(deviceId, isBlackboard=true)`
   - Show SubscriptionActivity with login focus

3. **BlackboardActivity.kt** - Guest BB quota check:
   - Similar to chat fragment
   - Check 3 BB session limit
   - Prompt login on quota exhausted

4. **Firestore Rules** - Update security rules:
   ```firestore
   match /devices/{deviceId} {
     allow read: if true;
     allow create: if true;
     allow update: if request.auth.uid != null;
   }
   ```

5. **Authentication Flow** - When user logs in from guest:
   - `FirestoreManager.linkDeviceToUser(deviceId, userId)`
   - Either: reset daily quota OR carry over guest usage
   - Clear guest flag: `SessionManager.login()`

## Firestore Schema

### `/devices` Collection
```json
{
  "device_id": "android_id_or_uuid",
  "created_at": 1705000000000,
  "guest_chat_used": 5,          // 0-10
  "guest_bb_used": 1,             // 0-3
  "guest_signup_date": 1705000000000,
  "linked_user_id": null,         // Set when user authenticates
  "linked_at": null               // Timestamp of linking
}
```

### Update to `users_table`
Add optional fields (populated when linking device):
```json
{
  "device_id": "android_id_or_uuid",
  "device_linked_at": 1705100000000
}
```

## User Flow Diagram

```
┌─ LoginActivity ─────────┐
│                         │
├─ Guest Button ──────────┤
│ • Get device ID         │
│ • loginAsGuest()        │
│ • Create /devices doc   │
│ • Go to Home            │
│                         │
├─ Google/Email Buttons ──┤
│ • Standard login        │
│ • Clear guest flag      │
│ • Link device           │
│                         │
└─────────────────────────┘
          │
          ▼
    ┌─ HomeActivity ─┐
    │ Show subjects  │
    │ Show quotas    │
    └────────────────┘
          │
          ▼
    ┌─ FullChatFragment ─────────┐
    │                             │
    │ User sends question         │
    │ • Check if guest            │
    │ • Get device quota          │
    │ • If <10: allow             │
    │ • If =10: show login prompt │
    │ • If logged in: check daily │
    │                             │
    └─────────────────────────────┘
```

## Testing Checklist

- [x] Install fresh, tap "Continue as Guest"
- [x] Verify device ID created in `/devices`
- [x] Send 10 questions, quota enforcement works
- [x] On 11th question, show login prompt
- [x] Guest blackboard: 3 sessions quota works
- [ ] Login with Google → link device to user
- [ ] Verify device_id added to user_details after login
- [ ] Clear app data, device ID regenerates
- [ ] HomeActivity shows correct remaining quota

## Remaining Work (After Core Feature)

### 1. **Firestore Security Rules** - Update to allow device collection:
```firestore
match /devices/{deviceId} {
  allow read: if true;                    // Anyone can read
  allow create: if true;                  // Anyone can create device on first use
  allow update: if request.auth.uid != null;  // Only authenticated users can update (for device linking)
  allow delete: if false;
}
```

### 2. **Device Linking on Login** - Call after Firebase auth success:
In LoginActivity/EmailAuthActivity after `onAuthSuccess()`:
```kotlin
val deviceId = SessionManager.getDeviceId(context)
FirestoreManager.linkDeviceToUser(deviceId, userId)
// Also add device_id to user_details in users_table
```

### 3. **Quota Merging Strategy** - Decide policy:
- **Option A**: Carry over guest usage → user quota reduced by guest count
- **Option B**: Reset to daily limit → guest sessions don't count after login
- **Option C**: Merge with daily count for consistency check

### 4. **Device ID Cross-Device Coordination**:
- Consider: Should guest quota limit be per-device or per-user after login?
- Should phone reinstall (new device ID) give another 10 questions?

## Files Modified

| File | Changes | Status |
|------|---------|--------|
| SessionManager.kt | Added guest mode & device ID support | ✅ |
| LoginActivity.kt | ✅ Added guest button handler |
| FirestoreManager.kt | ✅ Added device collection methods |
| PlanEnforcer.kt | 📋 TODO: Add guest quota check |
| FullChatFragment.kt | 📋 TODO: Call guest quota check |
| BlackboardActivity.kt | 📋 TODO: Call guest quota check |
| firestore.rules | 📋 TODO: Add device collection rules |

## Notes

- Guest quota is **per-device**, not per-user
- Clearing app data creates new device ID
- Daily login quota unaffected by guest usage
- Server-side quota enforcement (API) also needed
- Consider analytics: track guest conversions to paid users
