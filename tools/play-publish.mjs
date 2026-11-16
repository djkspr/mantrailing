#!/usr/bin/env node
// Envoie un AAB sur Google Play (API Android Publisher v3) et le publie sur une piste de test.
// Aucune dépendance : authentification par compte de service (JWT signé RS256).
//
// Usage : node tools/play-publish.mjs --aab release/x.aab --key <compte-de-service.json>
//           [--package app.mantrailing] [--track internal] [--notes "texte"]

import { createSign } from 'node:crypto';
import { readFile } from 'node:fs/promises';
import { pathToFileURL } from 'node:url';

const SCOPE = 'https://www.googleapis.com/auth/androidpublisher';
const API = 'https://androidpublisher.googleapis.com';

const b64url = (data) => Buffer.from(data).toString('base64url');

/** Jeton d'accès OAuth à partir de la clé du compte de service. */
export async function accessToken(key, { now = Date.now(), fetchImpl = fetch } = {}) {
  const iat = Math.floor(now / 1000);
  const tokenUri = key.token_uri ?? 'https://oauth2.googleapis.com/token';
  const header = b64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }));
  const claims = b64url(JSON.stringify({ iss: key.client_email, scope: SCOPE, aud: tokenUri, iat, exp: iat + 3600 }));
  const signature = createSign('RSA-SHA256').update(`${header}.${claims}`).sign(key.private_key, 'base64url');
  const res = await fetchImpl(tokenUri, {
    method: 'POST',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion: `${header}.${claims}.${signature}`,
    }),
  });
  const body = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(`Authentification Google refusée (HTTP ${res.status}) : ${body.error_description ?? body.error ?? ''}`);
  return body.access_token;
}

/**
 * Crée une modification (edit), envoie l'AAB, l'affecte à la piste et valide.
 * Une appli jamais publiée (« brouillon ») n'accepte que des releases en statut « draft » : on bascule alors
 * automatiquement, la release est à valider à la main dans la Play Console.
 */
export async function publish({ key, aab, packageName, track = 'internal', notes, api = API, fetchImpl = fetch, log = console.log }) {
  const token = await accessToken(key, { fetchImpl });
  const call = async (method, path, { body, headers = {}, base = `${api}/androidpublisher/v3` } = {}) => {
    const res = await fetchImpl(`${base}/applications/${packageName}${path}`, {
      method,
      headers: { authorization: `Bearer ${token}`, ...headers },
      body,
    });
    const text = await res.text();
    const json = text ? JSON.parse(text) : {};
    if (!res.ok) {
      const err = new Error(`Google Play : ${method} ${path} -> HTTP ${res.status} ${json.error?.message ?? text.slice(0, 300)}`);
      err.apiMessage = json.error?.message ?? '';
      throw err;
    }
    return json;
  };
  const jsonBody = (obj) => ({ body: JSON.stringify(obj), headers: { 'content-type': 'application/json' } });

  const edit = await call('POST', '/edits', jsonBody({}));
  log(`Modification Play ouverte (${edit.id})`);
  const bundle = await call('POST', `/edits/${edit.id}/bundles?uploadType=media`, {
    base: `${api}/upload/androidpublisher/v3`,
    body: await readFile(aab),
    headers: { 'content-type': 'application/octet-stream' },
  });
  log(`AAB envoyé : versionCode ${bundle.versionCode}`);

  const assign = (status) => call('PUT', `/edits/${edit.id}/tracks/${track}`, jsonBody({
    track,
    releases: [{
      versionCodes: [String(bundle.versionCode)],
      status,
      ...(notes ? { releaseNotes: [{ language: 'fr-FR', text: notes.slice(0, 500) }] } : {}),
    }],
  }));

  let status = 'completed';
  try {
    await assign(status);
    await call('POST', `/edits/${edit.id}:commit`);
  } catch (e) {
    if (!/draft/i.test(e.apiMessage ?? '')) throw e;
    status = 'draft';
    log('Appli encore en brouillon sur Google Play : release créée en statut « brouillon ».');
    await assign(status);
    await call('POST', `/edits/${edit.id}:commit`);
  }
  log(`Publié sur la piste « ${track} » (statut ${status}).`);
  return { versionCode: bundle.versionCode, status };
}

// ---------- ligne de commande ----------
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = {};
  for (let i = 2; i < process.argv.length; i += 2) args[process.argv[i].replace(/^--/, '')] = process.argv[i + 1];
  if (!args.aab || !args.key) {
    console.error('Usage : node tools/play-publish.mjs --aab <fichier.aab> --key <compte-de-service.json> [--track internal] [--notes "..."]');
    process.exit(2);
  }
  try {
    const key = JSON.parse(await readFile(args.key, 'utf8'));
    await publish({ key, aab: args.aab, packageName: args.package ?? 'app.mantrailing', track: args.track ?? 'internal', notes: args.notes });
  } catch (e) {
    console.error(`✖ ${e.message}`);
    process.exit(1);
  }
}
