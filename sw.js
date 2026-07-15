/* Reihen-Navigator Service Worker
   - App-Shell offline verfügbar
   - Kartenkacheln werden beim Ansehen gespeichert -> im Funkloch weiter sichtbar */

const SHELL = 'shell-v1';
const TILES = 'tiles-v1';
const MAX_TILES = 3000;

const SHELL_FILES = [
  './',
  './index.html',
  './manifest.webmanifest',
  './icon-192.png',
  './icon-512.png',
  'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css',
  'https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js',
  'https://cdnjs.cloudflare.com/ajax/libs/Turf.js/6.5.0/turf.min.js'
];

self.addEventListener('install', e => {
  e.waitUntil(
    caches.open(SHELL)
      .then(c => Promise.allSettled(SHELL_FILES.map(f => c.add(f))))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', e => {
  e.waitUntil(
    caches.keys()
      .then(keys => Promise.all(
        keys.filter(k => k !== SHELL && k !== TILES).map(k => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

function isTile(url) {
  return url.includes('/ArcGIS/rest/services/World_Imagery/MapServer/tile/')
      || url.includes('tile.openstreetmap.org');
}

async function trimTiles() {
  const c = await caches.open(TILES);
  const keys = await c.keys();
  if (keys.length > MAX_TILES) {
    for (let i = 0; i < keys.length - MAX_TILES; i++) await c.delete(keys[i]);
  }
}

self.addEventListener('fetch', e => {
  const req = e.request;
  if (req.method !== 'GET') return;
  const url = req.url;

  // Kartenkacheln: erst Netz, sonst Cache (und immer mitspeichern)
  if (isTile(url)) {
    e.respondWith(
      fetch(req).then(res => {
        const copy = res.clone();
        caches.open(TILES).then(c => c.put(req, copy).then(trimTiles));
        return res;
      }).catch(() => caches.match(req))
    );
    return;
  }

  // App-Dateien: erst Cache, sonst Netz
  e.respondWith(
    caches.match(req).then(hit => hit || fetch(req).then(res => {
      if (res.ok && (url.startsWith(self.location.origin) || url.includes('cdnjs'))) {
        const copy = res.clone();
        caches.open(SHELL).then(c => c.put(req, copy));
      }
      return res;
    }).catch(() => caches.match('./index.html')))
  );
});
