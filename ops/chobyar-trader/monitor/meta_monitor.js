"use strict";

function metaPct(value, digits = 1) {
  const n = Number(value);
  return Number.isFinite(n) ? `${(n * 100).toFixed(digits)}%` : "—";
}

function metaText(value, fallback = "—") {
  return value === null || value === undefined || value === "" ? fallback : String(value);
}

function metaRiskClass(score, warnAt, dangerAt) {
  const n = Number(score);
  if (!Number.isFinite(n)) return "unknown";
  if (n >= dangerAt) return "danger";
  if (n >= warnAt) return "warn";
  return "clear";
}

function ensureMetaPanel() {
  let panel = document.getElementById("v5MetaPanel");
  if (panel) return panel;
  const host = document.getElementById("v5SpecialistPanel");
  if (!host) return null;
  panel = document.createElement("section");
  panel.id = "v5MetaPanel";
  panel.className = "v5-meta-panel";
  const title = document.createElement("div");
  title.className = "v5-meta-title";
  const heading = document.createElement("strong");
  heading.textContent = "Meta Intelligence v5.2";
  const badge = document.createElement("span");
  badge.className = "tiny-badge";
  badge.textContent = "READ ONLY";
  title.append(heading, badge);
  const grid = document.createElement("div");
  grid.id = "v5MetaGrid";
  grid.className = "v5-meta-grid";
  const hold = document.createElement("div");
  hold.id = "v5MetaHold";
  hold.className = "v5-meta-hold";
  panel.append(title, grid, hold);
  const anchor = document.getElementById("v5SpecialistGrid");
  if (anchor) host.insertBefore(panel, anchor);
  else host.appendChild(panel);
  return panel;
}

function metaCard(label, value, detail, kind = "") {
  const card = document.createElement("div");
  card.className = `v5-meta-card ${kind}`.trim();
  const top = document.createElement("span");
  top.textContent = label;
  const strong = document.createElement("strong");
  strong.textContent = value;
  const small = document.createElement("small");
  small.textContent = detail || "";
  card.append(top, strong, small);
  return card;
}

function renderMetaIntelligence(report) {
  const panel = ensureMetaPanel();
  if (!panel) return;
  const grid = document.getElementById("v5MetaGrid");
  const hold = document.getElementById("v5MetaHold");
  if (!grid || !hold) return;
  grid.replaceChildren();
  hold.replaceChildren();

  const meta = report && report.v5_meta;
  if (!meta || meta.mode !== "shadow_observation_only" || meta.execution_authority !== false) {
    grid.appendChild(metaCard("Meta", "UNAVAILABLE", "داده امن Meta هنوز در گزارش عمومی نیست", "warn"));
    return;
  }

  const integrity = meta.data_integrity || {};
  const uncertainty = meta.uncertainty || {};
  const execution = meta.execution_stress || {};
  const transition = meta.regime_transition || {};
  const fragility = meta.fragility || {};
  const pre = metaText(meta.pre_meta_action, "WAIT").toUpperCase();
  const finalAction = metaText(meta.final_action, "WAIT").toUpperCase();

  const integrityFlags = Array.isArray(integrity.flags) ? integrity.flags.join(", ") : "";
  const uncertaintyDetail = `coverage ${metaPct(uncertainty.directional_coverage, 0)} · margin ${metaPct(uncertainty.directional_margin, 0)}`;
  const executionFlags = Array.isArray(execution.flags) ? execution.flags.join(", ") : "";
  const transitionFlags = Array.isArray(transition.flags) ? transition.flags.join(", ") : "";
  const fragilityDetail = fragility.trials ? `${fragility.flip_count || 0}/${fragility.trials} flips` : "no directional trial";

  grid.append(
    metaCard("Data Integrity", metaPct(integrity.score), integrity.healthy === true ? `HEALTHY${integrityFlags ? ` · ${integrityFlags}` : ""}` : `CHECK${integrityFlags ? ` · ${integrityFlags}` : ""}`, integrity.healthy === true ? "clear" : "warn"),
    metaCard("Uncertainty", metaPct(uncertainty.score), uncertaintyDetail, metaRiskClass(uncertainty.score, 0.45, 0.65)),
    metaCard("Execution Stress", metaPct(execution.score), executionFlags || "execution proxy clear", metaRiskClass(execution.score, 0.55, 0.80)),
    metaCard("Regime Transition", metaPct(transition.score), transitionFlags || "no transition flag", metaRiskClass(transition.score, 0.40, 0.70)),
    metaCard("Fragility", fragility.fragile === true ? "FRAGILE" : "STABLE", fragilityDetail, fragility.fragile === true ? "danger" : "clear"),
    metaCard("Meta Decision", `${pre} → ${finalAction}`, meta.meta_hold === true ? "HOLD APPLIED" : "NO HOLD", meta.meta_hold === true ? "danger" : "clear")
  );

  const label = document.createElement("strong");
  const reasons = Array.isArray(meta.meta_hold_reasons) ? meta.meta_hold_reasons : [];
  label.textContent = meta.meta_hold === true ? "Meta Hold فعال" : "Meta Hold غیرفعال";
  const reason = document.createElement("span");
  reason.textContent = meta.meta_hold === true ? (reasons.length ? reasons.join(" · ") : "reason unavailable") : "هیچ تصمیم جهت‌داری توسط Meta مسدود نشده";
  hold.className = `v5-meta-hold ${meta.meta_hold === true ? "hold" : "clear"}`;
  hold.append(label, reason);
}

const renderV5ShadowBeforeMeta = renderV5Shadow;
renderV5Shadow = function renderV5ShadowWithMeta(report) {
  renderV5ShadowBeforeMeta(report);
  renderMetaIntelligence(report);
};
