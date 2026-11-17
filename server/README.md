# API Mantrailing (sauvegarde en ligne et équipes)

Petite API Node (Express) + MySQL/MariaDB, hébergée chez o2switch (Passenger). Elle stocke les recherches, pistes
posées et chiens de l'appli, par **espace** : l'espace perso de chacun, ou une **équipe** (famille, club…). Une équipe
ne voit que ce qui a été fait ou partagé en son nom.

- Connexion : l'appli obtient un jeton Google (identifiant OAuth « Web » du projet Google `mantrailing-216ed`),
  l'API le vérifie et rend son propre jeton (seule son empreinte SHA-256 est gardée en base).
- Synchronisation : la version la plus récente de chaque élément l'emporte ; chaque écriture reçoit un numéro
  croissant (`seq`), curseur de réception des téléphones.

## Base de données : même fonctionnement que l'API de la brasserie

- **Requêtes** : Kysely (`src/db/index.js`, tables décrites dans `src/db/types.d.ts`).
- **Structure** : migrations knex dans `migrations/` (`AAAAMMJJ_NN_description.js`, avec `up` et `down`), jamais
  modifiées une fois déployées : toute évolution est une nouvelle migration.
- **Données de référence** : seeds knex dans `seeds/`, rejoués à chaque déploiement, donc **sûrs à relancer**
  (insertion ou mise à jour par clé, jamais de remise à zéro).
- **Quand** : ni au démarrage ni à la main sur le serveur, mais par le déploiement, une fois l'API redémarrée sur la
  nouvelle version (`POST /deploy/run-migrations`, migrations puis seeds).

```powershell
npm run migrate:make -- nom_de_la_migration   # nouvelle migration (à renommer AAAAMMJJ_NN_…)
npm run migrate                               # migrations + seeds sur la base de server/.env.local
```

## Routes

| Méthode | Route | Rôle |
|---|---|---|
| POST | `/auth/google` | `{ idToken }` → `{ token, user }` |
| POST | `/auth/logout` | révoque le jeton |
| GET | `/me` | compte et équipes |
| DELETE | `/me` | supprime le compte et l'espace perso, quitte les équipes |
| POST | `/teams` | `{ name }` → nouvelle équipe |
| POST | `/teams/:id/invites` | code d'invitation (8 caractères, 7 jours) |
| POST | `/teams/join` | `{ code }` → rejoindre |
| DELETE | `/teams/:id/members/me` | quitter (le dernier membre supprime l'équipe et ses données) |
| POST | `/spaces/:space/items` | envoi (`:space` = `perso` ou id d'équipe) |
| GET | `/spaces/:space/items?after=N` | réception par pages |
| GET | `/spaces/:space/items/:kind/:id` | un élément complet |
| GET | `/version` | commit déployé |
| POST/GET | `/deploy/npm-install` | dépendances npm (secret de déploiement) |
| POST | `/deploy/run-migrations` | migrations + seeds (secret de déploiement) |

## En local

```powershell
cd server
npm install
npm run db:local     # MariaDB 11.4 dans Docker, port 3307 (conteneur mantrailing-mariadb)
npm test             # tests (chaque fichier repart d'une base vide construite par les migrations)
npm run test:all     # idem, puis étiquette git « api-tested » si tout passe : la version que deploy.ps1 déploie

# Test de bout en bout avec l'appli (client Kotlin + ce serveur, connexion Google simulée) :
node scripts/dev-server.js --reset          # http://127.0.0.1:3999
# puis, à la racine du dépôt :
$env:MANTRAILING_TEST_SERVER='http://127.0.0.1:3999'; .\gradlew.bat :app:testDebugUnitTest --tests "*CloudServerIntegrationTest"
```

Configuration locale : `server/.env.local` (non versionné, voir `.env.example`), pour `npm run migrate` et
`npm start`.

## Mise en place chez o2switch (une fois)

1. **Sous-domaine** — cPanel › Domaines : `mantrailing.lesbrasseursdupastel.fr` (certificat HTTPS valide :
   cPanel › SSL/TLS Status, « Run AutoSSL » au besoin).
2. **Base de données** — cPanel › Bases de données MySQL : base `gepi9588_mantrailing`, un utilisateur avec un mot de
   passe fort, ajouté à la base avec **tous les privilèges** (les tables sont créées par les migrations, au premier
   déploiement).
3. **Application Node** — cPanel › Setup Node.js App › Create Application : Node.js 22 ou plus, mode Production,
   Application root `mantrailing/api`, Application URL `mantrailing.lesbrasseursdupastel.fr`, startup file
   `src/index.js`. Aucune variable à saisir dans le panneau.
4. **Compte FTP dédié** — cPanel › Comptes FTP : un compte dont le répertoire est **limité à `mantrailing`** (le
   déploiement envoie dans son sous-dossier `api`, option `-RemotePath`) : le
   déploiement ne voit rien d'autre sur l'hébergement.
5. **Ce poste** — une fois :

   ```powershell
   .\server\set-deploy-env.ps1   # FTP (mot de passe masqué) + secret de déploiement généré, à recopier sur le serveur
   ```
6. **Secrets du serveur** — posés à la main, **hors du site et du dossier de l'application** (comme pour l'API de la
   brasserie, mais dans un dossier à part) : cPanel › Gestionnaire de fichiers, « Afficher les fichiers cachés »,
   `/home/gepi9588/.config/mantrailing/.env` à partir de `server/.env.example` (mot de passe de la base, et la ligne
   `DEPLOY_SECRET=…` donnée par `set-deploy-env.ps1`), Permissions **600**. Le compte FTP de déploiement n'y a pas
   accès, et `deploy.ps1` n'envoie jamais de `.env`.
7. **WinSCP** — le paquet « WinSCP .NET assembly » (pas l'installateur) extrait dans `tools/winscp-automation` à la
   racine de ce dépôt (non versionné ; déjà en place sur ce poste).
8. **Premier envoi** — `npm run test:all` dans `server/`, puis `.\server\deploy.ps1` depuis la racine du dépôt. L'API
   ne tournant pas encore, le script demande une fois de cliquer **Run NPM Install** dans Setup Node.js App, puis
   continue seul : redémarrage, vérification de `/version`, création des tables par les migrations.

## Déploiements suivants

```powershell
cd server; npm run test:all; cd ..   # tests, étiquette « api-tested »
.\server\deploy.ps1                  # la version testée
.\server\deploy.ps1 -Version actuel  # le dossier de travail tel quel
.\server\deploy.ps1 -NoServerUpdate  # envoi seul
```

Étapes : copie séparée du dépôt sur la version testée (`../mantrailing-deploy`), envoi FTP, dépendances npm
installées par l'API si `package-lock.json` a changé, redémarrage et vérification de `/version`, migrations et seeds.

Pour changer un secret : modifier `/home/gepi9588/.config/mantrailing/.env` (gestionnaire de fichiers), puis
« Restart » dans Setup Node.js App.
