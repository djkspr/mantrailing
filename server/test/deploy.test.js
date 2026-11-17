'use strict';

// Installation des dépendances pendant un déploiement : secret, « déjà à jour », installation réussie ou en échec.
// La commande npm est remplacée par une commande node simulée (aucune installation réelle).
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const express = require('express');
const { createDeployRouter } = require('../src/deploy');

let root;
let exitCode = 0;
let server;
let base;

before(async () => {
  root = fs.mkdtempSync(path.join(os.tmpdir(), 'mantrailing-deploy-'));
  fs.writeFileSync(path.join(root, 'package-lock.json'), '{"lockfileVersion":3}');
  const app = express();
  app.use(express.json());
  app.use(
    '/deploy',
    createDeployRouter({
      appRoot: root,
      secret: 'le-secret',
      npmCommand: () => ({ cmd: process.execPath, args: ['-e', `console.log('npm simulé'); process.exit(${exitCode})`] }),
    })
  );
  await new Promise((resolve) => {
    server = app.listen(0, resolve);
  });
  base = `http://127.0.0.1:${server.address().port}/deploy/npm-install`;
});

after(() => {
  server.close();
  fs.rmSync(root, { recursive: true, force: true });
});

const call = async (method, secret, body) => {
  const res = await fetch(base, {
    method,
    headers: { 'content-type': 'application/json', ...(secret ? { 'x-deploy-secret': secret } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  return { status: res.status, body: await res.json() };
};

async function waitFinished() {
  for (let i = 0; i < 50; i++) {
    const { body } = await call('GET', 'le-secret');
    if (body.state !== 'running') return body;
    await new Promise((r) => setTimeout(r, 100));
  }
  throw new Error('installation jamais terminée');
}

test('sans le bon secret : refusé', async () => {
  assert.equal((await call('POST', null, {})).status, 403);
  assert.equal((await call('POST', 'mauvais', {})).status, 403);
  assert.equal((await call('GET', 'mauvais')).status, 403);
});

test('installation, puis « déjà à jour » tant que package-lock.json ne change pas', async () => {
  assert.deepEqual((await call('GET', 'le-secret')).body, { state: 'none' });
  const start = await call('POST', 'le-secret', {});
  assert.equal(start.status, 202);
  const done = await waitFinished();
  assert.equal(done.state, 'done');
  assert.match(done.output, /npm simulé/);

  assert.deepEqual((await call('POST', 'le-secret', {})).body, { status: 'up-to-date' });

  // Nouvelles dépendances : nouvelle installation.
  fs.writeFileSync(path.join(root, 'package-lock.json'), '{"lockfileVersion":3,"packages":{}}');
  assert.equal((await call('POST', 'le-secret', {})).body.status, 'started');
  assert.equal((await waitFinished()).state, 'done');
});

test('installation en échec : état « failed » avec le code de sortie, à refaire', async () => {
  exitCode = 1;
  assert.equal((await call('POST', 'le-secret', { force: true })).body.status, 'started');
  const failed = await waitFinished();
  assert.equal(failed.state, 'failed');
  assert.equal(failed.exitCode, 1);
  exitCode = 0;
});

test('sans secret configuré : routes absentes', async () => {
  const app = express();
  app.use('/deploy', createDeployRouter({ appRoot: root, secret: undefined }));
  const other = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  const res = await fetch(`http://127.0.0.1:${other.address().port}/deploy/npm-install`, {
    headers: { 'x-deploy-secret': 'nimporte' },
  });
  other.close();
  assert.equal(res.status, 404);
});
