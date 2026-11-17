# API Mantrailing (sauvegarde en ligne et équipes)

Petite API Node (Express) + MySQL/MariaDB, hébergée chez o2switch (Passenger). Elle stocke les recherches, pistes
posées et chiens de l'appli, par **espace** : l'espace perso de chacun, ou une **équipe** (famille, club…). Une équipe
ne voit que ce qui a été fait ou partagé en son nom.

- Connexion : l'appli obtient un jeton Google (identifiant OAuth « Web » du projet Google `mantrailing-216ed`),
  l'API le vérifie et rend son propre jeton (seule son empreinte SHA-256 est gardée en base).
- Synchronisation : la version la plus récente de chaque élément l'emporte ; chaque écriture reçoit un numéro
  croissant (`seq`), curseur de réception des téléphones.
- Le schéma est créé (ou complété) au démarrage : `src/db.js`.

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

## En local

```powershell
cd server
npm install
npm run db:local     # MariaDB 11.4 dans Docker, port 3307 (conteneur mantrailing-mariadb)
npm test             # tests de l'API contre cette base

# Test de bout en bout avec l'appli (client Kotlin + ce serveur, connexion Google simulée) :
node scripts/dev-server.js --reset          # http://127.0.0.1:3999
# puis, à la racine du dépôt :
$env:MANTRAILING_TEST_SERVER='http://127.0.0.1:3999'; .\gradlew.bat :app:testDebugUnitTest --tests "*CloudServerIntegrationTest"
```

## Mise en place chez o2switch (une fois)

1. **Sous-domaine** — cPanel › Domaines : `mantrailing.lesbrasseursdupastel.fr` (certificat HTTPS valide :
   cPanel › SSL/TLS Status, « Run AutoSSL » au besoin).
2. **Base de données** — cPanel › Bases de données MySQL : base `gepi9588_mantrailing`, un utilisateur (par exemple
   `gepi9588_mantrailing`) avec un mot de passe fort, ajouté à la base avec **tous les privilèges** (les tables sont
   créées par le serveur à son premier démarrage).
3. **Application Node** — cPanel › Setup Node.js App › Create Application :
   - Node.js version : la plus récente proposée (22 ou plus) ;
   - Application mode : Production ;
   - Application root : `mantrailing-api` ;
   - Application URL : `mantrailing.lesbrasseursdupastel.fr` ;
   - Application startup file : `src/index.js`.

   Aucune variable d'environnement à saisir dans le panneau : les secrets sont dans un fichier hors du dossier
   de l'application (étape 5).
4. **Compte FTP dédié** — cPanel › Comptes FTP : un compte (par exemple `mantrailing@lesbrasseursdupastel.fr`) dont
   le répertoire est **limité à `mantrailing-api`** : le déploiement ne voit rien d'autre sur l'hébergement. Sur ce
   poste, une fois :

   ```powershell
   .\server\set-deploy-env.ps1     # MANTRAILING_FTP_HOST / _USER / _PASS dans le profil Windows (mot de passe masqué)
   ```
5. **Secrets du serveur** — posés à la main, une fois, **hors du site et du dossier de l'application** (comme pour
   l'API de la brasserie, mais dans un dossier à part) : cPanel › Gestionnaire de fichiers, « Afficher les fichiers
   cachés », créer `/home/gepi9588/.config/mantrailing/.env` à partir de `server/.env.example` (avec le mot de passe
   de la base), puis Permissions : **600**. Le compte FTP de déploiement, limité à `mantrailing-api`, n'y a pas accès,
   et `deploy.ps1` n'envoie jamais de `.env`.
6. **WinSCP** — le paquet « WinSCP .NET assembly » (pas l'installateur) doit être extrait dans
   `tools/winscp-automation` à la racine de ce dépôt (non versionné ; déjà en place sur ce poste).
7. **Premier envoi** — depuis la racine du dépôt : `.\server\deploy.ps1`, puis dans Setup Node.js App :
   **Run NPM Install**, puis **Restart**. `https://mantrailing.lesbrasseursdupastel.fr/version` doit répondre.

L'adresse de l'API est déjà dans `gradle.properties` (`mantrailing.apiUrl`) : la prochaine version de l'appli
affichera la sauvegarde en ligne.

## Envois suivants

```powershell
.\server\deploy.ps1          # tests, envoi FTP, redémarrage, vérification de /version
```

Si `package.json` a changé, le script le signale : « Run NPM Install » puis « Restart » dans Setup Node.js App.
Pour changer un secret : modifier `/home/gepi9588/.config/mantrailing/.env` (gestionnaire de fichiers), puis
« Restart » dans Setup Node.js App.
