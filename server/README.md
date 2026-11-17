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

1. **Sous-domaine** — cPanel › Domaines : créer par exemple `mantrailing-api.lesbrasseursdupastel.fr`
   (racine : `/home/<compte>/mantrailing-api`). Vérifier que le certificat HTTPS est émis (cPanel › SSL/TLS Status,
   « Run AutoSSL » au besoin).
2. **Base de données** — cPanel › Bases de données MySQL :
   - créer la base `<compte>_mantrailing` ;
   - créer l'utilisateur `<compte>_mantrailing` avec un mot de passe fort ;
   - l'ajouter à la base avec **tous les privilèges**.
3. **Application Node** — cPanel › Setup Node.js App › Create Application :
   - Node.js version : la plus récente proposée (22 ou plus) ;
   - Application mode : Production ;
   - Application root : `mantrailing-api` ;
   - Application URL : le sous-domaine ;
   - Application startup file : `src/index.js`.
   Puis, dans « Environment variables » :

   | Variable | Valeur |
   |---|---|
   | `DB_HOST` | `localhost` |
   | `DB_NAME` | `<compte>_mantrailing` |
   | `DB_USER` | `<compte>_mantrailing` |
   | `DB_PASSWORD` | le mot de passe de l'étape 2 |
   | `GOOGLE_CLIENT_ID` | `1060303843038-k41u1rfk863q4kb8cvdq300eml85s73p.apps.googleusercontent.com` |

   Enregistrer (« Save »).
4. **Premier envoi** — depuis la racine du dépôt :
   `.\server\deploy.ps1 -ApiUrl https://mantrailing-api.lesbrasseursdupastel.fr`
   (identifiants FTP dans `FTP_USER` / `FTP_PASS`, comme pour l'autre projet). Puis dans Setup Node.js App :
   **Run NPM Install**, puis **Restart**. `https://<sous-domaine>/version` doit répondre.
5. **Brancher l'appli** — mettre l'adresse dans `gradle.properties` (`mantrailing.apiUrl=https://…`), puis publier
   l'appli. Tant que cette adresse est vide, la sauvegarde en ligne n'apparaît pas dans l'appli.

## Envois suivants

```powershell
.\server\deploy.ps1          # tests, envoi FTP, redémarrage, vérification de /version
```

Si `package.json` a changé, le script le signale : « Run NPM Install » puis « Restart » dans Setup Node.js App.
