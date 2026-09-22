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
  map: document.getElementById("track-map"),
  mapStatus: document.getElementById("map-status"),
  mapFixes: document.getElementById("map-fixes"),
  mapInside: document.getElementById("map-inside"),
  mapScale: document.getElementById("map-scale"),
};
let token = "";
let selectedSession = "";
let polling = false;
let timer = null;
let historySession = "";
let historySequence = -1;

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

// WGS84 to SWEREF 99 TM (EPSG:3006), a UTM-like transverse Mercator projection.
function sweref99(latitude, longitude) {
  const a = 6378137, f = 1 / 298.257223563, k = 0.9996;
  const e2 = f * (2 - f), ep2 = e2 / (1 - e2);
  const phi = latitude * Math.PI / 180, lambda = (longitude - 15) * Math.PI / 180;
  const sin = Math.sin(phi), cos = Math.cos(phi), tan = Math.tan(phi);
  const n = a / Math.sqrt(1 - e2 * sin * sin);
  const t = tan * tan, c = ep2 * cos * cos, A = lambda * cos;
  const m = a * ((1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256) * phi
    - (3 * e2 / 8 + 3 * e2 ** 2 / 32 + 45 * e2 ** 3 / 1024) * Math.sin(2 * phi)
    + (15 * e2 ** 2 / 256 + 45 * e2 ** 3 / 1024) * Math.sin(4 * phi)
    - 35 * e2 ** 3 / 3072 * Math.sin(6 * phi));
  const east = 500000 + k * n * (A + (1 - t + c) * A ** 3 / 6
    + (5 - 18 * t + t * t + 72 * c - 58 * ep2) * A ** 5 / 120);
  const north = k * (m + n * tan * (A * A / 2
    + (5 - t + 9 * c + 4 * c * c) * A ** 4 / 24
    + (61 - 58 * t + t * t + 600 * c - 330 * ep2) * A ** 6 / 720));
  return {east, north};
}

function svgElement(name, attributes) {
  const element = document.createElementNS("http://www.w3.org/2000/svg", name);
  for (const [key, value] of Object.entries(attributes)) element.setAttribute(key, String(value));
  return element;
}

function drawTrack(events) {
  const tile = {west: 535000, east: 537500, south: 6470000, north: 6472500};
  const fixes = events.filter(item => item.eventType === "navigation.gnss")
    .map(item => ({...item.payload, sequence: item.sequence}))
    .filter(item => Number.isFinite(item.latitudeDegrees) && Number.isFinite(item.longitudeDegrees)
      && Math.abs(item.latitudeDegrees) <= 90 && Math.abs(item.longitudeDegrees) <= 180)
    .map(item => ({...sweref99(item.latitudeDegrees, item.longitudeDegrees), sequence: item.sequence}));
  const inside = fixes.filter(p => p.east >= tile.west && p.east <= tile.east
    && p.north >= tile.south && p.north <= tile.north).length;
  const eastings = [tile.west, tile.east, ...fixes.map(p => p.east)];
  const northings = [tile.south, tile.north, ...fixes.map(p => p.north)];
  const minE = Math.min(...eastings), maxE = Math.max(...eastings);
  const minN = Math.min(...northings), maxN = Math.max(...northings);
  const width = 900, height = 500, pad = 40;
  const metresPerPixel = Math.max((maxE - minE) / (width - 2 * pad),
    (maxN - minN) / (height - 2 * pad)) * 1.12;
  const centerE = (minE + maxE) / 2, centerN = (minN + maxN) / 2;
  const x = e => width / 2 + (e - centerE) / metresPerPixel;
  const y = n => height / 2 - (n - centerN) / metresPerPixel;
  ui.map.replaceChildren();
  const grid = svgElement("g", {stroke: "#e4eded", "stroke-width": 1});
  for (let i = 1; i < 5; i++) {
    grid.append(svgElement("line", {x1: i * width / 5, y1: 0, x2: i * width / 5, y2: height}));
    grid.append(svgElement("line", {x1: 0, y1: i * height / 5, x2: width, y2: i * height / 5}));
  }
  ui.map.append(grid);
  ui.map.append(svgElement("rect", {x: x(tile.west), y: y(tile.north),
    width: 2500 / metresPerPixel, height: 2500 / metresPerPixel,
    fill: "#b8dfc4", "fill-opacity": 0.55, stroke: "#28845a", "stroke-width": 2}));
  if (fixes.length) {
    ui.map.append(svgElement("polyline", {points: fixes.map(p => `${x(p.east)},${y(p.north)}`).join(" "),
      fill: "none", stroke: "#1669a6", "stroke-width": 3, "stroke-linejoin": "round"}));
    for (const [index, color] of [[0, "#165d91"], [fixes.length - 1, "#e17036"]]) {
      const point = fixes[index];
      ui.map.append(svgElement("circle", {cx: x(point.east), cy: y(point.north), r: 6,
        fill: color, stroke: "white", "stroke-width": 2}));
    }
  }
  const tileLabel = svgElement("text", {x: x(tile.west) + 8, y: y(tile.north) + 22,
    fill: "#185b3d", "font-size": 14, "font-weight": 700});
  tileLabel.textContent = "DEM 6472500_535000";
  ui.map.append(tileLabel);
  ui.mapStatus.textContent = fixes.length ? "Track and DEM tile in metres · north is up"
    : "No valid GNSS positions in this session.";
  ui.mapFixes.textContent = fixes.length + " GNSS fixes";
  ui.mapInside.textContent = inside + " inside DEM tile";
  ui.mapScale.textContent = "Map width ≈ " + Math.round(width * metresPerPixel / 100) / 10 + " km";
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
    if (historySession !== selectedSession || historySequence !== summary.lastSequence) {
      const history = await api("/v1/session-events?sessionId=" + encodeURIComponent(selectedSession));
      drawTrack(history.events);
      historySession = selectedSession;
      historySequence = summary.lastSequence;
    }
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
