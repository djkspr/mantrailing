// Schéma initial de la sauvegarde en ligne : comptes, jetons de connexion, équipes, invitations, éléments synchronisés.

exports.up = async function (knex) {
  await knex.schema.createTable('users', (t) => {
    t.bigIncrements('id').unsigned().primary();
    t.string('google_sub', 64).notNullable().unique();
    t.string('email', 255).nullable();
    t.string('name', 100).notNullable();
    t.datetime('created_at').notNullable().defaultTo(knex.fn.now());
  });

  // Jetons de connexion de l'appli : seule leur empreinte SHA-256 est gardée.
  await knex.schema.createTable('sessions', (t) => {
    t.specificType('token_hash', 'CHAR(64)').primary();
    t.bigInteger('user_id').unsigned().notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.datetime('created_at').notNullable().defaultTo(knex.fn.now());
    t.datetime('last_used_at').notNullable().defaultTo(knex.fn.now());
    t.index(['user_id']);
  });

  await knex.schema.createTable('teams', (t) => {
    t.specificType('id', 'CHAR(16)').primary();
    t.string('name', 60).notNullable();
    t.bigInteger('owner_id').unsigned().notNullable();
    t.datetime('created_at').notNullable().defaultTo(knex.fn.now());
  });

  await knex.schema.createTable('team_members', (t) => {
    t.specificType('team_id', 'CHAR(16)').notNullable().references('id').inTable('teams').onDelete('CASCADE');
    t.bigInteger('user_id').unsigned().notNullable().references('id').inTable('users').onDelete('CASCADE');
    t.datetime('joined_at').notNullable().defaultTo(knex.fn.now());
    t.primary(['team_id', 'user_id']);
    t.index(['user_id']);
  });

  await knex.schema.createTable('invites', (t) => {
    t.specificType('code', 'CHAR(8)').primary();
    t.specificType('team_id', 'CHAR(16)').notNullable().references('id').inTable('teams').onDelete('CASCADE');
    t.bigInteger('created_by').unsigned().notNullable();
    t.datetime('expires_at').notNullable();
  });

  // Éléments synchronisés (recherches, pistes posées, chiens), par espace : « u:<id utilisateur> » pour l'espace
  // perso, « t:<id équipe> » pour une équipe. seq : numéro croissant de la dernière écriture, curseur de réception.
  await knex.schema.createTable('items', (t) => {
    t.string('space', 24).notNullable();
    t.string('kind', 8).notNullable();
    t.string('item_id', 64).notNullable();
    t.bigInteger('updated_at').notNullable();
    t.boolean('deleted').notNullable().defaultTo(false);
    t.specificType('json', 'MEDIUMTEXT').nullable();
    t.bigInteger('seq').unsigned().notNullable();
    t.primary(['space', 'kind', 'item_id']);
    t.index(['space', 'seq'], 'space_seq');
  });

  // Compteurs (numéro d'écriture des éléments) ; leurs lignes sont posées par les seeds.
  await knex.schema.createTable('counters', (t) => {
    t.string('name', 16).primary();
    t.bigInteger('value').unsigned().notNullable();
  });
};

exports.down = async function (knex) {
  for (const table of ['counters', 'items', 'invites', 'team_members', 'teams', 'sessions', 'users']) {
    await knex.schema.dropTableIfExists(table);
  }
};
