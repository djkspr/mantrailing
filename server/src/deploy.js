'use strict';

// Mise à jour du serveur pendant le déploiement (server/deploy.ps1) : l'hébergement n'offre que le FTP, sans commande
// à distance — c'est donc l'API elle-même qui installe ses dépendances npm quand package-lock.json a changé.
// Protégé par un secret (DEPLOY_SECRET côté serveur, MANTRAILING_DEPLOY_SECRET sur le poste qui déploie), envoyé dans
// l'en-tête x-deploy-secret. Sans secret configuré, ces routes n'existent pas (404).
//
// POST /deploy/npm-install { force? } : lance « npm install --omit=dev » si package-lock.json diffère de la dernière
//   installation réussie (ou force) ; répond tout de suite.
// GET  /deploy/npm-install : état de l'installation (fichier tmp/npm-install.json).
// POST /deploy/run-migrations : migrations (structure, migrations/) puis seeds (données, seeds/) avec knex, comme
//   pour l'API de la brasserie ; lancé par deploy.ps1 une fois l'API redémarrée sur la nouvelle version.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');
const { spawn } = require('node:child_process');
const express = require('express');

const TIMEOUT_MS = 10 * 60_000;

function secretMatches(given, expected) {
  if (typeof given !== 'string' || !expected) return false;
  const a = crypto.createHash('sha256').update(given).digest();
  const b = crypto.createHash('sha256').update(expected).digest();
  return crypto.timingSafeEqual(a, b);
}

function readJson(file) {
  try {
    return JSON.parse(fs.readFileSync(file, 'utf8'));
  } catch {
    return null;
  }
}

/** Commande npm : celle de l'environnement Node de l'appli (même dossier que node), sinon celle du PATH. */
function defaultNpmCommand() {
  const local = path.join(path.dirname(process.execPath), process.platform === 'win32' ? 'npm.cmd' : 'npm');
  return { cmd: fs.existsSync(local) ? local : 'npm', args: ['install', '--omit=dev', '--no-audit', '--no-fund'] };
}

/**
 * @param {object} options
 * @param {string} options.appRoot dossier de l'application (package-lock.json, tmp/)
 * @param {string|undefined} options.secret secret attendu ; absent : routes désactivées
 * @param {() => {cmd: string, args: string[]}} [options.npmCommand] commande lancée (tests)
 * @param {() => Promise<object>} [options.runMigrations] migrations puis seeds (migrate.js)
 */
function createDeployRouter({ appRoot, secret, npmCommand = defaultNpmCommand, runMigrations }) {
  const router = express.Router();
  const tmp = path.join(appRoot, 'tmp');
  const statusFile = path.join(tmp, 'npm-install.json');
  const installedHash = path.join(tmp, 'installed-lock.sha256');

  const writeStatus = (status) => {
    fs.mkdirSync(tmp, { recursive: true });
    fs.writeFileSync(statusFile, JSON.stringify(status, null, 2));
  };
  const lockHash = () =>
    crypto.createHash('sha256').update(fs.readFileSync(path.join(appRoot, 'package-lock.json'))).digest('hex');

  router.use((req, res, next) => {
    if (!secret) return res.status(404).json({ error: 'Introuvable.' });
    if (!secretMatches(req.get('x-deploy-secret'), secret)) return res.status(403).json({ error: 'Accès refusé.' });
    next();
  });

  router.post('/npm-install', (req, res) => {
    const current = readJson(statusFile);
    if (current?.state === 'running' && Date.now() - new Date(current.startedAt).getTime() < TIMEOUT_MS) {
      return res.status(202).json({ status: 'running', startedAt: current.startedAt });
    }
    const hash = lockHash();
    const installed = fs.existsSync(installedHash) ? fs.readFileSync(installedHash, 'utf8').trim() : null;
    if (!req.body?.force && installed === hash) return res.json({ status: 'up-to-date' });

    const { cmd, args } = npmCommand();
    const startedAt = new Date().toISOString();
    writeStatus({ state: 'running', startedAt, lockHash: hash, command: [path.basename(cmd), ...args].join(' ') });
    // Shell seulement pour npm sous Windows (npm.cmd) : il casserait les guillemets d'une commande node -e (tests).
    const child = spawn(cmd, args, {
      cwd: appRoot,
      env: process.env,
      shell: process.platform === 'win32' && cmd !== process.execPath,
    });
    let output = '';
    const keep = (d) => {
      output = (output + d.toString()).slice(-4000); // fin du journal seulement
    };
    child.stdout.on('data', keep);
    child.stderr.on('data', keep);
    const timer = setTimeout(() => child.kill(), TIMEOUT_MS);
    let finished = false;
    const finish = (code, error) => {
      if (finished) return;
      finished = true;
      clearTimeout(timer);
      const ok = code === 0;
      if (ok) fs.writeFileSync(installedHash, hash);
      writeStatus({
        state: ok ? 'done' : 'failed',
        startedAt,
        finishedAt: new Date().toISOString(),
        exitCode: code,
        error: error ?? null,
        output,
        lockHash: hash,
      });
      console.log(`Déploiement : npm install ${ok ? 'terminé' : `échoué (code ${code})`}`);
    };
    child.on('error', (err) => finish(-1, err.message));
    child.on('close', (code) => finish(code));
    res.status(202).json({ status: 'started', startedAt });
  });

  router.get('/npm-install', (_req, res) => {
    res.json(readJson(statusFile) ?? { state: 'none' });
  });

  router.post('/run-migrations', async (_req, res) => {
    if (!runMigrations) return res.status(404).json({ error: 'Introuvable.' });
    try {
      const result = await runMigrations();
      console.log(`Déploiement : migrations ${result.migrations.files.join(', ') || 'aucune'}`);
      res.json({
        success: true,
        migrations: result.migrations,
        seeds: result.seeds.map((file) => path.basename(file)),
      });
    } catch (err) {
      console.error('Déploiement : échec des migrations', err);
      res.status(500).json({ error: err.message || String(err) });
    }
  });

  return router;
}

module.exports = { createDeployRouter, secretMatches };
