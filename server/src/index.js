'use strict';

// Point d'entrée lancé par Passenger (o2switch).
// Secrets (base de données…) hors du dossier de l'application et jamais déployés : ~/.config/mantrailing/.env sur
// le serveur, lisible du seul compte d'hébergement (le compte FTP de déploiement n'y a pas accès). Autre emplacement :
// variable MANTRAILING_ENV_FILE. Poste de développement : server/.env.local (non versionné).
const path = require('node:path');
const os = require('node:os');
const dotenv = require('dotenv');

const envFile = process.env.MANTRAILING_ENV_FILE || path.join(os.homedir(), '.config', 'mantrailing', '.env');
dotenv.config({ path: path.join(__dirname, '..', '.env.local'), quiet: true });
dotenv.config({ path: envFile, override: true, quiet: true });

const { OAuth2Client } = require('google-auth-library');
const { createApp } = require('./app');
const { createPool, migrate } = require('./db');

const required = ['DB_HOST', 'DB_USER', 'DB_PASSWORD', 'DB_NAME', 'GOOGLE_CLIENT_ID'];
const missing = required.filter((key) => !process.env[key]);
if (missing.length) {
  console.error(`Configuration incomplète (${envFile}) : ${missing.join(', ')}`);
  process.exit(1);
}

const pool = createPool({
  dbHost: process.env.DB_HOST,
  dbPort: Number(process.env.DB_PORT || 3306),
  dbUser: process.env.DB_USER,
  dbPassword: process.env.DB_PASSWORD,
  dbName: process.env.DB_NAME,
});

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

migrate(pool)
  .then(() => {
    const app = createApp({ pool, verifyGoogleToken, version });
    const port = Number(process.env.PORT || 3000);
    app.listen(port, () => console.log(`mantrailing-api à l'écoute (port ${port})`));
  })
  .catch((err) => {
    console.error('Base de données inaccessible :', err.message);
    process.exit(1);
  });
