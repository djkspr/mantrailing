// knexfile.js — migrations (structure de la base, migrations/) et seeds (données de référence, seeds/), comme pour
// l'API des Brasseurs du Pastel. Configuration : src/config.js (~/.config/mantrailing/.env, ou .env.local en local).
const { dbConfigFromEnv } = require('./src/config');

/** Configuration knex pour une connexion donnée (les tests visent leur propre base). */
function knexConfig(connection = dbConfigFromEnv()) {
  return {
    client: 'mysql2',
    connection: { ...connection, charset: 'utf8mb4', supportBigNumbers: true, bigNumberStrings: false },
    migrations: {
      tableName: 'knex_migrations',
      directory: require('node:path').join(__dirname, 'migrations'),
    },
    seeds: {
      directory: require('node:path').join(__dirname, 'seeds'),
    },
  };
}

module.exports = {
  development: knexConfig(),
  production: knexConfig(),
  knexConfig,
};
