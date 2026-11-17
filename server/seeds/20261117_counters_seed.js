// Compteur du numéro d'écriture des éléments synchronisés. Sûr à relancer (les seeds tournent à chaque déploiement) :
// la ligne est créée si elle manque, jamais remise à zéro.
exports.seed = async function (knex) {
  await knex('counters').insert({ name: 'items', value: 0 }).onConflict('name').ignore();
};
