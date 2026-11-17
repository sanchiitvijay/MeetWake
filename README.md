# MeetWake

A tiny (~140 KB) Android app for people who work from home and might sleep through an ad-hoc
Microsoft Teams meeting. It watches **Teams notifications only**, and when one is about a meeting it
sets a real alarm that rings over the lock screen, at full alarm volume, even on silent or Do Not Disturb.

It works offline: it only reacts to notifications Teams already put on the phone. There's no server,
no account and no internet permission.

## Install

1. Copy `MeetWake.apk` to the phone and open it (allow "install unknown apps" when asked).
2. Open MeetWake and work through the **Setup** card:
   - **Notification access**: required. On Android 13+ a sideloaded app's switch may be greyed out.
     Fix it with *App info → ⋮ (top right) → Allow restricted settings*, then turn it on.
   - **Show notifications**, **Full-screen alarms**, **Battery: unrestricted**: tap Allow on each.
3. Tap **Try it → Test alarm** and lock the phone to check that the alarm wakes the screen.
4. In Teams, make sure notifications are on for meetings, calls and chats.

## How it decides

| Teams notification | What MeetWake does |
|---|---|
| Reminder: "Starts in 15 minutes", "10:00 AM – 10:30 AM" | Alarm at meeting time minus your lead time |
| Invite: "…invited you… tomorrow at 11:30", "Thu, Nov 19 3:00 PM" | Alarm on that day and time |
| "X started a meeting", "Meet now", or no time given | Rings right away |
| Incoming or missed Teams call | Rings right away (can be turned off) |
| Chat: "can you join the call?" | Rings right away (can be turned off) |
| "Canceled: …" | Removes the matching alarm |
| "Updated/rescheduled the meeting" | Moves the alarm |
| Meeting ended, recordings, reactions | Ignored |

It only rings when **all** of these are true:
- The meeting falls inside your **work hours**.
- That day is a **WFH day**. This comes from your weekdays, or from your answer to the 10 PM "WFH tomorrow?" question.
- You haven't said you're **awake**. Tap "I'm awake" on the alarm, on the awake-check notification, or in the app.
  You count as asleep again when a **nap period** starts, and from 3 hours before your next shift.

### Alarm and Teams call never clash

- **Already in a call or meeting?** The alarm doesn't ring. MeetWake checks the phone's call mode and whether
  the microphone is open for a voice call (Teams keeps it open even when you're muted). Teams' own pinned
  "in call" notification also counts.
- **Alarm ringing and you answer or join?** It stops within about 2 seconds and marks you awake.
- **Incoming Teams call while you sleep?** The alarm rings and its main button is **Answer in Teams**
  (**Open Teams** for meetings). One tap stops the alarm, unlocks the phone and opens that exact Teams screen,
  so the alarm never hides Teams' Answer or Join button.
- A colleague's chat saying "I'm in a call" does *not* count as you being in a call.

Duplicate notifications about the same meeting ring once. An unanswered alarm snoozes itself and rings again,
up to 3 times.

## Battery

- The notification listener is connected **only during work hours on WFH days**. Outside them it disconnects,
  and an exact alarm reconnects it when work starts. There's an option to keep it connected all the time.
- Nothing polls and nothing runs in the background. All the app has is a few AlarmManager timers, plus the
  ringing service, which runs only while an alarm is ringing.
- No libraries: framework APIs only.

## Settings

WFH weekdays · evening "WFH tomorrow?" question and its time · work start/end (overnight shifts supported) ·
nap periods · awake-check time · ask again after a nap · minutes before the meeting · snooze length ·
ring duration · sound · vibration · force full alarm volume · Teams calls on/off · chat messages on/off ·
extra keywords · stay connected outside work hours.

The **Recent Teams notifications** list shows every Teams notification seen during work hours and what was
decided about it. Use it to check detection on your own phone. **Try it → Test a Teams notification** lets
you type any text and see the result.

## Build

```bash
./gradlew :app:testDebugUnitTest :app:assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`. It's signed with your local debug key,
so it's fine for sideloading but not for the Play Store.

Debug builds also accept notifications from `adb shell cmd notification post`, so you can test without Teams:

```bash
adb shell "cmd notification post -S bigtext -t 'Sprint planning' tag1 'Meeting starts in 3 minutes'"
```

## Code map

- `core/MeetingParser.kt`: detects meetings and reads times from notification text (pure Kotlin, unit tested)
- `core/Planner.kt`: work hours, WFH days, naps, awake logic (pure Kotlin, unit tested)
- `TeamsListenerService.kt`: reads Teams notifications and disconnects outside work hours
- `Engine.kt`: turns detections into alarms, removes duplicates, decides at ring time
- `Alarms.kt`: AlarmManager timers, receivers, permissions
- `RingService.kt` / `AlarmActivity.kt`: the ringing alarm and its full-screen screen
- `MainActivity.kt`: status, setup checklist and settings
