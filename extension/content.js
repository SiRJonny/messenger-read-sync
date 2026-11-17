// Detects when the user READS a chat on messenger.com / facebook.com.
//
// Signal: the chat list item of an unread thread contains the marker
//   "Olvasatlan üzenet:" (hu) / "Unread message" (en),
// which Facebook removes exactly when the thread becomes read — no
// matter whether reading happened by opening the thread or by clicking
// in an already-open chat. Detection is per thread via the item's href.

(function () {
  "use strict";

  const BOILER = new Set([
    "messenger", "chats", "chatek", "messages", "üzenetek", "new message", "új üzenet",
  ]);
  const UNREAD_MARKER = /(olvasatlan üzenet|unread message)/i;

  function getChatTitle() {
    // 1) Page title — "(1) Alice | Facebook" -> "Alice"
    let t = (document.title || "")
      .replace(/^\s*\(\d+\)\s*/, "")
      .replace(/\s*\|\s*(Messenger|Facebook)\s*$/i, "")
      .trim();
    if (t && !BOILER.has(t.toLowerCase())) return t;

    // 2) DOM headings. Chat header renders the name as a heading; UI
    //    boilerplate headings are filtered out. Group chats prefix with
    //    "Beszélgetés velük: X" (hu) / "Conversation with them: X" (en).
    const hs = [...document.querySelectorAll('h1,h2,h3,[role="heading"]')]
      .map((e) => e.textContent.trim())
      .filter((x) => x && x.length < 60)
      .map((x) =>
        x.replace(/^Beszélgetés velük:\s*/i, "").replace(/^Conversation with .*?:\s*/i, "")
      )
      .filter((x) => !BOILER.has(x.toLowerCase()));
    return hs[0] || "";
  }

  const wasUnread = new Map(); // threadId -> list item text while unread
  const lastPreview = new Map(); // threadId -> normalized preview text

  function normalizePreview(text) {
    let t = text.replace(/\s+/g, " ").trim();
    // Strip the relative timestamp ("· 4 p.") — it changes every minute.
    const i = t.lastIndexOf("·");
    if (i >= 0) t = t.slice(0, i).trim();
    // Strip presence prefix — toggles with the contact's online status.
    return t.replace(/^Most aktív\.\s*/i, "").replace(/^Active now\.\s*/i, "");
  }

  function currentThreadId() {
    const m = location.pathname.match(/\/t\/([^/]+)/);
    return m ? decodeURIComponent(m[1]) : null;
  }

  function fire(id, triesLeft) {
    try {
      const title = getChatTitle();
      if (!title) {
        // Header may not be rendered yet; retry briefly.
        if (triesLeft > 0) {
          setTimeout(() => fire(id, triesLeft - 1), 500);
        } else {
          console.warn("readsync: read detected but no chat name found for", id);
        }
        return;
      }
      chrome.runtime.sendMessage({ type: "chat-read", threadId: id, title });    } catch (e) {
      console.error("readsync fire error:", e);
    }
  }

  function poll() {
    try {
      for (const a of document.querySelectorAll('a[href*="/t/"]')) {
        const href = a.getAttribute("href") || "";
        const m = href.match(/\/t\/([^/]+)/);
        if (!m) continue;
        const id = decodeURIComponent(m[1]);
        const text = a.textContent || "";
        const unread = UNREAD_MARKER.test(text);
        if (unread) {
          wasUnread.set(id, true);
        } else if (wasUnread.get(id)) {
          // Unread seen for this thread, marker gone -> just read.
          wasUnread.delete(id);
          fire(id, 8);
        }
        // Open + focused chat: incoming messages are marked read
        // instantly, so no unread marker ever appears. The only visible
        // signal is the list preview changing (sender/text changed).
        const norm = normalizePreview(text);
        const prev = lastPreview.get(id);
        if (prev !== undefined && prev !== norm && !unread) {
          if (id === currentThreadId() && document.hasFocus()) fire(id, 8);
        }
        lastPreview.set(id, norm);
      }
    } catch (e) {
      console.error("readsync poll error:", e);
    }
  }

  setInterval(poll, 400);
})();
