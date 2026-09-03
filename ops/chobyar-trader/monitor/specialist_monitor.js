"use strict";

function v5Text(value, fallback = "—") {
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

function v5Pct(value, digits = 1) {
  const n = Number(value);
  return Number.isFinite(n) ? `${(n * 100).toFixed(digits)}%` : "—";
}

function v5Vote(value) {
  const n = Number(value);
  return n > 0 ? "BUY" : n < 0 ? "SELL" : "WAIT";
}

function ensureV5Panel() {
  let panel = document.getElementById("v5SpecialistPanel");
  if (panel) return panel;
  panel = document.createElement("section");
  panel.id = "v5SpecialistPanel";
  panel.className = "panel v5-specialist-panel";

  const title = document.createElement("div");
  title.className = "panel-title v5-title";
  const heading = document.createElement("div");
  const h2 = document.createElement("h2");
  h2.textContent = "شورای متخصص‌های v5";
  const sub = document.createElement("p");
  sub.className = "muted";
  sub.textContent = "Shadow analysis · بدون اختیار معامله";
  heading.append(h2, sub);
  const badges = document.createElement("div");
  badges.className = "v5-badges";
  for (const text of ["SHADOW ONLY", "NO EXECUTION", "NO AUTO PROMOTION"]) {
    const badge = document.createElement("span");
    badge.className = "tiny-badge";
    badge.textContent = text;
    badges.appendChild(badge);
  }
  title.append(heading, badges);

  const summary = document.createElement("div");
  summary.id = "v5Summary";
  summary.className = "v5-summary";
  const grid = document.createElement("div");
  grid.id = "v5SpecialistGrid";
  grid.className = "v5-specialist-grid";
  const footer = document.createElement("div");
  footer.id = "v5Evidence";
  footer.className = "v5-evidence";
  panel.append(title, summary, grid, footer);

  const anchor = document.querySelector(".scorecard-panel");
  if (anchor && anchor.parentNode) anchor.parentNode.insertBefore(panel, anchor);
  else document.getElementById("dashboard")?.appendChild(panel);
  return panel;
}

function v5SummaryBox(label, value, kind = "") {
  const box = document.createElement("div");
  box.className = `v5-summary-box ${kind}`.trim();
  const span = document.createElement("span");
  span.textContent = label;
  const strong = document.createElement("strong");
  strong.textContent = value;
  box.append(span, strong);
  return box;
}

function renderV5Shadow(report) {
  ensureV5Panel();
  const shadow = report.v5_shadow;
  const scorecard = report.v5_specialist_scorecard;
  const summary = document.getElementById("v5Summary");
  const grid = document.getElementById("v5SpecialistGrid");
  const evidence = document.getElementById("v5Evidence");
  if (!summary || !grid || !evidence) return;

  summary.replaceChildren();
  grid.replaceChildren();
  evidence.replaceChildren();

  if (!shadow || shadow.mode !== "shadow_observation_only" || shadow.execution_authority !== false) {
    summary.appendChild(v5SummaryBox("v5", "داده Shadow معتبر در دسترس نیست", "warn"));
    return;
  }

  const regime = shadow.regime || {};
  const consensus = shadow.shadow_consensus || {};
  const action = v5Text(consensus.action, "WAIT").toUpperCase();
  const veto = consensus.risk_veto === true;
  summary.append(
    v5SummaryBox("رژیم بازار", `${v5Text(regime.label)} · ${v5Pct(regime.confidence, 0)}`),
    v5SummaryBox("نظر شورای Shadow", action, action.toLowerCase()),
    v5SummaryBox("Risk Veto", veto ? "VETO" : "CLEAR", veto ? "veto" : "clear"),
    v5SummaryBox("متخصص جهت‌دار فعال", v5Text(consensus.available_directional_specialists, "0"))
  );

  const cardByName = (scorecard && scorecard.specialists) || {};
  for (const item of shadow.specialists || []) {
    const card = document.createElement("article");
    card.className = "v5-agent-card";
    const top = document.createElement("div");
    top.className = "v5-agent-top";
    const name = document.createElement("strong");
    name.textContent = v5Text(item.agent);
    const vote = document.createElement("span");
    const voteText = item.veto === true ? "VETO" : v5Vote(item.vote);
    vote.className = `v5-agent-vote ${voteText.toLowerCase()}`;
    vote.textContent = voteText;
    top.append(name, vote);

    const confidence = document.createElement("div");
    confidence.className = "v5-confidence";
    const confLabel = document.createElement("span");
    confLabel.textContent = "Confidence";
    const confValue = document.createElement("strong");
    confValue.textContent = v5Pct(item.confidence, 0);
    confidence.append(confLabel, confValue);

    const reason = document.createElement("p");
    reason.className = "v5-reason";
    reason.textContent = item.available === false ? `Unavailable · ${v5Text(item.reason)}` : v5Text(item.reason);

    const ev = cardByName[item.agent] || {};
    const horizons = ev.horizons || {};
    const stats = document.createElement("div");
    stats.className = "v5-horizons";
    for (const horizon of ["1h", "4h", "12h"]) {
      const h = horizons[horizon] || {};
      const cell = document.createElement("div");
      const label = document.createElement("span");
      label.textContent = horizon;
      const val = document.createElement("strong");
      const hit = Number.isFinite(Number(h.hit_rate)) ? v5Pct(h.hit_rate, 0) : "—";
      val.textContent = `${Number(h.samples || 0)} / ${hit}`;
      cell.append(label, val);
      stats.appendChild(cell);
    }
    card.append(top, confidence, reason, stats);
    grid.appendChild(card);
  }

  const sampled = Number(scorecard?.sampled_rows || 0);
  const minimum = Number(scorecard?.minimum_directional_samples_per_regime || 30);
  const reviewable = Array.isArray(scorecard?.reviewable_specialists) ? scorecard.reviewable_specialists.length : 0;
  const note = document.createElement("span");
  note.textContent = `Evidence: ${sampled} sampled rows · حداقل ${minimum} نمونه 4h در هر Regime · Reviewable: ${reviewable}`;
  const lock = document.createElement("strong");
  lock.textContent = "AUTO PROMOTION: DISABLED";
  evidence.append(note, lock);
}

const renderReportBeforeV5 = renderReport;
renderReport = function renderReportV51(report) {
  renderReportBeforeV5(report);
  renderV5Shadow(report);
};
