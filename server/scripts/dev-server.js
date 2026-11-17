'use strict';

// Serveur local pour les tests de bout en bout de l'appli (base MariaDB de npm run db:local, construite par les
// migrations et les seeds). Connexion Google SIMULÉE : le jeton « google:<id>:<nom> » suffit. Jamais en production
// (src/index.js vérifie, lui, les vrais jetons Google).
const mysql = require('mysql2/promise');
const { createApp } = require('../src/app');
const { createDatabase } = require('../src/db');
const { runMigrationsAndSeeds } = require('../migrate');

const port = Number(process.env.PORT || 3999);
const server = { host: '127.0.0.1', port: 3307, user: 'root', password: 'root' };
const database = 'mantrailing_dev';

(async () => {
  const admin = await mysql.createConnection(server);
  if (process.argv.includes('--reset')) await admin.query(`DROP DATABASE IF EXISTS ${database}`);
  await admin.query(`CREATE DATABASE IF NOT EXISTS ${database} CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`);
  await admin.end();

  const { db, knex } = createDatabase({ ...server, database });
  const { migrations } = await runMigrationsAndSeeds(knex);
  if (migrations.files.length) console.log(`Migrations : ${migrations.files.join(', ')}`);
  const verifyGoogleToken = async (idToken) => {
    const [prefix, sub, name] = idToken.split(':');
    if (prefix !== 'google' || !sub) throw new Error('jeton de test invalide');
    return { sub, email: `${sub}@exemple.fr`, name: name || sub };
  };
  createApp({ db, verifyGoogleToken, version: { commit: 'dev' }, rateLimits: false }).listen(port, () =>
    console.log(`Serveur de développement sur http://127.0.0.1:${port} (connexion Google simulée)`)
  );
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
