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
  pressure: document.getElementById("pressure"),
  heightChart: document.getElementById("height-chart"),
  heightStatus: document.getElementById("height-status"),
  particleCanvas: document.getElementById("particle-canvas"),
  particleStatus: document.getElementById("particle-status"),
  particlePlay: document.getElementById("particle-play"),
  particleSpeed: document.getElementById("particle-speed"),
  particleModel: document.getElementById("particle-model"),
  groundClearance: document.getElementById("ground-clearance"),
  particleFrame: document.getElementById("particle-frame"),
  count: document.getElementById("event-count"),
  sequence: document.getElementById("sequence"),
  time: document.getElementById("event-time"),
  map: document.getElementById("track-map"),
  mapStatus: document.getElementById("map-status"),
  mapFixes: document.getElementById("map-fixes"),
  mapInside: document.getElementById("map-inside"),
  mapScale: document.getElementById("map-scale"),
  mapToggle: document.getElementById("map-toggle"),
  topoToggle: document.getElementById("topo-toggle"),
  zoomIn: document.getElementById("zoom-in"),
  zoomOut: document.getElementById("zoom-out"),
  fitMap: document.getElementById("fit-map"),
  attribution: document.getElementById("map-attribution"),
};
let token = "";
let selectedSession = "";
let polling = false;
let timer = null;
let historySession = "";
let historySequence = -1;
let latestGnss = null;
let latestPressure = null;
const demTile = {west: 535000, east: 537500, south: 6470000, north: 6472500};
let fixesOnMap = [];
let view = {east: 536250, north: 6471250, metresPerPixel: 6.3};
let mapEnabled = false;
let topographyEnabled = false;
let topographyUrl = "";
let dragStart = null;
let particleReplay = null;
let particleFrameIndex = 0;
let particleTimer = null;
let particleTopographyImage = null;

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

function drawHeight(events, terrain = [], demAvailable = false, verticalFilter = null) {
  const gnss = events.filter(item => item.eventType === "navigation.gnss")
    .map(item => ({t: item.timestamp?.utcEpochMillis, h: item.payload?.altitudeMeters}))
    .filter(item => Number.isFinite(item.t) && Number.isFinite(item.h));
  const pressure = events.filter(item => item.eventType === "navigation.pressure")
    .map(item => ({t: item.timestamp?.utcEpochMillis, p: item.payload?.pressureHectopascals}))
    .filter(item => Number.isFinite(item.t) && Number.isFinite(item.p) && item.p > 0);
  ui.heightChart.replaceChildren();
  const dem = terrain.filter(item => Number.isFinite(item.terrainMeters))
    .map(item => ({t: item.utcEpochMillis, h: item.terrainMeters}));
  if (!gnss.length && !dem.length) {
    ui.heightStatus.textContent = pressure.length
      ? "Pressure is present, but GNSS altitude is needed to anchor the barometric estimate."
      : "No height measurements in this session.";
    return;
  }
  const anchor = gnss[0];
  const reference = pressure[0];
  const baro = anchor && reference ? pressure.map(item => ({
    t: item.t, h: anchor.h + 8434.5 * Math.log(reference.p / item.p),
  })) : [];
  const clearance = verticalFilter?.groundClearanceMeters ?? 0.4;
  const kf = (verticalFilter?.estimates || []).map(item => ({
    t: item.utcEpochMillis, h: item.heightMeters - clearance, sequence: item.sequence,
  })).filter(item => Number.isFinite(item.t) && Number.isFinite(item.h));
  const points = gnss.concat(baro, dem, kf);
  const t0 = Math.min(...points.map(item => item.t));
  const t1 = Math.max(...points.map(item => item.t));
  const h0 = Math.min(...points.map(item => item.h));
  const h1 = Math.max(...points.map(item => item.h));
  const low = h0 - Math.max(2, (h1 - h0) * .1);
  const high = h1 + Math.max(2, (h1 - h0) * .1);
  const x = t => 62 + 815 * (t - t0) / Math.max(1000, t1 - t0);
  const y = h => 260 - 225 * (h - low) / (high - low);
  for (let i = 0; i <= 4; i++) {
    const height = low + (high - low) * i / 4;
    const yy = y(height);
    ui.heightChart.append(svgElement("line", {x1: 62, x2: 878, y1: yy, y2: yy, stroke: "#dce7e7"}));
    const label = svgElement("text", {x: 55, y: yy + 4, "text-anchor": "end", fill: "#587074", "font-size": 12});
    label.textContent = height.toFixed(0);
    ui.heightChart.append(label);
  }
  for (const [value, labelText] of [[t0, "0"], [t1, ((t1 - t0) / 1000).toFixed(0) + " s"]]) {
    const label = svgElement("text", {x: x(value), y: 287, "text-anchor": value === t0 ? "start" : "end", fill: "#587074", "font-size": 12});
    label.textContent = labelText;
    ui.heightChart.append(label);
  }
  function line(data, color) {
    if (!data.length) return;
    const d = data.map((item, index) => `${index ? "L" : "M"}${x(item.t).toFixed(1)},${y(item.h).toFixed(1)}`).join(" ");
    ui.heightChart.append(svgElement("path", {d, fill: "none", stroke: color, "stroke-width": 2.5, "stroke-linejoin": "round"}));
  }
  line(baro, "#df8f35");
  line(gnss, "#176d67");
  line(kf, "#c43b4d");
  if (dem.length) {
    let penDown = false;
    const d = terrain.map(item => {
      if (!Number.isFinite(item.terrainMeters)) { penDown = false; return ""; }
      const point = `${x(item.utcEpochMillis).toFixed(1)},${y(item.terrainMeters).toFixed(1)}`;
      const command = penDown ? `L${point}` : `M${point} l0.1,0`;
      penDown = true;
      return command;
    }).join(" ");
    ui.heightChart.append(svgElement("path", {d, fill: "none", stroke: "#7a4c9b", "stroke-width": 2.5}));
  }
  const kfBySequence = new Map(kf.map(item => [item.sequence, item.h]));
  const differences = terrain.filter(item => Number.isFinite(item.terrainMeters) && kfBySequence.has(item.sequence))
    .map(item => kfBySequence.get(item.sequence) - item.terrainMeters);
  let comparison = "";
  if (differences.length) {
    const mean = differences.reduce((sum, value) => sum + value, 0) / differences.length;
    const variance = differences.reduce((sum, value) => sum + (value - mean) ** 2, 0) /
      Math.max(1, differences.length - 1);
    comparison = ` · EKF ground − DEM: ${mean.toFixed(1)} ± ${Math.sqrt(variance).toFixed(1)} m`;
  }
  const rejected = verticalFilter?.rejectedMeasurements || {};
  const gateSummary = verticalFilter
    ? ` · 3σ rejected: ${rejected.gnss || 0} GNSS, ${rejected.pressure || 0} pressure`
    : "";
  const datumSummary = Number.isFinite(verticalFilter?.gnssDatumOffsetMeters)
    ? ` · GNSS datum correction ${verticalFilter.gnssDatumOffsetMeters.toFixed(1)} m`
    : "";
  ui.heightStatus.textContent = `${gnss.length} GNSS heights · ${baro.length} pressure samples · ${kf.length} EKF estimates · ` +
    (demAvailable ? `${dem.length} GNSS fixes inside DEM tile` : "DEM cache not prepared") + comparison + datumSummary + gateSummary;
}

function stopParticleReplay() {
  if (particleTimer) clearTimeout(particleTimer);
  particleTimer = null;
  ui.particlePlay.textContent = "Play";
}

function particlePoint(east, north) {
  const padding = 32;
  const span = 800 - 2 * padding;
  return {
    x: padding + (east - demTile.west) / (demTile.east - demTile.west) * span,
    y: padding + (demTile.north - north) / (demTile.north - demTile.south) * span,
  };
}

function drawParticleFrame() {
  const canvas = ui.particleCanvas;
  const context = canvas.getContext("2d");
  context.clearRect(0, 0, canvas.width, canvas.height);
  context.fillStyle = "#f8fbfa";
  context.fillRect(0, 0, canvas.width, canvas.height);
  if (particleTopographyImage) context.drawImage(particleTopographyImage, 32, 32, 736, 736);
  context.strokeStyle = "#6e9c82";
  context.lineWidth = 2;
  context.strokeRect(32, 32, 736, 736);
  if (!particleReplay?.frames?.length) return;
  const frames = particleReplay.frames;
  const frame = frames[particleFrameIndex];
  function trail(keyEast, keyNorth, color, dash = []) {
    context.beginPath();
    for (let i = 0; i <= particleFrameIndex; i++) {
      const point = particlePoint(frames[i][keyEast], frames[i][keyNorth]);
      if (i) context.lineTo(point.x, point.y); else context.moveTo(point.x, point.y);
    }
    context.strokeStyle = color;
    context.lineWidth = 2;
    context.setLineDash(dash);
    context.stroke();
    context.setLineDash([]);
  }
  trail("trueEast", "trueNorth", "rgba(22,114,88,.7)");
  trail("drStartEast", "drStartNorth", "rgba(42,52,55,.7)", [8,5]);
  trail("dr30East", "dr30North", "rgba(34,155,180,.8)", [3,4]);
  trail("meanEast", "meanNorth", "rgba(196,59,77,.75)");
  trail("mapEast", "mapNorth", "rgba(224,126,38,.68)");
  context.fillStyle = "rgba(90,110,110,.13)";
  for (const particle of (frames[0].initialParticles || frames[0].particles)) {
    const point = particlePoint(particle[0], particle[1]);
    context.fillRect(point.x - 1, point.y - 1, 2, 2);
  }
  for (const particle of frame.particles) {
    const point = particlePoint(particle[0], particle[1]);
    const alpha = .12 + .78 * Math.sqrt(Math.max(0, Math.min(1, particle[2])));
    context.fillStyle = `rgba(52,120,191,${alpha})`;
    context.fillRect(point.x - 1.5, point.y - 1.5, 3, 3);
  }
  const estimate = particlePoint(frame.meanEast, frame.meanNorth);
  const mapEstimate = particlePoint(frame.mapEast, frame.mapNorth);
  const deadReckoning = particlePoint(frame.drStartEast, frame.drStartNorth);
  const deadReckoning30 = particlePoint(frame.dr30East, frame.dr30North);
  const truth = particlePoint(frame.trueEast, frame.trueNorth);
  context.fillStyle = "#c43b4d";
  context.beginPath(); context.arc(estimate.x, estimate.y, 6, 0, Math.PI * 2); context.fill();
  context.strokeStyle = "#ffffff"; context.lineWidth = 2; context.stroke();
  context.fillStyle = "#e07e26";
  context.beginPath(); context.moveTo(mapEstimate.x, mapEstimate.y - 7); context.lineTo(mapEstimate.x + 7, mapEstimate.y);
  context.lineTo(mapEstimate.x, mapEstimate.y + 7); context.lineTo(mapEstimate.x - 7, mapEstimate.y); context.closePath(); context.fill();
  context.strokeStyle = "#ffffff"; context.lineWidth = 2; context.stroke();
  context.fillStyle = "#2a3437"; context.fillRect(deadReckoning.x - 4, deadReckoning.y - 4, 8, 8);
  context.fillStyle = "#229bb4"; context.fillRect(deadReckoning30.x - 4, deadReckoning30.y - 4, 8, 8);
  context.strokeStyle = "#167258"; context.lineWidth = 3;
  context.beginPath(); context.moveTo(truth.x - 7, truth.y); context.lineTo(truth.x + 7, truth.y);
  context.moveTo(truth.x, truth.y - 7); context.lineTo(truth.x, truth.y + 7); context.stroke();
  const elapsed = (frame.utcEpochMillis - frames[0].utcEpochMillis) / 1000;
  ui.particleStatus.textContent = `t ${elapsed.toFixed(0)} s · MMSE ${frame.mmseErrorMeters.toFixed(1)} m · MAP ${frame.mapErrorMeters.toFixed(1)} m` +
    ` · DR-start ${frame.drStartErrorMeters.toFixed(1)} m · DR-${frame.dr30HorizonSeconds.toFixed(0)}s ${frame.dr30ErrorMeters.toFixed(1)} m` +
    (Number.isFinite(frame.meanSpeedMetersPerSecond) ? " · speed " + frame.meanSpeedMetersPerSecond.toFixed(1) + " [" + frame.minSpeedMetersPerSecond.toFixed(1) + ", " + frame.maxSpeedMetersPerSecond.toFixed(1) + "] m/s" : "") +
    (Number.isFinite(frame.meanAccelerationBiasMetersPerSecond2) ? " · acc bias " + frame.meanAccelerationBiasMetersPerSecond2.toFixed(3) + " m/s²" : "") +
    (Number.isFinite(frame.terrainLikelihoodStdMeters) ? " · height σ " + frame.terrainLikelihoodStdMeters.toFixed(2) + " m" : "") +
    ` · ESS ${frame.effectiveParticleCount.toFixed(0)}/${particleReplay.parameters.particleCount} · ${frame.resampled ? "resampled" : "no resampling"}`;
  ui.particleFrame.value = String(particleFrameIndex);
}

function scheduleParticleFrame() {
  if (!particleTimer || !particleReplay) return;
  if (particleFrameIndex >= particleReplay.frames.length - 1) {
    stopParticleReplay();
    return;
  }
  particleFrameIndex++;
  drawParticleFrame();
  const speed = Number(ui.particleSpeed.value) || 10;
  particleTimer = setTimeout(scheduleParticleFrame, 1000 / speed);
}

async function loadParticleTopography() {
  if (!topographyUrl && !(await loadTopography())) return;
  const image = new Image();
  image.onload = () => { particleTopographyImage = image; drawParticleFrame(); };
  image.src = topographyUrl;
}

function setParticleReplay(data) {
  stopParticleReplay();
  particleReplay = data;
  particleFrameIndex = 0;
  const count = data?.frames?.length || 0;
  ui.particleFrame.max = String(Math.max(0, count - 1));
  ui.particleFrame.value = "0";
  ui.particleFrame.disabled = !count;
  ui.particlePlay.disabled = !count;
  drawParticleFrame();
  if (count) loadParticleTopography();
}

function showLatest(event, summary) {
  const payload = latestGnss?.payload || {};
  const pressurePayload = latestPressure?.payload || {};
  const lat = numeric(payload.latitudeDegrees, 6);
  const lon = numeric(payload.longitudeDegrees, 6);
  ui.position.textContent = lat === "—" || lon === "—" ? "—" : lat + ", " + lon;
  ui.speed.textContent = numeric(payload.speedMetersPerSecond, 1);
  ui.heading.textContent = numeric(payload.bearingDegrees, 0, "°");
  ui.accuracy.textContent = numeric(payload.horizontalAccuracyMeters, 1);
  ui.altitude.textContent = numeric(payload.altitudeMeters, 1);
  ui.pressure.textContent = numeric(pressurePayload.pressureHectopascals, 2);
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

// Inverse of the same WGS84 transverse Mercator projection, for selecting map tiles.
function wgs84(east, north) {
  const a = 6378137, f = 1 / 298.257223563, k = 0.9996;
  const e2 = f * (2 - f), ep2 = e2 / (1 - e2);
  const e1 = (1 - Math.sqrt(1 - e2)) / (1 + Math.sqrt(1 - e2));
  const mu = north / (k * a * (1 - e2 / 4 - 3 * e2 ** 2 / 64 - 5 * e2 ** 3 / 256));
  const phi1 = mu + (3 * e1 / 2 - 27 * e1 ** 3 / 32) * Math.sin(2 * mu)
    + (21 * e1 ** 2 / 16 - 55 * e1 ** 4 / 32) * Math.sin(4 * mu)
    + 151 * e1 ** 3 / 96 * Math.sin(6 * mu) + 1097 * e1 ** 4 / 512 * Math.sin(8 * mu);
  const sin = Math.sin(phi1), cos = Math.cos(phi1), tan = Math.tan(phi1);
  const C = ep2 * cos * cos, T = tan * tan;
  const N = a / Math.sqrt(1 - e2 * sin * sin);
  const R = a * (1 - e2) / (1 - e2 * sin * sin) ** 1.5;
  const D = (east - 500000) / (N * k);
  const latitude = phi1 - (N * tan / R) * (D ** 2 / 2
    - (5 + 3 * T + 10 * C - 4 * C * C - 9 * ep2) * D ** 4 / 24
    + (61 + 90 * T + 298 * C + 45 * T * T - 252 * ep2 - 3 * C * C) * D ** 6 / 720);
  const longitude = 15 * Math.PI / 180 + (D - (1 + 2 * T + C) * D ** 3 / 6
    + (5 - 2 * C + 28 * T - 3 * C * C + 8 * ep2 + 24 * T * T) * D ** 5 / 120) / cos;
  return {latitude: latitude * 180 / Math.PI, longitude: longitude * 180 / Math.PI};
}

function tileNumber(latitude, longitude, zoom) {
  const n = 2 ** zoom;
  const lat = Math.max(-85.0511, Math.min(85.0511, latitude)) * Math.PI / 180;
  return {x: (longitude + 180) / 360 * n,
    y: (1 - Math.asinh(Math.tan(lat)) / Math.PI) / 2 * n};
}

function tileCorner(x, y, zoom) {
  const n = 2 ** zoom;
  return {latitude: Math.atan(Math.sinh(Math.PI * (1 - 2 * y / n))) * 180 / Math.PI,
    longitude: x / n * 360 - 180};
}

function fitDem() {
  view = {east: (demTile.west + demTile.east) / 2,
    north: (demTile.south + demTile.north) / 2, metresPerPixel: 6.3};
  renderMap();
}

function addMapTiles(x, y) {
  const center = wgs84(view.east, view.north);
  const idealZoom = Math.log2(156543.03392 * Math.cos(center.latitude * Math.PI / 180)
    / view.metresPerPixel);
  const zoom = Math.max(0, Math.min(17, Math.round(idealZoom)));
  const corners = [[0, 0], [900, 0], [0, 500], [900, 500]].map(([sx, sy]) =>
    wgs84(view.east + (sx - 450) * view.metresPerPixel,
      view.north - (sy - 250) * view.metresPerPixel));
  const tiles = corners.map(p => tileNumber(p.latitude, p.longitude, zoom));
  const left = Math.max(0, Math.floor(Math.min(...tiles.map(p => p.x))) - 1);
  const right = Math.min(2 ** zoom - 1, Math.floor(Math.max(...tiles.map(p => p.x))) + 1);
  const top = Math.max(0, Math.floor(Math.min(...tiles.map(p => p.y))) - 1);
  const bottom = Math.min(2 ** zoom - 1, Math.floor(Math.max(...tiles.map(p => p.y))) + 1);
  if ((right - left + 1) * (bottom - top + 1) > 64) {
    ui.mapStatus.textContent = "Map area too wide; zoom in to load background images.";
    return;
  }
  const group = svgElement("g", {"aria-label": "OpenStreetMap background"});
  for (let ty = top; ty <= bottom; ty++) for (let tx = left; tx <= right; tx++) {
    const nw = tileCorner(tx, ty, zoom), ne = tileCorner(tx + 1, ty, zoom);
    const sw = tileCorner(tx, ty + 1, zoom);
    const p0 = sweref99(nw.latitude, nw.longitude);
    const p1 = sweref99(ne.latitude, ne.longitude);
    const p2 = sweref99(sw.latitude, sw.longitude);
    const x0 = x(p0.east), y0 = y(p0.north);
    const a = (x(p1.east) - x0) / 256, b = (y(p1.north) - y0) / 256;
    const c = (x(p2.east) - x0) / 256, d = (y(p2.north) - y0) / 256;
    group.append(svgElement("image", {href: `https://tile.openstreetmap.org/${zoom}/${tx}/${ty}.png`,
      width: 256, height: 256, transform: `matrix(${a} ${b} ${c} ${d} ${x0} ${y0})`}));
  }
  ui.map.append(group);
}

function renderMap() {
  const width = 900, height = 500;
  const x = e => width / 2 + (e - view.east) / view.metresPerPixel;
  const y = n => height / 2 - (n - view.north) / view.metresPerPixel;
  ui.map.replaceChildren();
  if (mapEnabled) addMapTiles(x, y);
  if (topographyEnabled && topographyUrl) {
    ui.map.append(svgElement("image", {href: topographyUrl, x: x(demTile.west), y: y(demTile.north),
      width: 2500 / view.metresPerPixel, height: 2500 / view.metresPerPixel,
      preserveAspectRatio: "none", opacity: 1}));
  }
  const grid = svgElement("g", {stroke: "#66848a", "stroke-opacity": mapEnabled ? 0.22 : 0.14});
  for (let i = 1; i < 5; i++) {
    grid.append(svgElement("line", {x1: i * width / 5, y1: 0, x2: i * width / 5, y2: height}));
    grid.append(svgElement("line", {x1: 0, y1: i * height / 5, x2: width, y2: i * height / 5}));
  }
  ui.map.append(grid);
  ui.map.append(svgElement("rect", {x: x(demTile.west), y: y(demTile.north),
    width: 2500 / view.metresPerPixel, height: 2500 / view.metresPerPixel,
    fill: mapEnabled || topographyEnabled ? "none" : "#b8dfc4", "fill-opacity": 0.55,
    stroke: "#16834a", "stroke-width": 3}));
  if (fixesOnMap.length) {
    ui.map.append(svgElement("polyline", {points: fixesOnMap.map(p => `${x(p.east)},${y(p.north)}`).join(" "),
      fill: "none", stroke: "#1669a6", "stroke-width": 4, "stroke-linejoin": "round"}));
    for (const [index, color] of [[0, "#165d91"], [fixesOnMap.length - 1, "#e17036"]]) {
      const point = fixesOnMap[index];
      ui.map.append(svgElement("circle", {cx: x(point.east), cy: y(point.north), r: 7,
        fill: color, stroke: "white", "stroke-width": 2}));
    }
  }
  const label = svgElement("text", {x: x(demTile.west) + 8, y: y(demTile.north) + 22,
    fill: "#064a2b", "font-size": 15, "font-weight": 800,
    stroke: "white", "stroke-width": 3, "paint-order": "stroke"});
  label.textContent = "DEM 6472500_535000";
  ui.map.append(label);
  ui.mapScale.textContent = "Map width ≈ " + Math.round(width * view.metresPerPixel / 100) / 10 + " km";
}

function drawTrack(events) {
  fixesOnMap = events.filter(item => item.eventType === "navigation.gnss")
    .map(item => item.payload || {})
    .filter(item => Number.isFinite(item.latitudeDegrees) && Number.isFinite(item.longitudeDegrees)
      && Math.abs(item.latitudeDegrees) <= 90 && Math.abs(item.longitudeDegrees) <= 180)
    .map(item => sweref99(item.latitudeDegrees, item.longitudeDegrees));
  const inside = fixesOnMap.filter(p => p.east >= demTile.west && p.east <= demTile.east
    && p.north >= demTile.south && p.north <= demTile.north).length;
  ui.mapStatus.textContent = fixesOnMap.length ? "Drag to explore · green outline is the DEM tile"
    : "No valid GNSS positions in this session; DEM tile remains visible.";
  ui.mapFixes.textContent = fixesOnMap.length + " GNSS fixes";
  ui.mapInside.textContent = inside + " inside DEM tile";
  renderMap();
}

async function loadTopography() {
  if (topographyUrl) return true;
  if (!token) {
    ui.mapStatus.textContent = "Connect with the backend token before loading local topography.";
    return false;
  }
  const response = await fetch("/contours.png", {
    headers: {Authorization: "Bearer " + token}, cache: "no-store",
  });
  if (!response.ok) {
    ui.mapStatus.textContent = response.status === 404
      ? "Local topography has not been generated yet." : "Could not load local topography.";
    return false;
  }
  topographyUrl = URL.createObjectURL(await response.blob());
  return true;
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
    if (historySession !== selectedSession || historySequence !== summary.lastSequence) {
      const history = await api("/v1/session-events?sessionId=" + encodeURIComponent(selectedSession));
      latestGnss = null;
      latestPressure = null;
      for (const item of history.events) {
        if (item.eventType === "navigation.gnss") latestGnss = item;
        if (item.eventType === "navigation.pressure") latestPressure = item;
      }
      drawTrack(history.events);
      let terrain = null;
      try {
        terrain = await api("/v1/session-terrain?sessionId=" + encodeURIComponent(selectedSession));
      } catch (error) {
        if (!String(error.message).includes("HTTP 404")) throw error;
      }
      let verticalFilter = null;
      try {
        verticalFilter = await api("/v1/session-vertical-filter?sessionId=" + encodeURIComponent(selectedSession) +
          "&groundClearanceMeters=" + encodeURIComponent(ui.groundClearance.value));
      } catch (error) {
        if (!String(error.message).includes("HTTP 422")) throw error;
      }
      drawHeight(history.events, terrain?.heights || [], terrain !== null, verticalFilter);
      ui.particleStatus.textContent = "Calculating 1,000-particle replay…";
      try {
        const query = "?sessionId=" + encodeURIComponent(selectedSession) +
          "&model=" + encodeURIComponent(ui.particleModel.value) +
          "&groundClearanceMeters=" + encodeURIComponent(ui.groundClearance.value);
        const replay = await api("/v1/session-particle-filter" + query);
        setParticleReplay(replay);
      } catch (error) {
        setParticleReplay(null);
        ui.particleStatus.textContent = String(error.message).includes("HTTP 422")
          ? "This session has no usable GNSS fixes inside the DEM tile."
          : "Particle replay unavailable: " + error.message;
      }
      historySession = selectedSession;
      historySequence = summary.lastSequence;
    }
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

ui.particlePlay.addEventListener("click", () => {
  if (particleTimer) { stopParticleReplay(); return; }
  if (!particleReplay?.frames?.length) return;
  if (particleFrameIndex >= particleReplay.frames.length - 1) particleFrameIndex = 0;
  ui.particlePlay.textContent = "Pause";
  particleTimer = setTimeout(scheduleParticleFrame, 0);
});
ui.particleFrame.addEventListener("input", () => {
  stopParticleReplay();
  particleFrameIndex = Number(ui.particleFrame.value);
  drawParticleFrame();
});
ui.particleSpeed.addEventListener("change", () => {
  if (!particleTimer) return;
  clearTimeout(particleTimer);
  particleTimer = setTimeout(scheduleParticleFrame, 0);
});
function reloadParticleModel() {
  stopParticleReplay();
  historySession = "";
  refresh();
}
ui.particleModel.addEventListener("change", reloadParticleModel);
ui.groundClearance.addEventListener("change", reloadParticleModel);

ui.mapToggle.addEventListener("click", () => {
  mapEnabled = !mapEnabled;
  ui.mapToggle.setAttribute("aria-pressed", String(mapEnabled));
  ui.mapToggle.textContent = mapEnabled ? "Map on" : "Map off";
  ui.attribution.hidden = !mapEnabled;
  renderMap();
});
ui.topoToggle.addEventListener("click", async () => {
  if (!topographyEnabled && !(await loadTopography())) return;
  topographyEnabled = !topographyEnabled;
  ui.topoToggle.setAttribute("aria-pressed", String(topographyEnabled));
  ui.topoToggle.textContent = topographyEnabled ? "Topography on" : "Topography off";
  renderMap();
});
ui.zoomIn.addEventListener("click", () => { view.metresPerPixel /= 2; renderMap(); });
ui.zoomOut.addEventListener("click", () => { view.metresPerPixel *= 2; renderMap(); });
ui.fitMap.addEventListener("click", fitDem);
ui.map.addEventListener("pointerdown", event => {
  if (event.button !== 0) return;
  dragStart = {x: event.clientX, y: event.clientY, east: view.east, north: view.north};
  ui.map.setPointerCapture(event.pointerId);
  ui.map.classList.add("dragging");
});
ui.map.addEventListener("pointermove", event => {
  if (!dragStart) return;
  const bounds = ui.map.getBoundingClientRect();
  view.east = dragStart.east - (event.clientX - dragStart.x) * 900 / bounds.width * view.metresPerPixel;
  view.north = dragStart.north + (event.clientY - dragStart.y) * 500 / bounds.height * view.metresPerPixel;
});
function endDrag() {
  if (!dragStart) return;
  dragStart = null;
  ui.map.classList.remove("dragging");
  renderMap();
}
ui.map.addEventListener("pointerup", endDrag);
ui.map.addEventListener("pointercancel", endDrag);
renderMap();
