(() => {
  "use strict";
  const names = {wide: "گسترده", balanced: "متعادل", selective: "انتخابی"};
  const fmt = (n, digits = 3) => Number.isFinite(Number(n)) ? Number(n).toFixed(digits) : "—";
  const escapeHtml = (v) => String(v).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"})[c]);

  function ensureRoot() {
    let root = document.getElementById("paper-exploration-monitor");
    if (root) return root;
    root = document.createElement("section");
    root.id = "paper-exploration-monitor";
    root.dir = "rtl";
    root.innerHTML = '<div class="pem-head"><div><h2>معاملات آزمایشی سریع</h2><p>کاملاً مجازی و جدا از تریدر اصلی</p></div><span class="pem-badge">SHADOW ONLY</span></div><div id="pem-content" class="pem-loading">در حال دریافت داده…</div>';
    (document.querySelector("main") || document.body).appendChild(root);
    return root;
  }

  function render(data) {
    const root = ensureRoot();
    const box = root.querySelector("#pem-content");
    if (!data || !data.ok) {
      box.innerHTML = '<div class="pem-alert">دادهٔ Paper Exploration در دسترس نیست.</div>';
      return;
    }
    const health = data.service_active && !data.stale;
    const lanes = ["wide", "balanced", "selective"].map(name => {
      const lane = data.lanes?.[name] || {};
      const ret = Number(lane.return_pct);
      return `<article class="pem-card"><div class="pem-title"><strong>${escapeHtml(names[name])}</strong><span class="${lane.position_open ? "pem-open" : "pem-flat"}">${lane.position_open ? "باز" : "بسته"}</span></div><div class="pem-return ${ret >= 0 ? "pem-pos" : "pem-neg"}">${fmt(ret)}%</div><dl><div><dt>معامله کامل</dt><dd>${escapeHtml(lane.completed_trades ?? "—")}</dd></div><div><dt>برد / باخت</dt><dd>${escapeHtml(lane.wins ?? "—")} / ${escapeHtml(lane.losses ?? "—")}</dd></div><div><dt>موجودی مجازی</dt><dd>${fmt(lane.cash, 4)} USDT</dd></div><div><dt>حد ورود</dt><dd>${fmt(lane.threshold, 2)}</dd></div></dl></article>`;
    }).join("");
    box.innerHTML = `<div class="pem-summary"><span class="${health ? "pem-ok" : "pem-warn"}">${health ? "فعال و تازه" : "هشدار: سرویس یا داده کهنه"}</span><strong>${escapeHtml(data.total_completed_trades)} معاملهٔ کامل آزمایشی</strong><span>عمر داده: ${fmt(data.age_seconds, 0)} ثانیه</span></div><div class="pem-grid">${lanes}</div><p class="pem-foot">این آمار با معاملات اصلی بالای صفحه جمع نمی‌شود؛ اختیار اجرای واقعی و ارتقای خودکار ندارد.</p>`;
  }

  async function refresh() {
    try {
      const response = await fetch("/public-report", {cache: "no-store"});
      if (!response.ok) throw new Error("http");
      render((await response.json()).paper_exploration);
    } catch (_) {
      render(null);
    }
  }

  const style = document.createElement("style");
  style.textContent = `#paper-exploration-monitor{margin:18px auto;max-width:1180px;padding:18px;border:1px solid #4968a8;border-radius:18px;background:linear-gradient(145deg,#101b3c,#172957);color:#eef4ff;box-shadow:0 14px 40px #0005;font-family:inherit}.pem-head,.pem-summary,.pem-title,.pem-summary span{display:flex;align-items:center}.pem-head{justify-content:space-between;gap:12px}.pem-head h2{margin:0;font-size:1.25rem}.pem-head p,.pem-foot{opacity:.75;margin:5px 0}.pem-badge,.pem-open,.pem-flat,.pem-ok,.pem-warn{padding:5px 9px;border-radius:999px;font-size:.76rem}.pem-badge,.pem-flat{background:#294477}.pem-open,.pem-ok{background:#123f36;color:#63f0bb}.pem-warn{background:#5a3515;color:#ffd387}.pem-summary{justify-content:space-between;gap:10px;margin:14px 0;flex-wrap:wrap}.pem-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.pem-card{padding:14px;border-radius:14px;background:#ffffff0b;border:1px solid #ffffff18}.pem-title{justify-content:space-between}.pem-return{font-size:1.7rem;font-weight:800;margin:12px 0}.pem-pos{color:#61efb4}.pem-neg{color:#ff8194}.pem-card dl{margin:0}.pem-card dl div{display:flex;justify-content:space-between;padding:7px 0;border-top:1px solid #ffffff12}.pem-card dt{opacity:.72}.pem-card dd{margin:0;font-weight:700}.pem-alert,.pem-loading{padding:20px;text-align:center}.pem-alert{color:#ffd387}.pem-foot{text-align:center;margin-top:14px}@media(max-width:760px){#paper-exploration-monitor{margin:12px 8px;padding:13px}.pem-grid{grid-template-columns:1fr}.pem-summary{align-items:flex-start;flex-direction:column}.pem-card{display:grid;grid-template-columns:1fr 1fr;gap:8px}.pem-card dl{grid-column:1/-1}}`;
  document.head.appendChild(style);
  ensureRoot();
  refresh();
  setInterval(refresh, 5000);
})();
