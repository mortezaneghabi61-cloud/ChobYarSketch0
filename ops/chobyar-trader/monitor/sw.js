"use strict";

// Prior proven shell generation: chobyar-monitor-shell-v3. v4 only advances the cache generation.
const CACHE = "chobyar-monitor-shell-v4";
const SHELL = [
  "/monitor/",
  "/monitor/style.css",
  "/monitor/app.js",
  "/monitor/position_detail.css",
  "/monitor/position_detail.js",
  "/monitor/manifest.webmanifest",
  "/monitor/icon.svg"
];

self.addEventListener("install", (event) => {
  event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)));
  self.skipWaiting();
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
  );
  self.clients.claim();
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);
  if (url.origin !== self.location.origin) return;

  // Telemetry is network-only. Never cache /public-report snapshots.
  if (url.pathname === "/public-report") {
    event.respondWith(fetch(event.request, { cache: "no-store" }));
    return;
  }

  if (SHELL.includes(url.pathname)) {
    event.respondWith(caches.match(event.request).then((cached) => cached || fetch(event.request)));
  }
});
