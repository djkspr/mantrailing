'use strict';

// Base de test neuve à chaque lancement (MariaDB locale, npm run db:local), construite par les migrations et les
// seeds : les tests vérifient donc aussi que les migrations s'appliquent sur une base vide.
const mysql = require('mysql2/promise');
const { createDatabase } = require('../../src/db');
const { runMigrationsAndSeeds } = require('../../migrate');

const server = {
  host: process.env.TEST_DB_HOST || '127.0.0.1',
  port: Number(process.env.TEST_DB_PORT || 3307),
  user: process.env.TEST_DB_USER || 'root',
  password: process.env.TEST_DB_PASSWORD || 'root',
};

/** @returns {Promise<{db: import('kysely').Kysely<any>, knex: import('knex').Knex, migrations: object}>} */
async function freshDatabase(database = process.env.TEST_DB_NAME || 'mantrailing_test') {
  const admin = await mysql.createConnection(server);
  await admin.query(`DROP DATABASE IF EXISTS \`${database}\``);
  await admin.query(`CREATE DATABASE \`${database}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci`);
  await admin.end();
  const { db, knex } = createDatabase({ ...server, database });
  const migrations = await runMigrationsAndSeeds(knex);
  return { db, knex, migrations };
}

module.exports = { freshDatabase, server };
