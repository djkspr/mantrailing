'use strict';

// Serveur local pour les tests de bout en bout de l'appli (base MariaDB de npm run db:local).
// Connexion Google SIMULÉE : le jeton « google:<id>:<nom> » suffit. Jamais en production
// (src/index.js vérifie, lui, les vrais jetons Google).
const mysql = require('mysql2/promise');
const { createApp } = require('../src/app');
const { createPool, migrate } = require('../src/db');

const port = Number(process.env.PORT || 3999);
const database = 'mantrailing_dev';

(async () => {
  const admin = await mysql.createConnection({ host: '127.0.0.1', port: 3307, user: 'root', password: 'root' });
  if (process.argv.includes('--reset')) await admin.query(`DROP DATABASE IF EXISTS ${database}`);
  await admin.query(`CREATE DATABASE IF NOT EXISTS ${database} CHARACTER SET utf8mb4`);
  await admin.end();

  const pool = createPool({ dbHost: '127.0.0.1', dbPort: 3307, dbUser: 'root', dbPassword: 'root', dbName: database });
  await migrate(pool);
  const verifyGoogleToken = async (idToken) => {
    const [prefix, sub, name] = idToken.split(':');
    if (prefix !== 'google' || !sub) throw new Error('jeton de test invalide');
    return { sub, email: `${sub}@exemple.fr`, name: name || sub };
  };
  createApp({ pool, verifyGoogleToken, version: { commit: 'dev' }, rateLimits: false }).listen(port, () =>
    console.log(`Serveur de développement sur http://127.0.0.1:${port} (connexion Google simulée)`)
  );
})().catch((err) => {
  console.error(err);
  process.exit(1);
});
