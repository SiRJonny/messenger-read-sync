const els = {
  url: document.getElementById("phoneUrl"),
  token: document.getElementById("token"),
  save: document.getElementById("save"),
  status: document.getElementById("status"),
};

chrome.storage.sync.get({ phoneUrl: "", token: "" }, (cfg) => {
  els.url.value = cfg.phoneUrl;
  els.token.value = cfg.token;
});

els.save.addEventListener("click", () => {
  chrome.storage.sync.set(
    { phoneUrl: els.url.value.trim(), token: els.token.value.trim() },
    () => {
      els.status.textContent = "Saved";
      setTimeout(() => (els.status.textContent = ""), 1500);
    }
  );
});
