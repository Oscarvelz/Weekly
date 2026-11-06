// Weekly service worker: network first, so a new version always wins when online;
// the last good copy opens the app when offline. Caseload data lives in IndexedDB, never here.
const CACHE = "weekly-1.8.21";
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
    caches.keys().then((keys) => Promise.all(keys.filter((key) => key.startsWith("weekly-") && key !== CACHE).map((key) => caches.delete(key)))).then(() => self.clients.claim()),
  );
});
// Weekly 1.8.7 dictionary: the data lives in its own cache, named by data version (not by app version), so app updates
// never download it again. Shards are immutable per version folder, so they are served cache-first. The page asks for
// the whole set once Weekly has been open a while (message "weekly-dict-precache"); a lookup also fills the cache.
const DICT_CACHE = "wdict-v1";
const DICT_PATH = "dict/v1/";
async function precacheDict() {
  const cache = await caches.open(DICT_CACHE);
  const base = new URL(DICT_PATH, self.registration.scope).href;
  let index = await cache.match(base + "index.json");
  if (!index) {
    const fresh = await fetch(base + "index.json", { cache: "no-store" });
    if (!fresh.ok) return;
    await cache.put(base + "index.json", fresh.clone());
    index = fresh;
  }
  const manifest = await index.json();
  const files = [`${base}mw.json`];
  for (const series of ["en", "es"]) (manifest[series] || []).forEach((_, i) => files.push(`${base}${series}/${String(i).padStart(3, "0")}.json`));
  let next = 0;
  async function worker() {
    while (next < files.length) {
      const url = files[next++];
      if (await cache.match(url)) continue;
      const res = await fetch(url).catch(() => null);
      if (res && res.ok) await cache.put(url, res).catch(() => undefined);
    }
  }
  await Promise.all([worker(), worker(), worker()]);
  const keys = await caches.keys();
  await Promise.all(keys.filter((key) => key.startsWith("wdict-") && key !== DICT_CACHE).map((key) => caches.delete(key)));
}
self.addEventListener("message", (event) => {
  if (event.data && event.data.type === "weekly-dict-precache") event.waitUntil(precacheDict().catch(() => undefined));
});
self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET" || new URL(request.url).origin !== self.location.origin) return;
  if (request.url.startsWith(new URL(DICT_PATH, self.registration.scope).href)) {
    event.respondWith(
      caches.open(DICT_CACHE).then((cache) =>
        cache.match(request).then(
          (hit) =>
            hit ||
            fetch(request).then((response) => {
              if (response.ok) cache.put(request, response.clone()).catch(() => undefined);
              return response;
            }),
        ),
      ),
    );
    return;
  }
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
