"use strict";

function positionDetailField(label, value, signedValue = null) {
  const box = document.createElement("div");
  box.className = "position-detail-field";
  const span = document.createElement("span");
  span.textContent = label;
  const strong = document.createElement("strong");
  strong.textContent = value;
  if (signedValue !== null) setSignedClass(strong, signedValue);
  box.append(span, strong);
  return box;
}

function positionRange(p) {
  const entry = safeNumber(p.entry_price);
  const mark = safeNumber(p.mark_price);
  const stop = safeNumber(p.stop_loss_price);
  const take = safeNumber(p.take_profit_price);
  if (p.targets_verified !== true || entry === null || mark === null || stop === null || take === null || take <= stop) return null;

  const progress = Math.max(0, Math.min(100, ((mark - stop) / (take - stop)) * 100));
  const wrap = document.createElement("div");
  wrap.className = "position-range";

  const title = document.createElement("div");
  title.className = "position-range-title";
  const label = document.createElement("span");
  label.textContent = "موقعیت قیمت بین SL و TP";
  const current = document.createElement("strong");
  current.textContent = `${progress.toFixed(1)}%`;
  title.append(label, current);

  const progressEl = document.createElement("progress");
  progressEl.className = "position-progress";
  progressEl.max = 100;
  progressEl.value = progress;
  progressEl.setAttribute("aria-label", "Position progress from stop loss to take profit");

  const labels = document.createElement("div");
  labels.className = "position-range-labels";
  for (const [name, value] of [["SL", stop], ["Entry", entry], ["TP", take]]) {
    const item = document.createElement("span");
    const b = document.createElement("b");
    b.textContent = name;
    const small = document.createElement("small");
    small.textContent = fmtNumber(value, 4);
    item.append(b, small);
    labels.appendChild(item);
  }

  wrap.append(title, progressEl, labels);
  return wrap;
}

renderPosition = function renderPositionV48(report) {
  const root = $("positionContent");
  const p = report.position;
  if (!p || typeof p !== "object" || typeof p.open !== "boolean") {
    root.className = "empty-state";
    root.replaceChildren();
    const strong = document.createElement("strong");
    strong.textContent = "اطلاعات پوزیشن در گزارش عمومی موجود نیست";
    const para = document.createElement("p");
    para.textContent = "این مانیتور از تغییر Equity برای حدس‌زدن پوزیشن استفاده نمی‌کند.";
    root.append(strong, para);
    return;
  }

  root.className = "position-data position-detail-grid";
  root.replaceChildren();

  const fields = [
    ["وضعیت", p.open ? "OPEN" : "FLAT", null],
    ["جهت", p.side || "—", null],
    ["Entry", fmtNumber(p.entry_price, 4), null],
    ["قیمت فعلی", fmtNumber(p.mark_price, 4), null],
    ["Unrealized PnL", fmtNumber(p.unrealized_pnl, 6), safeNumber(p.unrealized_pnl)],
    ["P/L قیمت", fmtPct(p.unrealized_return_pct, 3), safeNumber(p.unrealized_return_pct)],
    ["Stop Loss", fmtNumber(p.stop_loss_price, 4), null],
    ["Take Profit", fmtNumber(p.take_profit_price, 4), null],
    ["فاصله تا SL", fmtPct(p.distance_to_stop_pct, 3), safeNumber(p.distance_to_stop_pct)],
    ["فاصله تا TP", fmtPct(p.distance_to_take_pct, 3), safeNumber(p.distance_to_take_pct)],
  ];

  for (const [label, value, signed] of fields) root.appendChild(positionDetailField(label, value, signed));

  const range = positionRange(p);
  if (range) root.appendChild(range);
  else if (p.open === true) {
    const notice = document.createElement("div");
    notice.className = "position-target-warning";
    notice.textContent = "هدف‌های SL/TP فقط وقتی نمایش داده می‌شوند که Risk مصوب دقیقاً تأیید شده باشد.";
    root.appendChild(notice);
  }
};
