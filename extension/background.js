// Receives "chat-read" from the content script and POSTs to the phone.
// On failure it retries for ~10 minutes via chrome.alarms, so a dozing
// phone gets the event as soon as its Wi-Fi wakes up.

const RETRY_ALARM = "readsync-retry";
const MAX_ATTEMPTS = 10; // 1 per minute

function post(cfg, payload) {
  const headers = { "Content-Type": "application/json; charset=utf-8" };
  if (cfg.token) headers["Authorization"] = "Bearer " + cfg.token;
  // Base64 header dodges charset mangling of accented names (ó/ű/...) in
  // the POST body on the phone's HTTP server.
  const bytes = new TextEncoder().encode(payload.thread);
  headers["X-Thread-Name"] = btoa(String.fromCharCode(...bytes));
  return fetch(cfg.phoneUrl, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  }).then((res) => {
    if (!res.ok) throw new Error("HTTP " + res.status);
  });
}

async function attemptPending(alarm) {
  const { pending } = await chrome.storage.local.get("pending");
  if (!pending) return;
  if (alarm && alarm.name !== RETRY_ALARM) return;

  const cfg = await chrome.storage.sync.get({ phoneUrl: "", token: "" });
  if (!cfg.phoneUrl) {
    console.warn("phoneUrl not configured, giving up this attempt");
    return;
  }
  // Event-triggered SW fetches can race the network stack; retry fast
  // a few times before falling back to the 1-minute alarm.
  for (let i = 0; i < 4; i++) {
    try {
      await post(cfg, pending.payload);
      await chrome.storage.local.remove("pending");
      await chrome.alarms.clear(RETRY_ALARM);
      return;
    } catch (e) {
      console.warn("readsync POST failed:", e);
      await new Promise((r) => setTimeout(r, 400));
    }
  }
  if (pending.attempts + 1 >= MAX_ATTEMPTS) {
    await chrome.storage.local.remove("pending");
    await chrome.alarms.clear(RETRY_ALARM);
    return;
  }
  await chrome.storage.local.set({
    pending: { ...pending, attempts: pending.attempts + 1 },
  });
  await chrome.alarms.create(RETRY_ALARM, { delayInMinutes: 1 });
}

chrome.runtime.onMessage.addListener((msg) => {
  if (msg.type !== "chat-read") return;
  chrome.storage.local.get("pending", ({ pending }) => {
    const payload = { thread: msg.title, threadId: msg.threadId };
    // Keep the oldest pending chat; the newest title is usually enough,
    // but the oldest unread state is the one most worth clearing.
    const next = pending || { payload, attempts: 0 };
    chrome.storage.local.set({ pending: next });
    attemptPending(null);
  });
});

chrome.alarms.onAlarm.addListener(attemptPending);
