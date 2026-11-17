'use strict';

// Tous les tests de l'API (base MariaDB locale démarrée au besoin), puis, si tout passe, l'étiquette git
// « api-tested » sur le commit testé : c'est la version que server/deploy.ps1 déploie par défaut (-Version tested),
// même si le dossier de travail a évolué depuis. Pas d'étiquette si server/ a des modifications non commitées
// (ce qui a été testé ne serait alors aucun commit).
//
// Usage : npm run test:all   (dans server/)
const { execSync, spawnSync } = require('node:child_process');
const path = require('node:path');

const serverDir = path.join(__dirname, '..');
const git = (args) => execSync(`git ${args}`, { cwd: serverDir }).toString().trim();
const color = (code, text) => `\x1b[${code}m${text}\x1b[0m`;

const dirty = git('status --porcelain -- .');
if (dirty) console.log(color(33, '⚠ Modifications non commitées dans server/ : les tests tournent, mais la version ne sera pas marquée « api-tested ».'));

console.log(color(36, '▶ Base de données locale'));
require('./local-db');
// Le conteneur peut mettre quelques secondes à accepter les connexions juste après son démarrage.
for (let i = 0; i < 30; i++) {
  const ping = spawnSync('docker', ['exec', 'mantrailing-mariadb', 'mariadb', '-uroot', '-proot', '-e', 'select 1']);
  if (ping.status === 0) break;
  execSync(process.platform === 'win32' ? 'powershell -NoProfile -Command Start-Sleep 2' : 'sleep 2');
}

console.log(color(36, '▶ Tests de l’API'));
const run = spawnSync('npm', ['test'], { cwd: serverDir, stdio: 'inherit', shell: true });
if (run.status !== 0) {
  console.log(color(31, '✗ Tests en échec : version non marquée.'));
  process.exit(run.status || 1);
}
if (dirty) {
  console.log(color(33, 'Tests passés, mais modifications non commitées : version non marquée « api-tested ».'));
  process.exit(0);
}
const head = git('rev-parse HEAD');
git(`tag -f api-tested ${head}`);
console.log(color(32, `✓ Tests passés : commit ${head.slice(0, 7)} marqué « api-tested » (version déployée par deploy.ps1).`));
