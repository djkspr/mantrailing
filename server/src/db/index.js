'use strict';

// Accès à la base, comme pour l'API des Brasseurs du Pastel : Kysely pour les requêtes, knex pour les migrations et
// les seeds (knexfile.js, migrations/, seeds/).
const { Kysely, MysqlDialect } = require('kysely');
const mysql = require('mysql2');
const knexLib = require('knex');
const { knexConfig } = require('../../knexfile');

/**
 * @param {{host: string, port: number, user: string, password: string, database: string}} connection
 * @returns {{ db: import('kysely').Kysely<import('./db/types').DB>, knex: import('knex').Knex }}
 */
function createDatabase(connection) {
  const db = new Kysely({
    dialect: new MysqlDialect({
      pool: mysql.createPool({
        ...connection,
        connectionLimit: 5,
        charset: 'utf8mb4',
        // Les BIGINT (updated_at en millisecondes, seq) restent des nombres JS exacts jusqu'à 2^53.
        supportBigNumbers: true,
        bigNumberStrings: false,
      }),
    }),
  });
  const knex = knexLib(knexConfig(connection));
  return { db, knex };
}

module.exports = { createDatabase };
