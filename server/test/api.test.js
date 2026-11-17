'use strict';

// Tests de l'API contre une vraie base MariaDB (npm run db:local), neuve et construite par les migrations,
// connexion Google simulée.
const { test, before, after, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { createApp } = require('../src/app');
const { freshDatabase } = require('./helpers/db');

let db;
let knex;
// Vérifications directes en base : knex.raw rend [lignes, champs], comme mysql2.
const pool = { query: (statement, params) => knex.raw(statement, params ?? []) };

// Jeton Google de test : « google:<sub>:<nom> » ; tout autre jeton est refusé.
async function verifyGoogleToken(idToken) {
  const [prefix, sub, name] = idToken.split(':');
  if (prefix !== 'google' || !sub) throw new Error('jeton invalide');
  return { sub, email: `${sub}@exemple.fr`, name };
}

let server;
let base;

before(async () => {
  ({ db, knex } = await freshDatabase());
  const app = createApp({ db, verifyGoogleToken, version: { commit: 'test' }, rateLimits: false });
  await new Promise((resolve) => {
    server = app.listen(0, resolve);
  });
  base = `http://127.0.0.1:${server.address().port}`;
});

after(async () => {
  server.close();
  await db.destroy();
  await knex.destroy();
});

beforeEach(async () => {
  for (const table of ['items', 'invites', 'team_members', 'teams', 'sessions', 'users']) {
    await pool.query(`DELETE FROM ${table}`);
  }
});

async function call(method, path, { token, body } = {}) {
  const res = await fetch(base + path, {
    method,
    headers: {
      ...(body ? { 'content-type': 'application/json' } : {}),
      ...(token ? { authorization: `Bearer ${token}` } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  return { status: res.status, body: text ? JSON.parse(text) : null };
}

async function signIn(sub, name = sub) {
  const res = await call('POST', '/auth/google', { body: { idToken: `google:${sub}:${name}` } });
  assert.equal(res.status, 200);
  return res.body.token;
}

const item = (id, updatedAt, json = `{"v":${updatedAt}}`, kind = 'SESSION') => ({
  kind,
  id,
  updatedAt,
  deleted: false,
  json,
});

const push = (token, space, items) => call('POST', `/spaces/${space}/items`, { token, body: { items } });
const pull = (token, space, after = 0) => call('GET', `/spaces/${space}/items?after=${after}`, { token });

// ---------- Connexion ----------

test('connexion Google : compte créé, jeton propre à l’appli, profil', async () => {
  const token = await signIn('alice', 'Alice');
  const me = await call('GET', '/me', { token });
  assert.equal(me.status, 200);
  assert.equal(me.body.user.name, 'Alice');
  assert.deepEqual(me.body.teams, []);

  // Seconde connexion du même compte : même utilisateur, nouveau jeton.
  const again = await signIn('alice', 'Alice');
  assert.notEqual(again, token);
  assert.equal((await call('GET', '/me', { token: again })).body.user.id, me.body.user.id);
  // Le jeton n'est gardé que sous forme d'empreinte.
  const [[row]] = await pool.query('SELECT COUNT(*) AS n FROM sessions WHERE token_hash = ?', [token]);
  assert.equal(row.n, 0);
});

test('jeton Google refusé, jeton de l’appli absent ou inconnu', async () => {
  assert.equal((await call('POST', '/auth/google', { body: { idToken: 'faux-jeton-123' } })).status, 401);
  assert.equal((await call('GET', '/me')).status, 401);
  assert.equal((await call('GET', '/me', { token: 'inconnu' })).status, 401);
});

test('déconnexion : le jeton ne sert plus', async () => {
  const token = await signIn('alice');
  assert.equal((await call('POST', '/auth/logout', { token })).status, 204);
  assert.equal((await call('GET', '/me', { token })).status, 401);
});

// ---------- Espace perso ----------

test('espace perso : envoi puis réception, curseur, chacun le sien', async () => {
  const alice = await signIn('alice');
  const bob = await signIn('bob');
  assert.equal((await push(alice, 'perso', [item('s1', 100), item('d1', 100, '{}', 'DOG')])).status, 200);

  const first = await pull(alice, 'perso');
  assert.deepEqual(first.body.items.map((i) => i.id), ['s1', 'd1']);
  assert.equal((await pull(alice, 'perso', first.body.cursor)).body.items.length, 0);

  // L'espace perso de Bob est vide : il ne voit rien de celui d'Alice.
  assert.equal((await pull(bob, 'perso')).body.items.length, 0);
  assert.equal((await call('GET', '/spaces/perso/items/SESSION/s1', { token: bob })).status, 404);
  assert.equal((await call('GET', '/spaces/perso/items/SESSION/s1', { token: alice })).body.json, '{"v":100}');
});

test('la version la plus récente l’emporte, une plus ancienne est ignorée', async () => {
  const alice = await signIn('alice');
  await push(alice, 'perso', [item('s1', 300, 'récente')]);
  const { cursor } = (await pull(alice, 'perso')).body;
  await push(alice, 'perso', [item('s1', 200, 'ancienne')]);

  const stored = await call('GET', '/spaces/perso/items/SESSION/s1', { token: alice });
  assert.equal(stored.body.json, 'récente');
  assert.equal(stored.body.updatedAt, 300);
  // Version ignorée : rien de nouveau à recevoir.
  assert.equal((await pull(alice, 'perso', cursor)).body.items.length, 0);

  await push(alice, 'perso', [item('s1', 400, 'plus récente')]);
  assert.deepEqual((await pull(alice, 'perso', cursor)).body.items.map((i) => i.json), ['plus récente']);
});

test('suppression transmise, contenu effacé du serveur', async () => {
  const alice = await signIn('alice');
  await push(alice, 'perso', [item('s1', 100)]);
  const { cursor } = (await pull(alice, 'perso')).body;
  await push(alice, 'perso', [{ kind: 'SESSION', id: 's1', updatedAt: 200, deleted: true, json: '{"x":1}' }]);

  const changes = (await pull(alice, 'perso', cursor)).body.items;
  assert.deepEqual(changes, [{ kind: 'SESSION', id: 's1', updatedAt: 200, deleted: true, json: null }]);
  assert.equal((await call('GET', '/spaces/perso/items/SESSION/s1', { token: alice })).status, 404);
  const [[row]] = await pool.query("SELECT json FROM items WHERE item_id = 's1'");
  assert.equal(row.json, null);
});

test('réception par pages', async () => {
  const alice = await signIn('alice');
  await push(alice, 'perso', Array.from({ length: 250 }, (_, i) => item(`s${i}`, 1)));
  const page1 = (await pull(alice, 'perso')).body;
  assert.equal(page1.items.length, 200);
  assert.equal(page1.more, true);
  const page2 = (await pull(alice, 'perso', page1.cursor)).body;
  assert.equal(page2.items.length, 50);
  assert.equal(page2.more, false);
});

test('envois invalides refusés', async () => {
  const alice = await signIn('alice');
  assert.equal((await push(alice, 'perso', [{ ...item('s1', 1), kind: 'AUTRE' }])).status, 400);
  assert.equal((await push(alice, 'perso', [item('../x', 1)])).status, 400);
  assert.equal((await push(alice, 'perso', [item('s1', 1, 'x'.repeat(1_000_001))])).status, 400);
});

// ---------- Équipes ----------

test('équipe : invitation, adhésion, partage limité aux éléments de l’équipe', async () => {
  const alice = await signIn('alice', 'Alice');
  const bob = await signIn('bob', 'Bob');
  const team = (await call('POST', '/teams', { token: alice, body: { name: '  Club canin  ' } })).body;
  assert.equal(team.name, 'Club canin');

  // Avant d'y être invité, Bob ne voit pas l'équipe (404, comme si elle n'existait pas).
  await push(alice, team.id, [item('club-1', 100)]);
  await push(alice, 'perso', [item('perso-alice', 100)]);
  assert.equal((await pull(bob, team.id)).status, 404);
  assert.equal((await call('POST', `/teams/${team.id}/invites`, { token: bob })).status, 404);

  const invite = (await call('POST', `/teams/${team.id}/invites`, { token: alice })).body;
  assert.match(invite.code, /^[A-HJKMNP-Z2-9]{8}$/);
  const typed = `${invite.code.slice(0, 4).toLowerCase()}-${invite.code.slice(4)}`;
  const joined = await call('POST', '/teams/join', { token: bob, body: { code: typed } });
  assert.equal(joined.status, 200);
  assert.deepEqual(Object.values(joined.body.members).sort(), ['Alice', 'Bob']);

  // Bob voit ce qui a été fait dans l'équipe, et seulement ça.
  assert.deepEqual((await pull(bob, team.id)).body.items.map((i) => i.id), ['club-1']);
  assert.deepEqual((await pull(bob, 'perso')).body.items, []);
  // Ce que Bob ajoute dans l'équipe, Alice le reçoit ; son perso reste à lui.
  await push(bob, team.id, [item('club-2', 100)]);
  await push(bob, 'perso', [item('perso-bob', 100)]);
  assert.deepEqual((await pull(alice, team.id)).body.items.map((i) => i.id), ['club-1', 'club-2']);
  assert.deepEqual((await pull(alice, 'perso')).body.items.map((i) => i.id), ['perso-alice']);

  const me = (await call('GET', '/me', { token: bob })).body;
  assert.deepEqual(me.teams.map((t) => t.name), ['Club canin']);
});

test('code d’invitation inconnu ou expiré', async () => {
  const alice = await signIn('alice');
  const bob = await signIn('bob');
  const team = (await call('POST', '/teams', { token: alice, body: { name: 'Famille' } })).body;
  const { code } = (await call('POST', `/teams/${team.id}/invites`, { token: alice })).body;
  await pool.query('UPDATE invites SET expires_at = NOW() - INTERVAL 1 DAY WHERE code = ?', [code]);

  assert.equal((await call('POST', '/teams/join', { token: bob, body: { code } })).status, 410);
  assert.equal((await call('POST', '/teams/join', { token: bob, body: { code: 'ZZZZZZZZ' } })).status, 404);
  assert.equal((await pull(bob, team.id)).status, 404);
});

test('quitter une équipe ; le dernier membre emporte l’équipe et ses données', async () => {
  const alice = await signIn('alice');
  const bob = await signIn('bob');
  const team = (await call('POST', '/teams', { token: alice, body: { name: 'Famille' } })).body;
  const { code } = (await call('POST', `/teams/${team.id}/invites`, { token: alice })).body;
  await call('POST', '/teams/join', { token: bob, body: { code } });
  await push(alice, team.id, [item('f1', 1)]);

  assert.equal((await call('DELETE', `/teams/${team.id}/members/me`, { token: bob })).status, 204);
  assert.equal((await pull(bob, team.id)).status, 404);
  assert.equal((await pull(alice, team.id)).body.items.length, 1);

  await call('DELETE', `/teams/${team.id}/members/me`, { token: alice });
  const [[{ n }]] = await pool.query("SELECT COUNT(*) AS n FROM items WHERE space = ?", [`t:${team.id}`]);
  assert.equal(n, 0);
  const [[{ t }]] = await pool.query('SELECT COUNT(*) AS t FROM teams');
  assert.equal(t, 0);
});

test('suppression du compte : perso effacé, équipes partagées conservées pour les autres', async () => {
  const alice = await signIn('alice');
  const bob = await signIn('bob');
  const shared = (await call('POST', '/teams', { token: alice, body: { name: 'Club' } })).body;
  const alone = (await call('POST', '/teams', { token: alice, body: { name: 'Solo' } })).body;
  const { code } = (await call('POST', `/teams/${shared.id}/invites`, { token: alice })).body;
  await call('POST', '/teams/join', { token: bob, body: { code } });
  await push(alice, 'perso', [item('p1', 1)]);
  await push(alice, shared.id, [item('c1', 1)]);
  await push(alice, alone.id, [item('a1', 1)]);

  assert.equal((await call('DELETE', '/me', { token: alice })).status, 204);
  assert.equal((await call('GET', '/me', { token: alice })).status, 401);

  const [rows] = await pool.query('SELECT item_id FROM items ORDER BY item_id');
  assert.deepEqual(rows.map((r) => r.item_id), ['c1']);
  const bobTeams = (await call('GET', '/me', { token: bob })).body.teams;
  assert.deepEqual(bobTeams.map((t) => [t.name, Object.keys(t.members).length]), [['Club', 1]]);
});

test('version et routes inconnues', async () => {
  assert.equal((await call('GET', '/version')).body.commit, 'test');
  assert.equal((await call('GET', '/nimporte-quoi')).status, 404);
});
