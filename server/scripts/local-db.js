'use strict';

// Base MariaDB locale pour les tests (Docker), sur le port 3307 pour ne pas gêner d'autres projets.
const { execSync } = require('node:child_process');

const NAME = 'mantrailing-mariadb';
const run = (cmd) => execSync(cmd, { stdio: ['ignore', 'pipe', 'pipe'] }).toString().trim();

const existing = run(`docker ps -a --filter name=^/${NAME}$ --format "{{.Status}}"`);
if (!existing) {
  run(
    `docker run -d --name ${NAME} -p 3307:3306 -e MARIADB_ROOT_PASSWORD=root ` +
      `-e MARIADB_DATABASE=mantrailing_test mariadb:11.4`
  );
  console.log(`Conteneur ${NAME} créé (MariaDB 11.4, port 3307).`);
} else if (!existing.startsWith('Up')) {
  run(`docker start ${NAME}`);
  console.log(`Conteneur ${NAME} redémarré.`);
} else {
  console.log(`Conteneur ${NAME} déjà démarré.`);
}
