'use strict';

// Chargement de la configuration, commun au serveur (src/index.js) et à knex (knexfile.js).
// Secrets hors du dossier de l'application et jamais déployés : ~/.config/mantrailing/.env sur le serveur, lisible du
// seul compte d'hébergement (le compte FTP de déploiement n'y a pas accès). Autre emplacement : MANTRAILING_ENV_FILE.
// Poste de développement : server/.env.local (non versionné), lu en premier.
const path = require('node:path');
const os = require('node:os');
const dotenv = require('dotenv');

const envFile = process.env.MANTRAILING_ENV_FILE || path.join(os.homedir(), '.config', 'mantrailing', '.env');
dotenv.config({ path: path.join(__dirname, '..', '.env.local'), quiet: true });
dotenv.config({ path: envFile, override: true, quiet: true });

/** Connexion à la base, depuis les variables DB_*. */
function dbConfigFromEnv() {
  return {
    host: process.env.DB_HOST,
    port: Number(process.env.DB_PORT || 3306),
    user: process.env.DB_USER,
    password: process.env.DB_PASSWORD,
    database: process.env.DB_NAME,
  };
}

module.exports = { envFile, dbConfigFromEnv };
