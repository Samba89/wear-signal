# wear-signal

A vibe-coded Signal app I made for WearOS, so I can send a message to somebody without a phone. Tested on a Pixel Watch 4.

It links to your existing Signal account as a **linked device** (like Signal Desktop) so an LTE watch can read and reply to
conversations while your phone stays at home.

It has a poll mode which will send notifications, and a manual mode, which requires 
you to open the app and press "Check for messages".

I personally use manual mode because notifications sometimes come in twice, once from 
the watch app and once from the phone's app. (there's no easy workaround to this issue, I might try harder later).
My main use-case is when I'm out of the house and my phone is left at home. In this 
situation, notifications still get forwarded from my phone's signal app in the standard wearOS way.

I am actually a real developer that can write real code with my own hands and brain,
but this particular project is 99% Claude. 

Happy for PRs and bug reports, I'll fix what I can.

## What it does

- **Pairing**: shows a provisioning QR; scan it from phone Signal →
  Settings → Linked devices → Link new device. **Choose "Don't transfer messages"**
  if asked — the watch can't consume the history backup.
- **Conversations**: opens straight into the conversation list (10 at a time, "Load
  more" for older) → chat-style thread view with left/right bubbles, last 100 messages
  per conversation, populated from every drain (history starts at link time). Contact
  and group photos are fetched and decrypted alongside names; anyone without a photo
  gets a coloured monogram, and group messages are colour-coded per sender. Group
  titles, member lists, and photos are fetched from the GroupsV2 server using master
  keys harvested from message contexts.
- **Sending**: reply in any thread or start a new 1:1 via the contact picker. Group
  replies fan out pairwise to the cached member list with the groupV2 context. Sends
  emit a sync transcript so phone Signal shows them too.
- **Notifications toggle** (manual control): OFF (default) — no background polling; the
  phone's own Signal notifications bridge to the watch. ON (phone dead/away) — the watch
  polls the queue on an exact-alarm interval (1/5/15 min, default 5), decrypts, notifies,
  disconnects.
- **Charge-time maintenance**: a daily WorkManager job that only runs while charging
  drains the queue silently (keeps conversations current and refreshes the server's
  45-day linked-device inactivity deadline) and runs prekey upkeep — regardless of the
  toggle, with zero wearing-time battery cost.
- **Manual poll**: "Check for messages" in the conversation list, silent.
- **Images (receive-only)**: image attachments are downloaded during polls, decrypted,
  downscaled to watch resolution (~100KB each; originals never kept), shown inline in
  threads. Retention: 7 days and a 64MB cap, oldest deleted first. Other attachment
  types show as a placeholder.
- Delivery/read ticks on sent messages; no typing indicators; no sending of
  attachments or read receipts.

## Build

Requires JDK 17+ (e.g. Android Studio's bundled JBR — point `JAVA_HOME` at it, or set
`org.gradle.java.home` in your own `~/.gradle/gradle.properties`) and an Android SDK
configured via `local.properties`.

```
./gradlew :app:assembleDebug
```

### Installing the APK on a watch

Wear OS has no way to sideload from the watch itself — installs go over wireless ADB
from a computer with [platform-tools](https://developer.android.com/tools/releases/platform-tools)
(`adb`) on the same Wi-Fi network as the watch.

1. **Enable developer options** on the watch: Settings → System → About → tap
   **Build number** 7 times.
2. **Enable debugging**: Settings → Developer options → turn on **ADB debugging**
   and **Wireless debugging**.
3. **Pair the computer** (first time per computer): in Wireless debugging choose
   **Pair new device**, then on the computer run
   `adb pair <ip>:<pairing-port>` and type the 6-digit code shown on the watch.
4. **Connect**: back on the Wireless debugging screen, note the main IP and port, then
   `adb connect <ip>:<port>`. The watch shows up in `adb devices`.
5. **Install**: `adb install -r wear-signal.apk` (a couple of minutes over Wi-Fi).
   - If it fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the installed copy was
     signed with a different key: `adb uninstall dev.sam.wearsignal` first. That wipes
     the app's data, so you'll re-link afterwards.
6. **Link**: open the app on the watch and scan the QR from phone Signal →
   Settings → Linked devices → Link new device (choose "Don't transfer messages"
   if asked). Then poll once to pull in names, photos, and group state.

Afterwards you can turn Wireless debugging off — it costs battery when left on.

### Signing / working from multiple machines

Both debug and release builds are signed with `keystore/wear-signal.keystore` when it
exists (gitignored — copy it to each dev machine; default password `wear-signal`,
override with `-PwearSignalKeystorePassword=...`). A consistent signature is what lets
installs from any machine update in place: installing an APK with a *different*
signature requires uninstalling first, which wipes the app data and forces a re-link.
If the keystore is absent, debug falls back to the machine-local debug key.

## Testing on the emulator

```
~/Android/Sdk/emulator/emulator -avd wearos5 &
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Status screen has a debug chip to force the phone-connected state
(auto / connected / away) for testing notification dedup without a paired phone.

## Notes

- Prekey upkeep (top-up + 2-day signed/last-resort rotation) runs during the daily
  charge-time maintenance job.
- Group send requires the member list, which is fetched on the first poll that sees a
  message from that group — so a brand-new group needs one incoming drain before you
  can reply to it.
- Unlinking: phone Signal → Linked devices → remove "Watch". Relink anytime (5-device limit).
- Profile names are resolved via profile keys harvested from incoming messages, so the
  first message from a contact may show a truncated ACI until the fetch completes.
