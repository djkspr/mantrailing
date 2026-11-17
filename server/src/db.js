'use strict';

const mysql = require('mysql2/promise');

/**
 * Schéma de la base. Chaque instruction est idempotente : elle est rejouée à chaque démarrage
 * (pas d'outil de migration pour une base aussi petite ; une évolution s'ajoute en fin de liste).
 */
const SCHEMA = [
  `CREATE TABLE IF NOT EXISTS users (
     id BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
     google_sub VARCHAR(64) NOT NULL UNIQUE,
     email VARCHAR(255) NULL,
     name VARCHAR(100) NOT NULL,
     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`,
  // Jetons de connexion de l'appli : seule leur empreinte SHA-256 est gardée.
  `CREATE TABLE IF NOT EXISTS sessions (
     token_hash CHAR(64) PRIMARY KEY,
     user_id BIGINT UNSIGNED NOT NULL,
     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     last_used_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     INDEX (user_id),
     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
   ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`,
  `CREATE TABLE IF NOT EXISTS teams (
     id CHAR(16) PRIMARY KEY,
     name VARCHAR(60) NOT NULL,
     owner_id BIGINT UNSIGNED NOT NULL,
     created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
   ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`,
  `CREATE TABLE IF NOT EXISTS team_members (
     team_id CHAR(16) NOT NULL,
     user_id BIGINT UNSIGNED NOT NULL,
     joined_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
     PRIMARY KEY (team_id, user_id),
     INDEX (user_id),
     FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE,
     FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
   ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`,
  `CREATE TABLE IF NOT EXISTS invites (
     code CHAR(8) PRIMARY KEY,
     team_id CHAR(16) NOT NULL,
     created_by BIGINT UNSIGNED NOT NULL,
     expires_at DATETIME NOT NULL,
     FOREIGN KEY (team_id) REFERENCES teams (id) ON DELETE CASCADE
   ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`,
  // Éléments synchronisés (recherches, pistes posées, chiens), par espace :
  // « u:<id utilisateur> » pour l'espace perso, « t:<id équipe> » pour une équipe.
  // seq : numéro croissant de la dernière écriture, curseur de réception des téléphones.
  `CREATE TABLE IF NOT EXISTS items (
     space VARCHAR(24) NOT NULL,
     kind VARCHAR(8) NOT NULL,
     item_id VARCHAR(64) NOT NULL,
     updated_at BIGINT NOT NULL,
     deleted TINYINT(1) NOT NULL DEFAULT 0,
     json MEDIUMTEXT NULL,
     seq BIGINT UNSIGNED NOT NULL,
     PRIMARY KEY (space, kind, item_id),
     INDEX space_seq (space, seq)
   ) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`,
  `CREATE TABLE IF NOT EXISTS counters (
     name VARCHAR(16) PRIMARY KEY,
     value BIGINT UNSIGNED NOT NULL
   )`,
  `INSERT IGNORE INTO counters (name, value) VALUES ('items', 0)`,
];

function createPool(config) {
  return mysql.createPool({
    host: config.dbHost,
    port: config.dbPort,
    user: config.dbUser,
    password: config.dbPassword,
    database: config.dbName,
    connectionLimit: 5,
    charset: 'utf8mb4',
    // Les BIGINT (updated_at en millisecondes, seq) restent des nombres JS exacts jusqu'à 2^53.
    supportBigNumbers: true,
    bigNumberStrings: false,
  });
}

async function migrate(pool) {
  for (const statement of SCHEMA) await pool.query(statement);
}

module.exports = { createPool, migrate };
