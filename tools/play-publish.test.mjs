// Tests de tools/play-publish.mjs contre un faux Google (aucun appel réseau).
// Lancement : node --test "tools/*.test.mjs"
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createVerify, generateKeyPairSync } from 'node:crypto';
import { mkdtemp, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { accessToken, publish } from './play-publish.mjs';

const { privateKey, publicKey } = generateKeyPairSync('rsa', { modulusLength: 2048 });
const key = {
  client_email: 'release@projet.iam.gserviceaccount.com',
  private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }),
  token_uri: 'https://oauth.test/token',
};

/** Faux serveurs OAuth + Play : enregistre les appels, réponses scriptables. */
function fakeGoogle({ draftApp = false, tokenStatus = 200 } = {}) {
  const calls = [];
  let committedStatus;
  const fetchImpl = async (url, init = {}) => {
    const u = new URL(url);
    const body = init.body;
    calls.push({ method: init.method, path: u.pathname + u.search, headers: init.headers, body });
    const reply = (status, obj) => new Response(JSON.stringify(obj), { status });
    if (u.host === 'oauth.test') return tokenStatus === 200 ? reply(200, { access_token: 'jeton' }) : reply(tokenStatus, { error: 'invalid_grant' });
    if (init.headers?.authorization !== 'Bearer jeton') return reply(401, { error: { message: 'no auth' } });
    const p = u.pathname;
    if (init.method === 'POST' && p.endsWith('/edits')) return reply(200, { id: 'E1' });
    if (p.includes('/upload/') && p.endsWith('/edits/E1/bundles')) return reply(200, { versionCode: 11, sha256: 'x' });
    if (init.method === 'PUT' && p.endsWith('/edits/E1/tracks/internal')) {
      committedStatus = JSON.parse(body).releases[0].status;
      return reply(200, JSON.parse(body));
    }
    if (p.endsWith('/edits/E1:commit')) {
      if (draftApp && committedStatus !== 'draft') return reply(400, { error: { message: 'Only releases with status draft may be created on draft app.' } });
      return reply(200, { id: 'E1' });
    }
    return reply(404, { error: { message: `inattendu ${init.method} ${p}` } });
  };
  return { calls, fetchImpl };
}

async function fakeAab() {
  const dir = await mkdtemp(join(tmpdir(), 'play-test-'));
  const file = join(dir, 'app.aab');
  await writeFile(file, Buffer.from('PK-faux-aab'));
  return file;
}

test('jeton : JWT RS256 signé avec la clé du compte de service', async () => {
  const { calls, fetchImpl } = fakeGoogle();
  assert.equal(await accessToken(key, { fetchImpl, now: 1_800_000_000_000 }), 'jeton');

  const assertion = new URLSearchParams(calls[0].body).get('assertion');
  const [h, c, s] = assertion.split('.');
  assert.ok(createVerify('RSA-SHA256').update(`${h}.${c}`).verify(publicKey, s, 'base64url'), 'signature valide');
  const claims = JSON.parse(Buffer.from(c, 'base64url'));
  assert.equal(claims.iss, key.client_email);
  assert.equal(claims.scope, 'https://www.googleapis.com/auth/androidpublisher');
  assert.equal(claims.aud, key.token_uri);
  assert.equal(claims.exp - claims.iat, 3600);
});

test('jeton refusé : message clair', async () => {
  const { fetchImpl } = fakeGoogle({ tokenStatus: 400 });
  await assert.rejects(accessToken(key, { fetchImpl }), /Authentification Google refusée \(HTTP 400\)/);
});

test('publication : edit, envoi de l\'AAB, piste interne, validation', async () => {
  const { calls, fetchImpl } = fakeGoogle();
  const result = await publish({ key, aab: await fakeAab(), packageName: 'app.mantrailing', notes: 'Mode démo', fetchImpl, log: () => {} });

  assert.deepEqual(result, { versionCode: 11, status: 'completed' });
  const play = calls.slice(1).map((c) => `${c.method} ${c.path}`);
  assert.deepEqual(play, [
    'POST /androidpublisher/v3/applications/app.mantrailing/edits',
    'POST /upload/androidpublisher/v3/applications/app.mantrailing/edits/E1/bundles?uploadType=media',
    'PUT /androidpublisher/v3/applications/app.mantrailing/edits/E1/tracks/internal',
    'POST /androidpublisher/v3/applications/app.mantrailing/edits/E1:commit',
  ]);
  assert.equal(calls[2].headers['content-type'], 'application/octet-stream');
  assert.equal(calls[2].body.toString(), 'PK-faux-aab');
  const release = JSON.parse(calls[3].body).releases[0];
  assert.deepEqual(release.versionCodes, ['11']);
  assert.deepEqual(release.releaseNotes, [{ language: 'fr-FR', text: 'Mode démo' }]);
});

test('appli encore en brouillon : bascule en release « draft »', async () => {
  const { calls, fetchImpl } = fakeGoogle({ draftApp: true });
  const result = await publish({ key, aab: await fakeAab(), packageName: 'app.mantrailing', fetchImpl, log: () => {} });

  assert.equal(result.status, 'draft');
  const statuses = calls.filter((c) => c.method === 'PUT').map((c) => JSON.parse(c.body).releases[0].status);
  assert.deepEqual(statuses, ['completed', 'draft']);
});

test('autre erreur Play : remontée telle quelle', async () => {
  const { fetchImpl } = fakeGoogle();
  const failing = async (url, init) => (String(url).includes('/bundles') ? new Response(JSON.stringify({ error: { message: 'APK specifies a version code that has already been used.' } }), { status: 403 }) : fetchImpl(url, init));
  await assert.rejects(
    publish({ key, aab: await fakeAab(), packageName: 'app.mantrailing', fetchImpl: failing, log: () => {} }),
    /HTTP 403 APK specifies a version code that has already been used/,
  );
});
