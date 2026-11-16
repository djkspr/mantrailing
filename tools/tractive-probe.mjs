#!/usr/bin/env node
// Prototype : récupère la trace d'un collier Tractive sur une plage horaire.
// API non officielle (celle de l'appli Tractive, documentée par la lib aiotractive).
//
// Usage :
//   node tools/tractive-probe.mjs                         -> aujourd'hui, de 00:00 à maintenant
//   node tools/tractive-probe.mjs --from 2026-11-16T09:00 --to 2026-11-16T12:00
//   node tools/tractive-probe.mjs --tracker ABCDEFGH      -> si plusieurs colliers
//
// Identifiants lus dans .env (TRACTIVE_EMAIL, TRACTIVE_PASSWORD), jamais affichés.
// Sorties dans data/ : réponses brutes (JSON), trace GPX, carte HTML.

import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const API = 'https://graph.tractive.com/4/';
const CLIENT_ID = '625e533dc3c3b41c28a669f0';

// ---------- arguments ----------
const args = Object.fromEntries(
  process.argv.slice(2).reduce((acc, a, i, arr) => {
    if (a.startsWith('--')) acc.push([a.slice(2), arr[i + 1]]);
    return acc;
  }, []),
);
const startOfToday = new Date(); startOfToday.setHours(0, 0, 0, 0);
const from = args.from ? new Date(args.from) : startOfToday;
const to = args.to ? new Date(args.to) : new Date();
if (isNaN(from) || isNaN(to) || from >= to) fail('Plage horaire invalide (--from / --to).');

// ---------- identifiants ----------
const envFile = join(ROOT, '.env');
if (existsSync(envFile)) process.loadEnvFile(envFile);
const { TRACTIVE_EMAIL: email, TRACTIVE_PASSWORD: password } = process.env;
if (!email || !password) fail(`Renseigne TRACTIVE_EMAIL et TRACTIVE_PASSWORD dans ${envFile} (voir .env.example).`);

// ---------- client HTTP ----------
const baseHeaders = {
  'x-tractive-client': CLIENT_ID,
  'content-type': 'application/json;charset=UTF-8',
  accept: 'application/json, text/plain, */*',
};
let auth = null;

async function call(method, path, body) {
  const headers = { ...baseHeaders };
  if (auth) {
    headers['x-tractive-user'] = auth.user_id;
    headers.authorization = `Bearer ${auth.access_token}`;
  }
  const res = await fetch(new URL(path, API), { method, headers, body: body && JSON.stringify(body) });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> HTTP ${res.status} ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : null;
}

function fail(msg) { console.error(`✖ ${msg}`); process.exit(1); }

// ---------- déroulé ----------
const outDir = join(ROOT, 'data');
await mkdir(outDir, { recursive: true });
const save = (name, data) => writeFile(join(outDir, name), typeof data === 'string' ? data : JSON.stringify(data, null, 2));

console.log('→ Connexion à Tractive…');
auth = await call('POST', 'auth/token', { platform_email: email, platform_token: password, grant_type: 'tractive' });
console.log(`  ok (utilisateur ${auth.user_id})`);

console.log('→ Animaux et colliers…');
const pets = [];
for (const ref of await call('GET', `user/${auth.user_id}/trackable_objects`)) {
  const pet = await call('GET', `trackable_object/${ref._id}`);
  pets.push(pet);
  console.log(`  ${pet.details?.name ?? ref._id} → collier ${pet.device_id ?? '?'}`);
}
await save('pets.json', pets);

const trackerId = args.tracker ?? pets.find((p) => p.device_id)?.device_id;
if (!trackerId) fail('Aucun collier trouvé sur ce compte.');
const pet = pets.find((p) => p.device_id === trackerId);

const tracker = await call('GET', `tracker/${trackerId}`);
await save('tracker.json', tracker);

const t0 = Math.floor(from / 1000), t1 = Math.floor(to / 1000);
console.log(`→ Positions du ${from.toLocaleString('fr-FR')} au ${to.toLocaleString('fr-FR')}…`);
const raw = await call('GET', `tracker/${trackerId}/positions?time_from=${t0}&time_to=${t1}&format=json_segments`);
await save('positions-raw.json', raw);

// Le format "json_segments" renvoie une liste de segments, chacun une liste de points.
const segments = (Array.isArray(raw) ? raw : [raw])
  .map((seg) => (Array.isArray(seg) ? seg : [seg]))
  .map((seg) => seg.filter((p) => Array.isArray(p?.latlong)))
  .filter((seg) => seg.length);
const points = segments.flat();
if (!points.length) fail('Aucune position sur cette plage. Regarde data/positions-raw.json.');

// ---------- statistiques ----------
const haversine = ([la1, lo1], [la2, lo2]) => {
  const r = (d) => (d * Math.PI) / 180, R = 6371000;
  const a = Math.sin(r(la2 - la1) / 2) ** 2 + Math.cos(r(la1)) * Math.cos(r(la2)) * Math.sin(r(lo2 - lo1) / 2) ** 2;
  return 2 * R * Math.asin(Math.sqrt(a));
};
const gaps = points.slice(1).map((p, i) => p.time - points[i].time);
const dist = segments.reduce((s, seg) => s + seg.slice(1).reduce((d, p, i) => d + haversine(seg[i].latlong, p.latlong), 0), 0);
const unc = points.map((p) => p.pos_uncertainty).filter((u) => u != null).sort((a, b) => a - b);
const median = (a) => (a.length ? a[Math.floor(a.length / 2)] : null);
const sensors = points.reduce((m, p) => ((m[p.sensor_used ?? '?'] = (m[p.sensor_used ?? '?'] ?? 0) + 1), m), {});

console.log('\n=== Bilan ===');
console.log(`Points          : ${points.length} en ${segments.length} segment(s)`);
console.log(`Intervalle      : médian ${median([...gaps].sort((a, b) => a - b)) ?? '-'} s, min ${gaps.length ? Math.min(...gaps) : '-'} s, max ${gaps.length ? Math.max(...gaps) : '-'} s`);
console.log(`Imprécision     : médiane ${median(unc) ?? '-'} m, max ${unc.at(-1) ?? '-'} m`);
console.log(`Capteurs        : ${Object.entries(sensors).map(([k, v]) => `${k}=${v}`).join(', ')}`);
console.log(`Distance        : ${(dist / 1000).toFixed(2)} km`);

// ---------- GPX ----------
const name = pet?.details?.name ?? trackerId;
const iso = (t) => new Date(t * 1000).toISOString();
const gpx = `<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="mantrailing tractive-probe" xmlns="http://www.topografix.com/GPX/1/1">
  <trk><name>${name} ${from.toISOString()}</name>
${segments.map((seg) => `    <trkseg>\n${seg.map((p) => `      <trkpt lat="${p.latlong[0]}" lon="${p.latlong[1]}">${p.alt != null ? `<ele>${p.alt}</ele>` : ''}<time>${iso(p.time)}</time></trkpt>`).join('\n')}\n    </trkseg>`).join('\n')}
  </trk>
</gpx>
`;
await save('trace.gpx', gpx);

// ---------- carte HTML (Leaflet + OSM) ----------
const html = `<!doctype html><html lang="fr"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Trace ${name}</title>
<link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css">
<style>html,body,#map{height:100%;margin:0}</style></head><body><div id="map"></div>
<script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
<script>
const segments = ${JSON.stringify(segments.map((s) => s.map((p) => ({ ll: p.latlong, t: p.time, u: p.pos_uncertainty, s: p.sensor_used }))))};
const map = L.map('map');
// Fonds acceptant une page ouverte en file:// (les tuiles OSM officielles la refusent : pas de Referer).
const ign = (layer, fmt) => 'https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=' + layer
  + '&STYLE=normal&TILEMATRIXSET=PM&FORMAT=' + fmt + '&TILEMATRIX={z}&TILEROW={y}&TILECOL={x}';
const bases = {
  'Plan IGN': L.tileLayer(ign('GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2', 'image/png'), { maxZoom: 19, attribution: '© IGN' }),
  'Photos aériennes IGN': L.tileLayer(ign('ORTHOIMAGERY.ORTHOPHOTOS', 'image/jpeg'), { maxZoom: 19, attribution: '© IGN' }),
  'Satellite (Esri)': L.tileLayer('https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}', { maxZoom: 19, attribution: '© Esri' }),
  'Carto (OSM)': L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}.png', { maxZoom: 19, attribution: '© OpenStreetMap, © CARTO' }),
};
bases['Plan IGN'].addTo(map);
L.control.layers(bases).addTo(map);
const all = [];
for (const seg of segments) {
  L.polyline(seg.map(p => p.ll), { color: '#d9480f', weight: 4 }).addTo(map);
  for (const p of seg) {
    all.push(p.ll);
    if (p.u) L.circle(p.ll, { radius: p.u, color: '#d9480f', weight: 0, fillOpacity: .08 }).addTo(map);
    L.circleMarker(p.ll, { radius: 3, color: '#862e00' }).addTo(map)
      .bindPopup(new Date(p.t * 1000).toLocaleTimeString('fr-FR') + '<br>± ' + (p.u ?? '?') + ' m (' + (p.s ?? '?') + ')');
  }
}
map.fitBounds(all);
</script></body></html>
`;
await save('trace.html', html);
console.log(`\nFichiers écrits dans ${outDir} : positions-raw.json, trace.gpx, trace.html`);
