// Migrations puis seeds, comme pour l'API des Brasseurs du Pastel : lancés par le déploiement
// (POST /deploy/run-migrations, cf. src/deploy.js) ou à la main en local (npm run migrate).

/** @param {import('knex').Knex} knex */
async function runMigrationsAndSeeds(knex) {
  const [batch, files] = await knex.migrate.latest();
  const [seeds] = await knex.seed.run();
  return { migrations: { batch, files }, seeds };
}

module.exports = { runMigrationsAndSeeds };

if (require.main === module) {
  const knexfile = require('./knexfile');
  const knex = require('knex')(knexfile[process.env.NODE_ENV || 'development']);
  runMigrationsAndSeeds(knex)
    .then((result) => {
      console.log(`Migrations exécutées : ${result.migrations.files.length ? result.migrations.files.join(', ') : 'aucune'}`);
      console.log(`Seeds : ${result.seeds.map((s) => require('node:path').basename(s)).join(', ')}`);
    })
    .catch((err) => {
      console.error('Échec des migrations :', err.message);
      process.exitCode = 1;
    })
    .finally(() => knex.destroy());
}
