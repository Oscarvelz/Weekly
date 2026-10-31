// Weekly service worker: network first, so a new version always wins when online;
// the last good copy opens the app when offline. Caseload data lives in IndexedDB, never here.
const CACHE = "weekly-1.3.3";
// On install, keep the page and every file it loads, so Weekly opens offline from the very first visit.
self.addEventListener("install", (event) => {
  self.skipWaiting();
  event.waitUntil(
    caches.open(CACHE).then(async (cache) => {
      try {
        const page = await fetch("./index.html", { cache: "no-store" });
        if (!page.ok) return;
        const html = await page.clone().text();
        await cache.put("./index.html", page);
        const files = new Set(["./", "./manifest.webmanifest", "./favicon.svg", "./icon-192.png", "./icon-512.png"]);
        for (const match of html.matchAll(/(?:src|href)="(\.\/[^"]+)"/g)) files.add(match[1]);
        await Promise.all([...files].map((file) => cache.add(file).catch(() => undefined)));
      } catch {
        /* offline during install; the network-first fetch handler fills the cache later */
      }
    }),
  );
});
self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key)))).then(() => self.clients.claim()),
  );
});
self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET" || new URL(request.url).origin !== self.location.origin) return;
  // Weekly 1.3: the voice package and runtime are read from Cadence's folder and kept by the voice engine's own
  // cache; never copy them into Weekly's cache.
  if (!request.url.startsWith(self.registration.scope)) return;
  event.respondWith(
    fetch(request)
      .then((response) => {
        if (response.ok) {
          const copy = response.clone();
          caches.open(CACHE).then((cache) => cache.put(request, copy)).catch(() => undefined);
        }
        return response;
      })
      .catch(() =>
        caches.match(request).then((hit) => hit || (request.mode === "navigate" ? caches.match("./index.html").then((page) => page || caches.match("./")) : undefined)).then((hit) => hit || Response.error()),
      ),
  );
});
