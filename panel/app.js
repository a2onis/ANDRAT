(() => {
  "use strict";

  const STORAGE_KEY = "andrat-control-connection";
  const config = window.ANDRAT_CONFIG || {};
  const saved = readStorage();
  const state = {
    databaseUrl: saved.databaseUrl || config.databaseUrl || "",
    deviceId: saved.deviceId || "",
    token: saved.token || "",
    connected: false,
    status: null,
    pollTimer: null,
    seenAcks: new Set(),
    activity: [],
    flashEnabled: false,
    pollError: ""
  };

  const el = {
    pairForm: document.querySelector("#pair-form"),
    databaseUrl: document.querySelector("#database-url"),
    deviceId: document.querySelector("#device-id"),
    token: document.querySelector("#control-token"),
    toggleToken: document.querySelector("#toggle-token"),
    connectButton: document.querySelector("#connect-button"),
    disconnectButton: document.querySelector("#disconnect-button"),
    pairState: document.querySelector("#pair-state"),
    emptyState: document.querySelector("#empty-state"),
    dashboard: document.querySelector("#dashboard"),
    focusPairButton: document.querySelector("#focus-pair-button"),
    deviceName: document.querySelector("#device-name"),
    deviceIdShort: document.querySelector("#device-id-short"),
    appVersion: document.querySelector("#app-version"),
    dashboardStatus: document.querySelector("#dashboard-status"),
    dashboardStatusLabel: document.querySelector("#dashboard-status-label"),
    connectionDetail: document.querySelector("#connection-detail"),
    lastSeen: document.querySelector("#last-seen"),
    capabilities: document.querySelector("#capabilities"),
    permissions: document.querySelector("#permissions"),
    pingButton: document.querySelector("#ping-button"),
    vibrateButton: document.querySelector("#vibrate-button"),
    vibrateDuration: document.querySelector("#vibrate-duration"),
    vibrateValue: document.querySelector("#vibrate-value"),
    flashlightButton: document.querySelector("#flashlight-button"),
    noticeMessage: document.querySelector("#notice-message"),
    noticeButton: document.querySelector("#notice-button"),
    refreshButton: document.querySelector("#refresh-button"),
    activityList: document.querySelector("#activity-list")
  };

  initialize();

  function initialize() {
    el.databaseUrl.value = state.databaseUrl;
    el.deviceId.value = state.deviceId;
    el.token.value = state.token;
    el.vibrateDuration.addEventListener("input", () => {
      el.vibrateValue.value = `${el.vibrateDuration.value} ms`;
      el.vibrateValue.textContent = `${el.vibrateDuration.value} ms`;
    });
    el.pairForm.addEventListener("submit", (event) => {
      event.preventDefault();
      connect();
    });
    el.toggleToken.addEventListener("click", toggleTokenVisibility);
    el.disconnectButton.addEventListener("click", disconnect);
    el.focusPairButton.addEventListener("click", () => {
      el.databaseUrl.focus();
      window.scrollTo({ top: 0, behavior: "smooth" });
    });
    el.pingButton.addEventListener("click", () => sendCommand("ping", {}, "Ping device"));
    el.vibrateButton.addEventListener("click", () => sendCommand(
      "vibrate",
      { durationMs: Number(el.vibrateDuration.value) },
      `Vibrate for ${el.vibrateDuration.value} ms`
    ));
    el.flashlightButton.addEventListener("click", () => {
      const enabled = !state.flashEnabled;
      sendCommand("flashlight", { enabled }, enabled ? "Turn flashlight on" : "Turn flashlight off")
        .then((sent) => {
          if (sent) {
            state.flashEnabled = enabled;
            updateFlashlightButton();
          }
        });
    });
    el.noticeButton.addEventListener("click", () => {
      const message = el.noticeMessage.value.trim();
      if (!message) {
        el.noticeMessage.focus();
        showToast("Write a short notice first", "error");
        return;
      }
      sendCommand("notify", { message }, "Show device notice").then((sent) => {
        if (sent) el.noticeMessage.value = "";
      });
    });
    el.refreshButton.addEventListener("click", () => poll(true));
    renderDisconnected();
  }

  async function connect() {
    let databaseUrl;
    try {
      databaseUrl = normalizeUrl(el.databaseUrl.value);
    } catch (error) {
      el.databaseUrl.setCustomValidity(error.message);
      el.databaseUrl.reportValidity();
      return;
    }
    el.databaseUrl.setCustomValidity("");

    const deviceId = el.deviceId.value.trim();
    const token = el.token.value.trim();
    if (!deviceId) {
      el.deviceId.setCustomValidity("Paste the device ID shown in the Android app.");
      el.deviceId.reportValidity();
      return;
    }
    if (!token) {
      el.token.setCustomValidity("Paste the control token shown in the Android app.");
      el.token.reportValidity();
      return;
    }
    el.deviceId.setCustomValidity("");
    el.token.setCustomValidity("");

    state.databaseUrl = databaseUrl;
    state.deviceId = deviceId;
    state.token = token;
    saveStorage();
    setConnectBusy(true);
    try {
      // A null status is a valid response while the app has not started its service yet.
      state.status = await databaseRequest(["status"]);
      state.connected = true;
      state.pollError = "";
      showDashboard();
      setActionBusy(false);
      renderStatus();
      addActivity("Connected to pairing", "Panel is ready for commands", "success", Date.now(), "connection");
      startPolling();
      showToast(state.status ? "Device connected" : "Credentials saved — waiting for app", "success");
    } catch (error) {
      state.connected = false;
      setPairState("CONNECTION ERROR");
      showToast(error.message, "error");
    } finally {
      setConnectBusy(false);
    }
  }

  function disconnect() {
    stopPolling();
    state.connected = false;
    state.status = null;
    state.databaseUrl = "";
    state.deviceId = "";
    state.token = "";
    state.activity = [];
    state.seenAcks.clear();
    el.databaseUrl.value = "";
    el.deviceId.value = "";
    el.token.value = "";
    saveStorage();
    renderDisconnected();
    showToast("Device disconnected from this browser", "success");
  }

  function startPolling() {
    stopPolling();
    state.pollTimer = window.setInterval(() => poll(false), 4000);
    poll(false);
  }

  function stopPolling() {
    if (state.pollTimer !== null) {
      window.clearInterval(state.pollTimer);
      state.pollTimer = null;
    }
  }

  async function poll(showFeedback) {
    if (!state.connected) return;
    const results = await Promise.allSettled([
      databaseRequest(["status"]),
      databaseRequest(["acks", state.token])
    ]);
    const statusResult = results[0];
    const ackResult = results[1];
    if (statusResult.status === "fulfilled") {
      state.status = statusResult.value;
      state.pollError = "";
      renderStatus();
    } else {
      state.pollError = statusResult.reason?.message || "Unable to read device status";
      renderStatus();
    }
    if (ackResult.status === "fulfilled") {
      consumeAcks(ackResult.value);
    }
    if (showFeedback) {
      showToast(statusResult.status === "fulfilled" ? "Workspace refreshed" : state.pollError, statusResult.status === "fulfilled" ? "success" : "error");
    }
  }

  async function sendCommand(type, payload, label) {
    if (!state.connected) {
      showToast("Pair a device before sending commands", "error");
      return false;
    }
    const commandId = makeId();
    const command = {
      id: commandId,
      type,
      payload,
      createdAt: Date.now()
    };
    setActionBusy(true);
    try {
      await databaseRequest(["inbox", state.token, commandId], "PUT", command);
      addActivity(label, "Command queued for the Android app", "pending", command.createdAt, commandId);
      showToast(`${label} queued`, "success");
      return true;
    } catch (error) {
      addActivity(label, error.message, "failed", Date.now(), commandId);
      showToast(error.message, "error");
      return false;
    } finally {
      setActionBusy(false);
    }
  }

  function consumeAcks(data) {
    if (!data || typeof data !== "object") return;
    Object.entries(data)
      .sort(([, a], [, b]) => Number(a?.completedAt || 0) - Number(b?.completedAt || 0))
      .forEach(([key, ack]) => {
        if (!ack || state.seenAcks.has(key)) return;
        state.seenAcks.add(key);
        const ok = Boolean(ack.ok);
        const label = humanCommand(ack.type || "command");
        const message = ack.message || (ok ? "Command completed" : "Command failed");
        const existing = state.activity.find((item) => item.id === ack.commandId);
        if (existing) {
          existing.status = ok ? "success" : "failed";
          existing.detail = message;
          existing.time = Number(ack.completedAt || Date.now());
        } else {
          addActivity(label, message, ok ? "success" : "failed", Number(ack.completedAt || Date.now()), ack.commandId || key);
        }
      });
    renderActivity();
  }

  async function databaseRequest(parts, method = "GET", body = undefined) {
    if (!state.databaseUrl) throw new Error("Database URL is not configured");
    const path = [state.deviceId, ...parts]
      .map((part) => encodeURIComponent(String(part)))
      .join("/");
    const response = await fetch(`${state.databaseUrl}/devices/${path}.json`, {
      method,
      cache: "no-store",
      headers: body === undefined ? { Accept: "application/json" } : { Accept: "application/json", "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); } catch { data = text; }
    }
    if (!response.ok) {
      const reason = typeof data === "object" && data?.error ? data.error : `Database request failed (${response.status})`;
      throw new Error(reason);
    }
    return data;
  }

  function renderStatus() {
    const status = state.status || {};
    const lastSeenNumber = Number(status.lastSeen || 0);
    const online = Boolean(status.online) && lastSeenNumber > 0 && Date.now() - lastSeenNumber < 20000;
    const error = Boolean(state.pollError);
    el.deviceName.textContent = status.deviceName || "Android device";
    el.deviceIdShort.textContent = state.deviceId ? `${state.deviceId.slice(0, 8)}…` : "—";
    el.appVersion.textContent = status.appVersion ? `Andrat Control ${status.appVersion}` : "Andrat Control";
    el.connectionDetail.textContent = error ? "Panel cannot reach database" : online ? "Connection is live" : status.lastSeen ? "App heartbeat is stale" : "Waiting for the app";
    el.lastSeen.textContent = error ? state.pollError : status.lastSeen ? `Last heartbeat ${relativeTime(lastSeenNumber)}` : "Start the service in the app";
    el.capabilities.textContent = status.supports ? `${String(status.supports).split(",").length} actions` : "Waiting";
    const permissionLabels = [];
    if (status.cameraPermission) permissionLabels.push("flashlight");
    if (status.notificationPermission) permissionLabels.push("notices");
    el.permissions.textContent = permissionLabels.length ? permissionLabels.join(" + ") : status.deviceId ? "Needs approval" : "Waiting";
    el.dashboardStatus.className = `dashboard-status ${error || !online ? (error ? "error" : "offline") : ""}`;
    el.dashboardStatusLabel.textContent = error ? "Error" : online ? "Online" : "Waiting";
    el.pairState.textContent = error ? "ERROR" : online ? "ONLINE" : "CONNECTED";
    updateFlashlightButton();
  }

  function renderDisconnected() {
    el.emptyState.classList.remove("hidden");
    el.dashboard.classList.add("hidden");
    el.disconnectButton.classList.add("hidden");
    el.connectButton.classList.remove("hidden");
    el.pairState.textContent = "NOT CONNECTED";
    setActionBusy(false);
  }

  function showDashboard() {
    el.emptyState.classList.add("hidden");
    el.dashboard.classList.remove("hidden");
    el.disconnectButton.classList.remove("hidden");
    el.connectButton.classList.add("hidden");
  }

  function setConnectBusy(busy) {
    el.connectButton.disabled = busy;
    el.connectButton.querySelector("span").textContent = busy ? "Connecting…" : "Connect to device";
  }

  function setActionBusy(busy) {
    [el.pingButton, el.vibrateButton, el.flashlightButton, el.noticeButton].forEach((button) => {
      button.disabled = busy || !state.connected;
    });
  }

  function updateFlashlightButton() {
    el.flashlightButton.innerHTML = state.flashEnabled
      ? "Turn flashlight off <span aria-hidden=\"true\">→</span>"
      : "Turn flashlight on <span aria-hidden=\"true\">→</span>";
  }

  function addActivity(title, detail, status, time, id) {
    const existing = id && state.activity.find((item) => item.id === id);
    if (existing) {
      existing.title = title;
      existing.detail = detail;
      existing.status = status;
      existing.time = time;
    } else {
      state.activity.unshift({ id: id || makeId(), title, detail, status, time });
    }
    state.activity = state.activity.slice(0, 10);
    renderActivity();
  }

  function renderActivity() {
    if (!state.activity.length) {
      el.activityList.innerHTML = '<div class="activity-empty">No commands sent in this panel yet.</div>';
      return;
    }
    el.activityList.innerHTML = state.activity.map((item) => `
      <div class="activity-item ${item.status === "failed" ? "failed" : ""}">
        <i aria-hidden="true"></i>
        <div class="activity-copy"><strong>${escapeHtml(item.title)}</strong><small>${escapeHtml(item.detail)}</small></div>
        <time class="activity-time">${escapeHtml(relativeTime(item.time))}</time>
      </div>
    `).join("");
  }

  function toggleTokenVisibility() {
    const visible = el.token.type === "text";
    el.token.type = visible ? "password" : "text";
    el.toggleToken.textContent = visible ? "Show" : "Hide";
    el.toggleToken.setAttribute("aria-label", visible ? "Show control token" : "Hide control token");
  }

  function normalizeUrl(value) {
    let url = String(value || "").trim().replace(/\/+$/, "");
    if (url.endsWith(".json")) url = url.slice(0, -5);
    if (!/^https:\/\/[^\s]+$/i.test(url)) {
      throw new Error("Enter a valid HTTPS Firebase Realtime Database URL");
    }
    return url;
  }

  function relativeTime(timestamp) {
    const delta = Math.max(0, Date.now() - Number(timestamp || 0));
    if (delta < 5000) return "just now";
    if (delta < 60000) return `${Math.floor(delta / 1000)}s ago`;
    if (delta < 3600000) return `${Math.floor(delta / 60000)}m ago`;
    return new Date(timestamp).toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
  }

  function humanCommand(type) {
    return {
      ping: "Ping device",
      vibrate: "Vibrate",
      flashlight: "Flashlight",
      notify: "Show device notice",
      status: "Refresh status"
    }[type] || "Remote command";
  }

  function makeId() {
    if (window.crypto?.randomUUID) return window.crypto.randomUUID();
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }

  function saveStorage() {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify({
      databaseUrl: state.databaseUrl,
      deviceId: state.deviceId,
      token: state.token
    }));
  }

  function readStorage() {
    try { return JSON.parse(window.localStorage.getItem(STORAGE_KEY) || "{}"); } catch { return {}; }
  }

  function escapeHtml(value) {
    return String(value).replace(/[&<>'"]/g, (character) => ({
      "&": "&amp;", "<": "&lt;", ">": "&gt;", "'": "&#39;", '"': "&quot;"
    }[character]));
  }

  function showToast(message, type) {
    let toast = document.querySelector(".toast");
    if (!toast) {
      toast = document.createElement("div");
      toast.className = "toast";
      document.body.appendChild(toast);
    }
    toast.textContent = message;
    toast.dataset.type = type || "success";
    toast.classList.add("visible");
    window.clearTimeout(showToast.timeout);
    showToast.timeout = window.setTimeout(() => toast.classList.remove("visible"), 3600);
  }
})();
