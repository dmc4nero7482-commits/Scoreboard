const CACHE = 'scoreboard-v3';
const STATIC = ['./manifest.json', './icon.svg'];

self.addEventListener('install', e => {
    e.waitUntil(
        caches.open(CACHE).then(c => c.addAll([...STATIC, './scoreboard.html']))
    );
    self.skipWaiting();
});

self.addEventListener('activate', e => {
    e.waitUntil(
        caches.keys().then(keys =>
            Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k)))
        )
    );
    self.clients.claim();
});

self.addEventListener('fetch', e => {
    const url = new URL(e.request.url);

    if (url.pathname.endsWith('.html') || url.pathname.endsWith('/')) {
        e.respondWith(
            fetch(e.request)
                .then(res => {
                    caches.open(CACHE).then(c => c.put(e.request, res.clone()));
                    return res;
                })
                .catch(() => caches.match(e.request))
        );
        return;
    }

    e.respondWith(
        caches.match(e.request).then(cached => cached || fetch(e.request))
    );
});
