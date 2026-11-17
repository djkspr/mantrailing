'use strict';

// Point d'entrée lancé par Passenger (o2switch). Configuration : src/config.js (~/.config/mantrailing/.env sur le
// serveur, server/.env.local en local). La structure de la base n'est PAS modifiée au démarrage : migrations et seeds
// sont lancés par le déploiement (POST /deploy/run-migrations), comme pour l'API de la brasserie.
const path = require('node:path');
const { envFile, dbConfigFromEnv } = require('./config');
const { OAuth2Client } = require('google-auth-library');
const { createApp } = require('./app');
const { createDatabase } = require('./db');
const { runMigrationsAndSeeds } = require('../migrate');

const required = ['DB_HOST', 'DB_USER', 'DB_PASSWORD', 'DB_NAME', 'GOOGLE_CLIENT_ID'];
const missing = required.filter((key) => !process.env[key]);
if (missing.length) {
  console.error(`Configuration incomplète (${envFile}) : ${missing.join(', ')}`);
  process.exit(1);
}

const { db, knex } = createDatabase(dbConfigFromEnv());

// Jeton Google émis pour l'identifiant « Web » du projet (celui que l'appli demande).
const google = new OAuth2Client();
async function verifyGoogleToken(idToken) {
  const ticket = await google.verifyIdToken({ idToken, audience: process.env.GOOGLE_CLIENT_ID });
  const payload = ticket.getPayload();
  if (!payload?.sub || payload.email_verified === false) throw new Error('Jeton Google invalide');
  return { sub: payload.sub, email: payload.email, name: payload.name };
}

let version = {};
try {
  version = require('./version.json');
} catch {
  // Pas de version.json en local : écrit par deploy.ps1 au moment de l'envoi.
}

const app = createApp({
  db,
  verifyGoogleToken,
  version,
  deploy: {
    appRoot: path.join(__dirname, '..'),
    secret: process.env.DEPLOY_SECRET,
    runMigrations: () => runMigrationsAndSeeds(knex),
  },
});
const port = Number(process.env.PORT || 3000);
app.listen(port, () => console.log(`API Mantrailing à l'écoute (port ${port})`));
