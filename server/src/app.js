'use strict';

const crypto = require('node:crypto');
const express = require('express');
const rateLimit = require('express-rate-limit');
const { sql } = require('kysely');
const { z } = require('zod');
const { createDeployRouter } = require('./deploy');

/** Limites : un élément (une recherche avec sa trace) dépasse rarement quelques centaines de Ko. */
const MAX_ITEM_JSON = 1_000_000;
const MAX_PULL_BYTES = 5_000_000;
const PULL_PAGE = 200;
const INVITE_DAYS = 7;
const INVITE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';
const MAX_TEAMS_PER_USER = 50;

const sha256 = (value) => crypto.createHash('sha256').update(value).digest('hex');
const randomId = (length, alphabet) =>
  Array.from(crypto.randomBytes(length), (b) => alphabet[b % alphabet.length]).join('');

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

const itemSchema = z.object({
  kind: z.enum(['SESSION', 'TRAIL', 'DOG']),
  id: z.string().regex(/^[A-Za-z0-9_-]{1,64}$/),
  updatedAt: z.number().int().nonnegative(),
  deleted: z.boolean(),
  json: z.string().max(MAX_ITEM_JSON).nullable(),
});

/**
 * API de la sauvegarde en ligne de Mantrailing.
 * - Connexion : le téléphone envoie le jeton Google de l'utilisateur, l'API rend son propre jeton.
 * - Espaces : « perso » (soi seul) ou une équipe dont on est membre. Une équipe ne voit que ses
 *   éléments ; un espace auquel on n'a pas accès répond 404, sans dire s'il existe.
 *
 * @param {object} deps
 * @param {import('kysely').Kysely<import('./db/types').DB>} deps.db requêtes (Kysely, comme l'API de la brasserie)
 * @param {(idToken: string) => Promise<{sub: string, email?: string, name?: string}>} deps.verifyGoogleToken
 * @param {object} [deps.version] commit déployé, renvoyé par GET /version
 */
function createApp({ db, verifyGoogleToken, version = {}, rateLimits = true, deploy = null }) {
  const app = express();
  app.disable('x-powered-by');
  app.set('trust proxy', 1); // Passenger / proxy o2switch : vraie adresse du client pour les limites.
  app.use(express.json({ limit: '12mb' }));
  if (rateLimits) {
    app.use(rateLimit({ windowMs: 60_000, limit: 300, standardHeaders: 'draft-8', legacyHeaders: false }));
  }
  const authLimiter = rateLimits
    ? rateLimit({ windowMs: 60_000, limit: 20, standardHeaders: 'draft-8', legacyHeaders: false })
    : (_req, _res, next) => next();

  const wrap = (handler) => (req, res, next) => Promise.resolve(handler(req, res)).catch(next);
  const startedAt = new Date().toISOString();

  app.get('/', (_req, res) => res.json({ name: 'mantrailing-api' }));
  app.get('/version', (_req, res) => res.json({ ...version, startedAt }));

  // Installation des dépendances pendant un déploiement (secret DEPLOY_SECRET, cf. deploy.js).
  if (deploy) app.use('/deploy', createDeployRouter(deploy));

  // ---------- Connexion ----------

  app.post(
    '/auth/google',
    authLimiter,
    wrap(async (req, res) => {
      const { idToken } = z.object({ idToken: z.string().min(10) }).parse(req.body);
      let profile;
      try {
        profile = await verifyGoogleToken(idToken);
      } catch {
        throw new HttpError(401, 'Compte Google refusé.');
      }
      const name = (profile.name || profile.email?.split('@')[0] || 'Moi').slice(0, 100);
      await db
        .insertInto('users')
        .values({ google_sub: profile.sub, email: profile.email ?? null, name })
        .onDuplicateKeyUpdate({ email: sql`VALUES(email)`, name: sql`VALUES(name)` })
        .execute();
      const user = await db
        .selectFrom('users')
        .select(['id', 'email', 'name'])
        .where('google_sub', '=', profile.sub)
        .executeTakeFirstOrThrow();
      const token = crypto.randomBytes(32).toString('base64url');
      await db.insertInto('sessions').values({ token_hash: sha256(token), user_id: user.id }).execute();
      res.json({ token, user: publicUser(user) });
    })
  );

  async function authenticate(req) {
    const token = /^Bearer (.+)$/.exec(req.get('authorization') || '')?.[1];
    if (!token) throw new HttpError(401, 'Connexion requise.');
    const row = await db
      .selectFrom('sessions as s')
      .innerJoin('users as u', 'u.id', 's.user_id')
      .select(['u.id', 'u.email', 'u.name', 's.last_used_at'])
      .where('s.token_hash', '=', sha256(token))
      .executeTakeFirst();
    if (!row) throw new HttpError(401, 'Connexion expirée, reconnecte-toi.');
    req.user = row;
    req.tokenHash = sha256(token);
    // Date d'utilisation mise à jour au plus une fois par jour (une écriture de moins par requête).
    if (Date.now() - new Date(row.last_used_at).getTime() > 86_400_000) {
      await db.updateTable('sessions').set({ last_used_at: sql`NOW()` }).where('token_hash', '=', req.tokenHash).execute();
    }
  }
  const requireUser = (req, _res, next) => authenticate(req).then(() => next(), next);
  const authed = (handler) => [requireUser, wrap(handler)];

  app.post(
    '/auth/logout',
    ...authed(async (req, res) => {
      await db.deleteFrom('sessions').where('token_hash', '=', req.tokenHash).execute();
      res.status(204).end();
    })
  );

  // ---------- Compte et équipes ----------

  app.get(
    '/me',
    ...authed(async (req, res) => {
      res.json({ user: publicUser(req.user), teams: await teamsOf(req.user.id) });
    })
  );

  /** Supprime le compte : espace perso effacé, équipes quittées (celles des autres gardent leurs données). */
  app.delete(
    '/me',
    ...authed(async (req, res) => {
      for (const team of await teamsOf(req.user.id)) await leaveTeam(req.user.id, team.id);
      await db.deleteFrom('items').where('space', '=', `u:${req.user.id}`).execute();
      await db.deleteFrom('users').where('id', '=', req.user.id).execute();
      res.status(204).end();
    })
  );

  app.post(
    '/teams',
    ...authed(async (req, res) => {
      const { name } = z.object({ name: z.string().trim().min(1).max(60) }).parse(req.body);
      const { count } = await db
        .selectFrom('team_members')
        .select((eb) => eb.fn.countAll().as('count'))
        .where('user_id', '=', req.user.id)
        .executeTakeFirstOrThrow();
      if (Number(count) >= MAX_TEAMS_PER_USER) throw new HttpError(409, 'Trop d’équipes.');
      const id = randomId(16, 'abcdefghijklmnopqrstuvwxyz0123456789');
      await db.insertInto('teams').values({ id, name, owner_id: req.user.id }).execute();
      await db.insertInto('team_members').values({ team_id: id, user_id: req.user.id }).execute();
      res.status(201).json(await loadTeam(id));
    })
  );

  app.post(
    '/teams/:teamId/invites',
    ...authed(async (req, res) => {
      await requireMember(req.user.id, req.params.teamId);
      const code = randomId(8, INVITE_ALPHABET);
      const expiresAt = new Date(Date.now() + INVITE_DAYS * 86_400_000);
      await db
        .insertInto('invites')
        .values({ code, team_id: req.params.teamId, created_by: req.user.id, expires_at: expiresAt })
        .execute();
      res.status(201).json({ code, expiresAt: expiresAt.toISOString() });
    })
  );

  /** Rejoindre une équipe : on reçoit ce qui s'y fait ; rien de ce que l'on a n'y est envoyé. */
  app.post(
    '/teams/join',
    authLimiter,
    ...authed(async (req, res) => {
      const { code } = z.object({ code: z.string() }).parse(req.body);
      const normalized = code.toUpperCase().replace(/[^A-Z0-9]/g, '');
      const invite = await db
        .selectFrom('invites')
        .select(['team_id', 'expires_at'])
        .where('code', '=', normalized)
        .executeTakeFirst();
      if (!invite) throw new HttpError(404, 'Code d’invitation inconnu.');
      if (new Date(invite.expires_at).getTime() < Date.now()) throw new HttpError(410, 'Ce code d’invitation a expiré.');
      await db.insertInto('team_members').ignore().values({ team_id: invite.team_id, user_id: req.user.id }).execute();
      res.json(await loadTeam(invite.team_id));
    })
  );

  app.delete(
    '/teams/:teamId/members/me',
    ...authed(async (req, res) => {
      await requireMember(req.user.id, req.params.teamId);
      await leaveTeam(req.user.id, req.params.teamId);
      res.status(204).end();
    })
  );

  // ---------- Éléments d'un espace ----------

  /** « perso » ou une équipe dont l'utilisateur est membre ; sinon 404. */
  async function spaceOf(req) {
    const space = req.params.space;
    if (space === 'perso') return `u:${req.user.id}`;
    await requireMember(req.user.id, space);
    return `t:${space}`;
  }

  /** Envoi : la version la plus récente de chaque élément l'emporte (une plus ancienne est ignorée). */
  app.post(
    '/spaces/:space/items',
    ...authed(async (req, res) => {
      const space = await spaceOf(req);
      const { items } = z.object({ items: z.array(itemSchema).max(1000) }).parse(req.body);
      // Une seule connexion pour la transaction : LAST_INSERT_ID() rend le numéro réservé par cette connexion.
      await db.transaction().execute(async (trx) => {
        for (const item of items) {
          await sql`UPDATE counters SET value = LAST_INSERT_ID(value + 1) WHERE name = 'items'`.execute(trx);
          const { rows } = await sql`SELECT LAST_INSERT_ID() AS seq`.execute(trx);
          const seq = Number(rows[0].seq);
          const newer = sql`VALUES(updated_at) >= updated_at`;
          await trx
            .insertInto('items')
            .values({
              space,
              kind: item.kind,
              item_id: item.id,
              updated_at: item.updatedAt,
              deleted: item.deleted,
              json: item.deleted ? null : item.json,
              seq,
            })
            // updated_at en dernier : les affectations précédentes comparent encore l'ancienne valeur.
            .onDuplicateKeyUpdate({
              json: sql`IF(${newer}, VALUES(json), json)`,
              deleted: sql`IF(${newer}, VALUES(deleted), deleted)`,
              seq: sql`IF(${newer}, VALUES(seq), seq)`,
              updated_at: sql`GREATEST(updated_at, VALUES(updated_at))`,
            })
            .execute();
        }
      });
      res.json({ received: items.length });
    })
  );

  /** Réception : éléments écrits après le curseur [after], par pages (nombre et taille limités). */
  app.get(
    '/spaces/:space/items',
    ...authed(async (req, res) => {
      const space = await spaceOf(req);
      const after = z.coerce.number().int().nonnegative().catch(0).parse(req.query.after ?? 0);
      const rows = await db
        .selectFrom('items')
        .select(['kind', 'item_id', 'updated_at', 'deleted', 'json', 'seq'])
        .where('space', '=', space)
        .where('seq', '>', after)
        .orderBy('seq')
        .limit(PULL_PAGE)
        .execute();
      const items = [];
      let bytes = 0;
      for (const row of rows) {
        bytes += row.json?.length ?? 0;
        if (items.length > 0 && bytes > MAX_PULL_BYTES) break;
        items.push(row);
      }
      res.json({
        items: items.map(toItem),
        cursor: items.length ? Number(items[items.length - 1].seq) : after,
        more: items.length < rows.length || rows.length === PULL_PAGE,
      });
    })
  );

  /** Un élément complet, pour afficher ce qui n'est plus sur le téléphone. */
  app.get(
    '/spaces/:space/items/:kind/:id',
    ...authed(async (req, res) => {
      const space = await spaceOf(req);
      const row = await db
        .selectFrom('items')
        .select(['kind', 'item_id', 'updated_at', 'deleted', 'json', 'seq'])
        .where('space', '=', space)
        .where('kind', '=', req.params.kind)
        .where('item_id', '=', req.params.id)
        .executeTakeFirst();
      if (!row || row.deleted) throw new HttpError(404, 'Introuvable.');
      res.json(toItem(row));
    })
  );

  // ---------- Erreurs ----------

  app.use((_req, _res, next) => next(new HttpError(404, 'Introuvable.')));
  // eslint-disable-next-line no-unused-vars
  app.use((err, _req, res, _next) => {
    if (err instanceof z.ZodError) return res.status(400).json({ error: 'Requête invalide.' });
    if (err.type === 'entity.too.large') return res.status(413).json({ error: 'Envoi trop volumineux.' });
    if (err instanceof HttpError) return res.status(err.status).json({ error: err.message });
    console.error(err);
    res.status(500).json({ error: 'Erreur du serveur.' });
  });

  // ---------- Outils ----------

  async function requireMember(userId, teamId) {
    const row = await db
      .selectFrom('team_members')
      .select('team_id')
      .where('team_id', '=', String(teamId))
      .where('user_id', '=', userId)
      .executeTakeFirst();
    if (!row) throw new HttpError(404, 'Introuvable.');
  }

  async function loadTeam(teamId) {
    const team = await db.selectFrom('teams').select(['id', 'name', 'owner_id']).where('id', '=', teamId).executeTakeFirst();
    if (!team) return null;
    const members = await db
      .selectFrom('team_members as m')
      .innerJoin('users as u', 'u.id', 'm.user_id')
      .select(['u.id', 'u.name'])
      .where('m.team_id', '=', teamId)
      .orderBy('m.joined_at')
      .execute();
    return {
      id: team.id,
      name: team.name,
      ownerId: String(team.owner_id),
      members: Object.fromEntries(members.map((m) => [String(m.id), m.name])),
    };
  }

  async function teamsOf(userId) {
    const rows = await db
      .selectFrom('team_members')
      .select('team_id')
      .where('user_id', '=', userId)
      .orderBy('joined_at')
      .execute();
    return Promise.all(rows.map((r) => loadTeam(r.team_id)));
  }

  /** Dernier membre parti : l'équipe et tout ce qui y a été partagé sont supprimés. */
  async function leaveTeam(userId, teamId) {
    await db.deleteFrom('team_members').where('team_id', '=', teamId).where('user_id', '=', userId).execute();
    const { count } = await db
      .selectFrom('team_members')
      .select((eb) => eb.fn.countAll().as('count'))
      .where('team_id', '=', teamId)
      .executeTakeFirstOrThrow();
    if (Number(count) === 0) {
      await db.deleteFrom('items').where('space', '=', `t:${teamId}`).execute();
      await db.deleteFrom('teams').where('id', '=', teamId).execute();
    }
  }

  return app;
}

function publicUser(user) {
  return { id: String(user.id), email: user.email, name: user.name };
}

function toItem(row) {
  return {
    kind: row.kind,
    id: row.item_id,
    updatedAt: Number(row.updated_at),
    deleted: Boolean(row.deleted),
    json: row.deleted ? null : row.json,
  };
}

module.exports = { createApp };
