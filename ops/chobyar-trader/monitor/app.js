"use strict";

const POLL_MS = 30_000;
const STALE_MS = 75_000;
const HISTORY_KEY = "chobyar.monitor.equity.v1";
const EVENTS_KEY = "chobyar.monitor.events.v1";
const MAX_HISTORY = 240;
const MAX_EVENTS = 80;

let lastGoodReport = null;
let lastSuccessAt = 0;
let lastConnection = "boot";
let lastDecisionKey = "";
let pollTimer = null;

const $ = (id) => document.getElementById(id);

function loadLocal(key, fallback) {
  try {
    const value = JSON.parse(localStorage.getItem(key));
    return Array.isArray(value) ? value : fallback;
  } catch (_) {
    return fallback;
  }
}

function saveLocal(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value));
  } catch (_) {
    // Local-only convenience storage must never break monitoring.
  }
}

let equityHistory = loadLocal(HISTORY_KEY, []);
let localEvents = loadLocal(EVENTS_KEY, []);

function safeNumber(value) {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

function fmtNumber(value, digits = 4) {
  const n = safeNumber(value);
  if (n === null) return "—";
  return n.toLocaleString("en-US", { maximumFractionDigits: digits, minimumFractionDigits: 0 });
}

function fmtPct(value, digits = 2) {
  const n = safeNumber(value);
  if (n === null) return "—";
  return `${(n * 100).toFixed(digits)}%`;
}

function fmtTime(value) {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return "—";
  return d.toLocaleString("fa-IR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}

function fmtDuration(seconds) {
  const n = Math.max(0, Math.floor(safeNumber(seconds) || 0));
  const d = Math.floor(n / 86400);
  const h = Math.floor((n % 86400) / 3600);
  const m = Math.floor((n % 3600) / 60);
  if (d) return `${d}d ${h}h ${m}m`;
  if (h) return `${h}h ${m}m`;
  return `${m}m`;
}

function setSignedClass(el, value) {
  el.classList.remove("positive", "negative");
  const n = safeNumber(value);
  if (n === null || n === 0) return;
  el.classList.add(n > 0 ? "positive" : "negative");
}

function addEvent(message) {
  localEvents.unshift({ at: Date.now(), message: String(message).slice(0, 240) });
  localEvents = localEvents.slice(0, MAX_EVENTS);
  saveLocal(EVENTS_KEY, localEvents);
  renderEvents();
}

function renderEvents() {
  const root = $("eventLog");
  root.replaceChildren();
  if (!localEvents.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "هنوز رویدادی ثبت نشده است.";
    root.appendChild(empty);
    return;
  }
  for (const row of localEvents) {
    const item = document.createElement("div");
    item.className = "event-item";
    const time = document.createElement("time");
    time.textContent = new Date(row.at).toLocaleTimeString("fa-IR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
    const text = document.createElement("span");
    text.textContent = row.message;
    item.append(time, text);
    root.appendChild(item);
  }
}

function validateReport(report) {
  if (!report || typeof report !== "object") throw new Error("invalid_report");
  if (report.ok !== true || report.public_report !== true) throw new Error("untrusted_report_shape");
  if (typeof report.mode !== "string" || typeof report.live_locked !== "boolean") throw new Error("missing_safety_fields");
  return report;
}

function safetyCritical(report) {
  return report.mode !== "paper" || report.live_locked !== true;
}

function setCritical(report) {
  const critical = safetyCritical(report);
  document.body.classList.toggle("fail-closed", critical);
  const gate = $("criticalGate");
  gate.hidden = !critical;
  if (critical) {
    $("criticalMessage").textContent = `گزارش ایمن نیست: mode=${String(report.mode)} / live_locked=${String(report.live_locked)}`;
    addEvent("FAIL-CLOSED: Paper/Live lock contract violated");
  }
}

function connectionState(kind, text) {
  const badge = $("connectionBadge");
  badge.className = `badge ${kind}`;
  badge.innerHTML = '<span class="dot"></span>';
  badge.append(document.createTextNode(` ${text}`));
}

function renderHeader(report) {
  const mode = $("modeBadge");
  mode.textContent = String(report.mode || "—").toUpperCase();
  mode.className = `badge ${report.mode === "paper" ? "good" : "bad"}`;

  const live = $("liveBadge");
  live.textContent = report.live_locked === true ? "LIVE LOCKED" : "LIVE UNLOCKED";
  live.className = `badge ${report.live_locked === true ? "good" : "bad"}`;

  $("lastUpdate").textContent = fmtTime(report.server_time_utc || new Date(lastSuccessAt).toISOString());
}

function renderPerformance(report) {
  const perf = report.performance || {};
  const fwd = report.forward_test || {};
  const equity = fwd.current_equity ?? perf.equity;

  $("equity").textContent = fmtNumber(equity, 6);
  $("realizedPnl").textContent = fmtNumber(fwd.realized_pnl ?? perf.realized_pnl, 6);
  $("returnPct").textContent = fmtPct(perf.return_pct, 4);
  $("currentDd").textContent = fmtPct(perf.current_drawdown_pct, 4);
  $("maxDd").textContent = fmtPct(fwd.max_drawdown_pct ?? perf.max_drawdown_pct, 4);
  $("cycles").textContent = fmtNumber(fwd.cycles, 0);
  $("closedTrades").textContent = fmtNumber(fwd.closed_trades, 0);
  $("winsLosses").textContent = `${fmtNumber(fwd.wins, 0)} / ${fmtNumber(fwd.losses, 0)}`;
  $("uptime").textContent = fmtDuration(fwd.uptime_seconds);

  setSignedClass($("realizedPnl"), fwd.realized_pnl ?? perf.realized_pnl);
  setSignedClass($("returnPct"), perf.return_pct);
}

function renderPosition(report) {
  const root = $("positionContent");
  const p = report.position;
  if (!p || typeof p !== "object" || typeof p.open !== "boolean") {
    root.className = "empty-state";
    root.innerHTML = "";
    const strong = document.createElement("strong");
    strong.textContent = "اطلاعات پوزیشن در گزارش عمومی موجود نیست";
    const para = document.createElement("p");
    para.textContent = "این مانیتور از تغییر Equity برای حدس‌زدن پوزیشن استفاده نمی‌کند.";
    root.append(strong, para);
    return;
  }

  root.className = "position-data";
  const fields = [
    ["وضعیت", p.open ? "OPEN" : "CLOSED"],
    ["جهت", p.side || "—"],
    ["Entry", fmtNumber(p.entry_price, 4)],
    ["Unrealized PnL", fmtNumber(p.unrealized_pnl, 6)],
  ];
  root.replaceChildren();
  for (const [label, value] of fields) {
    const box = document.createElement("div");
    const span = document.createElement("span");
    span.textContent = label;
    const strong = document.createElement("strong");
    strong.textContent = value;
    box.append(span, strong);
    root.appendChild(box);
  }
}

function renderDecision(report) {
  const d = report.decision || {};
  const signal = String(d.signal || d.action || "WAIT").toUpperCase();
  const signalEl = $("decisionSignal");
  signalEl.textContent = signal;
  signalEl.className = `signal ${signal === "BUY" ? "buy" : signal === "SELL" ? "sell" : "wait"}`;
  $("decisionAction").textContent = String(d.action ?? "—");
  $("decisionExecuted").textContent = d.executed === true ? "YES" : d.executed === false ? "NO" : "—";
  $("decisionReason").textContent = String(d.risk_reason ?? "—");
  $("decisionTime").textContent = fmtTime(d.ts);

  const votes = d.agent_summary || {};
  $("voteBuy").textContent = fmtNumber(votes.buy, 0);
  $("voteSell").textContent = fmtNumber(votes.sell, 0);
  $("voteWait").textContent = fmtNumber(votes.wait, 0);
  $("voteUnavailable").textContent = fmtNumber(votes.unavailable, 0);

  const key = JSON.stringify([d.ts, d.signal, d.action, d.executed, d.risk_reason]);
  if (lastDecisionKey && key !== lastDecisionKey) {
    addEvent(`تصمیم جدید: ${signal} · executed=${String(d.executed)} · ${String(d.risk_reason || "")}`);
  }
  lastDecisionKey = key;
}

function renderServices(report) {
  const services = report.services || {};
  const root = $("servicesList");
  root.replaceChildren();
  const labels = {
    trader: "Trader",
    status: "Status server",
    backtest_timer: "Backtest timer",
    agent_scorecard_timer: "Agent Scorecard timer",
  };
  for (const key of Object.keys(labels)) {
    const value = String(services[key] ?? "unknown");
    const row = document.createElement("div");
    row.className = "service-row";
    const label = document.createElement("span");
    label.textContent = labels[key];
    const state = document.createElement("span");
    state.className = `service-state ${value === "active" ? "active" : "bad"}`;
    state.textContent = value;
    row.append(label, state);
    root.appendChild(row);
  }
}

function horizonCard(name, metrics) {
  const div = document.createElement("div");
  div.className = `horizon ${metrics?.sufficient ? "sufficient" : ""}`;
  const h4 = document.createElement("h4");
  h4.textContent = name;
  const dl = document.createElement("dl");
  const rows = [
    ["samples", fmtNumber(metrics?.samples, 0)],
    ["hit", fmtPct(metrics?.hit_rate, 1)],
    ["avg", fmtPct(metrics?.average_signed_return, 3)],
  ];
  for (const [k, v] of rows) {
    const pair = document.createElement("div");
    const dt = document.createElement("dt");
    dt.textContent = k;
    const dd = document.createElement("dd");
    dd.textContent = v;
    pair.append(dt, dd);
    dl.appendChild(pair);
  }
  div.append(h4, dl);
  return div;
}

function renderScorecard(report) {
  const sc = report.agent_scorecard || {};
  $("sampledRows").textContent = fmtNumber(sc.sampled_rows, 0);
  $("minimumSamples").textContent = fmtNumber(sc.minimum_directional_samples, 0);
  const ready = sc.ready_for_manual_weight_review === true;
  const review = $("weightReviewBadge");
  review.textContent = ready ? "Weight review: READY" : "Weight review: NOT READY";
  review.className = `badge ${ready ? "warn" : "neutral"}`;

  const root = $("scorecardGrid");
  root.replaceChildren();
  const agents = sc.agents && typeof sc.agents === "object" ? sc.agents : {};
  const names = Object.keys(agents).sort();
  if (!names.length) {
    const empty = document.createElement("div");
    empty.className = "empty-state";
    empty.textContent = "هنوز Scorecard قابل نمایش نیست.";
    root.appendChild(empty);
    return;
  }
  for (const name of names) {
    const data = agents[name] || {};
    const card = document.createElement("article");
    card.className = "agent-card";
    const head = document.createElement("div");
    head.className = "agent-head";
    const title = document.createElement("strong");
    title.textContent = name;
    const eligibility = document.createElement("span");
    eligibility.className = data.eligible_for_weight_review ? "eligible" : "not-eligible";
    eligibility.textContent = data.eligible_for_weight_review ? "REVIEWABLE" : "OBSERVING";
    head.append(title, eligibility);
    const grid = document.createElement("div");
    grid.className = "horizon-grid";
    const horizons = data.horizons || {};
    for (const h of ["1h", "4h", "12h"]) grid.appendChild(horizonCard(h, horizons[h] || {}));
    card.append(head, grid);
    root.appendChild(card);
  }
}

function renderBacktest(report) {
  const b = report.backtest || {};
  $("backtestStatus").textContent = b.ok === true ? "DATA OK" : "NOT OK";
  $("backtestWarning").hidden = b.full_fidelity_multiagent !== false;
  $("btModel").textContent = String(b.strategy_model ?? "—");
  $("btSource").textContent = String(b.source ?? "—");
  $("btHistory").textContent = `${fmtNumber(b.history_days, 1)}d / ${fmtNumber(b.candles, 0)}`;
  $("btTrades").textContent = fmtNumber(b.closed_trades, 0);
  $("btWinsLosses").textContent = `${fmtNumber(b.wins, 0)} / ${fmtNumber(b.losses, 0)}`;
  $("btWinRate").textContent = fmtPct(b.win_rate, 1);
  $("btReturn").textContent = fmtPct(b.return_pct, 2);
  $("btDd").textContent = fmtPct(b.max_drawdown_pct, 2);
  setSignedClass($("btReturn"), b.return_pct);
}

function appendEquity(report) {
  const equity = safeNumber(report.forward_test?.current_equity ?? report.performance?.equity);
  if (equity === null) return;
  const t = new Date(report.server_time_utc || Date.now()).getTime();
  if (!Number.isFinite(t)) return;
  const last = equityHistory[equityHistory.length - 1];
  if (last && last.t === t) return;
  equityHistory.push({ t, equity });
  equityHistory = equityHistory.slice(-MAX_HISTORY);
  saveLocal(HISTORY_KEY, equityHistory);
  drawChart();
}

function drawChart() {
  const canvas = $("equityChart");
  const rect = canvas.getBoundingClientRect();
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  const width = Math.max(320, Math.floor(rect.width));
  const height = 245;
  canvas.width = width * dpr;
  canvas.height = height * dpr;
  const ctx = canvas.getContext("2d");
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, width, height);

  ctx.strokeStyle = "rgba(145,165,191,.12)";
  ctx.lineWidth = 1;
  for (let i = 1; i < 5; i++) {
    const y = (height / 5) * i;
    ctx.beginPath();
    ctx.moveTo(12, y);
    ctx.lineTo(width - 12, y);
    ctx.stroke();
  }

  if (equityHistory.length < 2) {
    ctx.fillStyle = "#91a5bf";
    ctx.font = "12px system-ui";
    ctx.textAlign = "center";
    ctx.fillText("برای رسم نمودار حداقل دو snapshot لازم است", width / 2, height / 2);
    return;
  }

  const values = equityHistory.map((p) => p.equity);
  const min = Math.min(...values);
  const max = Math.max(...values);
  const span = Math.max(max - min, Math.abs(max) * 0.0001, 0.0001);
  const left = 14, right = width - 14, top = 16, bottom = height - 22;
  const x = (i) => left + (i / (equityHistory.length - 1)) * (right - left);
  const y = (v) => bottom - ((v - min) / span) * (bottom - top);

  ctx.strokeStyle = "#60a5fa";
  ctx.lineWidth = 2;
  ctx.beginPath();
  equityHistory.forEach((p, i) => {
    const px = x(i), py = y(p.equity);
    if (i === 0) ctx.moveTo(px, py); else ctx.lineTo(px, py);
  });
  ctx.stroke();

  ctx.fillStyle = "#91a5bf";
  ctx.font = "10px system-ui";
  ctx.textAlign = "left";
  ctx.fillText(max.toFixed(6), 12, 12);
  ctx.fillText(min.toFixed(6), 12, height - 7);
}

function renderReport(report) {
  setCritical(report);
  renderHeader(report);
  renderPerformance(report);
  renderPosition(report);
  renderDecision(report);
  renderServices(report);
  renderScorecard(report);
  renderBacktest(report);
  appendEquity(report);
}

async function poll() {
  try {
    const response = await fetch("/public-report", { method: "GET", cache: "no-store", credentials: "omit" });
    if (!response.ok) throw new Error(`http_${response.status}`);
    const report = validateReport(await response.json());
    lastGoodReport = report;
    lastSuccessAt = Date.now();
    document.body.classList.remove("stale");
    connectionState("good", "متصل");
    if (lastConnection !== "online") addEvent("اتصال مانیتور به گزارش امن برقرار شد.");
    lastConnection = "online";
    renderReport(report);
  } catch (err) {
    document.body.classList.add("stale");
    connectionState("warn", "STALE");
    if (lastConnection !== "stale") addEvent(`گزارش تازه دریافت نشد؛ آخرین snapshot نگه داشته شد (${String(err?.message || err)})`);
    lastConnection = "stale";
    if (!lastGoodReport) {
      $("lastUpdate").textContent = "بدون داده";
    }
  }
}

function updateAge() {
  if (!lastSuccessAt) {
    $("dataAge").textContent = "هنوز snapshot دریافت نشده";
    return;
  }
  const ageMs = Date.now() - lastSuccessAt;
  $("dataAge").textContent = `${Math.floor(ageMs / 1000)} ثانیه پیش`;
  if (ageMs > STALE_MS) {
    document.body.classList.add("stale");
    connectionState("warn", "STALE");
  }
}

function boot() {
  renderEvents();
  drawChart();
  addEvent("ChobYar Trader Monitor شروع شد (Read-only).");
  poll();
  pollTimer = setInterval(poll, POLL_MS);
  setInterval(updateAge, 1000);
  window.addEventListener("resize", drawChart, { passive: true });

  if ("serviceWorker" in navigator && window.isSecureContext) {
    navigator.serviceWorker.register("/monitor/sw.js").catch(() => {});
  }
}

document.addEventListener("DOMContentLoaded", boot, { once: true });
