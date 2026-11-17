'use strict';

// Mise à jour de la structure (migrations) et des données (seeds), comme pour l'API de la brasserie.
const { test, before, after } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const express = require('express');
const { freshDatabase } = require('./helpers/db');
const { runMigrationsAndSeeds } = require('../migrate');
const { createDeployRouter } = require('../src/deploy');

let db;
let knex;
let first;

before(async () => {
  ({ db, knex, migrations: first } = await freshDatabase('mantrailing_test_migrations'));
});

after(async () => {
  await db.destroy();
  await knex.destroy();
});

const counter = async () => Number((await knex('counters').where({ name: 'items' }).first()).value);

test('base vide : toutes les migrations appliquées, données de référence posées', async () => {
  assert.deepEqual(first.migrations.files, ['20261117_01_initial_schema.js']);
  assert.deepEqual(first.seeds.map((f) => path.basename(f)), ['20261117_counters_seed.js']);
  for (const table of ['users', 'sessions', 'teams', 'team_members', 'invites', 'items', 'counters']) {
    assert.ok(await knex.schema.hasTable(table), table);
  }
  assert.equal(await counter(), 0);
});

test('relancées à chaque déploiement : rien de neuf, compteur jamais remis à zéro', async () => {
  await knex('counters').where({ name: 'items' }).update({ value: 42 });
  const again = await runMigrationsAndSeeds(knex);
  assert.deepEqual(again.migrations.files, []);
  assert.equal(await counter(), 42);
});

test('retour en arrière puis réapplication', async () => {
  await knex.migrate.rollback(undefined, true);
  assert.equal(await knex.schema.hasTable('items'), false);
  const reapplied = await runMigrationsAndSeeds(knex);
  assert.deepEqual(reapplied.migrations.files, ['20261117_01_initial_schema.js']);
  assert.equal(await counter(), 0);
});

test('route de déploiement : migrations et seeds avec le secret, refus sans', async () => {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'mantrailing-migrate-'));
  const app = express();
  app.use(express.json());
  app.use('/deploy', createDeployRouter({ appRoot: root, secret: 's', runMigrations: () => runMigrationsAndSeeds(knex) }));
  const server = await new Promise((resolve) => {
    const s = app.listen(0, () => resolve(s));
  });
  const url = `http://127.0.0.1:${server.address().port}/deploy/run-migrations`;
  try {
    assert.equal((await fetch(url, { method: 'POST' })).status, 403);
    const res = await fetch(url, { method: 'POST', headers: { 'x-deploy-secret': 's' } });
    assert.equal(res.status, 200);
    const body = await res.json();
    assert.equal(body.success, true);
    assert.deepEqual(body.migrations.files, []);
    assert.deepEqual(body.seeds, ['20261117_counters_seed.js']);
  } finally {
    server.close();
    fs.rmSync(root, { recursive: true, force: true });
  }
});
