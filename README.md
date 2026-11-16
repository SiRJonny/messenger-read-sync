# Messenger Read Sync

Clears a Facebook Messenger notification on your Android phone when you
read that chat on the PC (messenger.com in the browser). Both devices must
be on the same LAN.

## Pieces

| Folder | What it is |
|---|---|
| `extension/` | Chrome MV3 extension. Detects which chat you just read (unread-badge removal in the chat list) and POSTs its name to the phone. Retries for ~10 min if the phone is unreachable. Works on messenger.com and facebook.com/messages. |
| `android/` | Android app. Runs an HTTP endpoint (port 8765) and, with notification-access permission, dismisses matching Messenger notifications. |

## How it works

1. You read a chat (open it, or click into the page when its messages
   are new — the extension watches the per-thread "unread" marker in the
   chat list, Hungarian "Olvasatlan üzenet" / English "Unread message").
2. Extension reads the thread name and POSTs
   `{ "thread": "Alice" }` to `http://<phone-ip>:8765/read`.
3. Phone app compares the name against live Messenger notifications
   (title/text, case-insensitive, partial match) and cancels the matches.

## Security model

- Shared bearer token. The app generates a 32-hex-char token on first
  launch; paste it into the extension options together with the phone URL.
  Requests without it get 403 (constant-time comparison).
- Home-network gate. On first launch tap "Set current Wi-Fi as home
  network"; the app stores your router's BSSID (its Wi-Fi MAC address,
  unique per access point) and only runs the endpoint while connected to
  that access point. On any other Wi-Fi (or mobile data) the endpoint is
  stopped. Android requires fine location permission and location
  services on to read the BSSID — the app asks once.
- No router port forwarding — reachable from your LAN only.
- Payload contains chat names only. No message content, no credentials.
- Cleartext HTTP on the LAN: an eavesdropper on the same network could see
  chat names and the token. Acceptable for notification dismissal; do not
  port-forward.

## Build the Android app

1. Open `android/` in Android Studio (it fetches Gradle + dependencies;
   the Android SDK is already at `C:\Users\Csabi\AppData\Local\Android\Sdk`).
2. Run the `app` configuration on your phone (USB debugging) or
   Build > Build APK(s) and sideload.

## Setup

1. Phone: open the app, tap **Grant notification access**, allow it for
   Messenger Read Sync. The app shows the phone URL and token.
2. Phone: tap **Allow background** so Doze does not block the server while
   the phone idles (small battery cost, no foreground service used).
3. Phone: tap **Set current Wi-Fi as home network** (must be on home Wi-Fi).
3. PC: `chrome://extensions` > Developer mode > Load unpacked >
   select `extension/`.
4. Extension options: paste the URL shown in the app
   (`http://<phone-ip>:8765/read`) and the token.

## Notes

- Matching is by chat name; a group chat must have the same name in the
  Messenger notification as in the chat header on the PC (normally true).
- Mesh Wi-Fi systems have one BSSID per node/band. If you roam between
  nodes and the notification stops clearing, tap "re-pair" on that node,
  or ask for an SSID-based fallback.
- Only Messenger notifications (`com.facebook.orca`) are ever touched.
- If Facebook changes the chat-list marker text or heading structure,
  update `UNREAD_MARKER` / `getChatTitle()` in `extension/content.js`.
- Samsung: also remove the app from Battery > Background usage limits >
  **Deep sleeping apps** if the server stops responding while the screen
  is off.
