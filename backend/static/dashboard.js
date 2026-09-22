"use strict";

const ui = {
  token: document.getElementById("token"),
  connect: document.getElementById("connect"),
  status: document.getElementById("connection-status"),
  session: document.getElementById("session"),
  freshness: document.getElementById("freshness"),
  position: document.getElementById("position"),
  speed: document.getElementById("speed"),
  heading: document.getElementById("heading"),
  accuracy: document.getElementById("accuracy"),
  altitude: document.getElementById("altitude"),
  count: document.getElementById("event-count"),
  sequence: document.getElementById("sequence"),
  time: document.getElementById("event-time"),
};
let token = "";
let selectedSession = "";
let polling = false;
let timer = null;

function status(message, kind = "") {
  ui.status.textContent = message;
  ui.status.className = "notice " + kind;
}

function numeric(value, digits, suffix = "") {
  return typeof value === "number" && Number.isFinite(value)
    ? value.toFixed(digits) + suffix
    : "—";
}

async function api(path) {
  const response = await fetch(path, {
    headers: { Authorization: "Bearer " + token },
    cache: "no-store",
  });
  if (response.status === 401) throw new Error("Token not accepted. Enter the current token and reconnect.");
  if (!response.ok) throw new Error("Backend returned HTTP " + response.status);
  return response.json();
}

function updateSessions(sessions) {
  const currentIds = Array.from(ui.session.options).map(option => option.value);
  const nextIds = sessions.map(item => item.sessionId);
  const sameIds = currentIds.length === nextIds.length &&
    currentIds.every((id, index) => id === nextIds[index]);
  if (!sameIds) {
    ui.session.replaceChildren();
    for (const item of sessions) {
      const option = document.createElement("option");
      option.value = item.sessionId;
      ui.session.append(option);
    }
  }
  sessions.forEach((item, index) => {
    ui.session.options[index].textContent =
      item.sessionId.slice(0, 8) + " · " + item.eventCount + " events";
  });
  if (!sessions.some(item => item.sessionId === selectedSession)) {
    selectedSession = sessions[0]?.sessionId || "";
  }
  ui.session.disabled = !selectedSession;
  if (selectedSession) ui.session.value = selectedSession;
}

function showLatest(event, summary) {
  const payload = event.payload || {};
  const lat = numeric(payload.latitudeDegrees, 6);
  const lon = numeric(payload.longitudeDegrees, 6);
  ui.position.textContent = lat === "—" || lon === "—" ? "—" : lat + ", " + lon;
  ui.speed.textContent = numeric(payload.speedMetersPerSecond, 1);
  ui.heading.textContent = numeric(payload.bearingDegrees, 0, "°");
  ui.accuracy.textContent = numeric(payload.horizontalAccuracyMeters, 1);
  ui.altitude.textContent = numeric(payload.altitudeMeters, 1);
  ui.count.textContent = String(summary.eventCount);
  ui.sequence.textContent = "Latest sequence " + event.sequence;
  const millis = event.timestamp?.utcEpochMillis;
  ui.time.textContent = Number.isFinite(millis)
    ? new Date(millis).toLocaleString()
    : "—";
  const age = Number.isFinite(millis) ? Math.max(0, Math.round((Date.now() - millis) / 1000)) : null;
  ui.freshness.textContent = age === null
    ? "Latest event time unavailable."
    : age <= 10 ? "Receiving recent telemetry · latest event " + age + " s ago"
      : "No recent event · latest event " + age + " s ago";
}

async function refresh() {
  if (!token || polling) return;
  polling = true;
  try {
    const list = await api("/v1/sessions");
    const sessions = list.sessions || [];
    updateSessions(sessions);
    if (!selectedSession) {
      ui.freshness.textContent = "No mission events received yet.";
      status("Connected. Waiting for the first mission event.", "ok");
      return;
    }
    const latest = await api("/v1/latest?sessionId=" + encodeURIComponent(selectedSession));
    const summary = sessions.find(item => item.sessionId === selectedSession);
    showLatest(latest.event, summary);
    status("Connected to local backend · " + sessions.length + " session(s) available", "ok");
  } catch (error) {
    status(error.message || "Could not read telemetry.", "error");
    if (String(error.message).startsWith("Token not accepted")) {
      token = "";
      clearInterval(timer);
      timer = null;
    }
  } finally {
    polling = false;
  }
}

ui.connect.addEventListener("click", () => {
  const entered = ui.token.value.trim();
  if (!entered) {
    status("Enter the current backend token.", "error");
    return;
  }
  token = entered;
  ui.token.value = "";
  status("Connecting…");
  refresh();
  if (!timer) timer = setInterval(refresh, 1000);
});
ui.token.addEventListener("keydown", event => {
  if (event.key === "Enter") ui.connect.click();
});
ui.session.addEventListener("change", () => {
  selectedSession = ui.session.value;
  refresh();
});
