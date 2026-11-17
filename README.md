# Mantrailing

Application Android d'entraînement au mantrailing : enregistre la piste du traceur, récupère la trace du chien
(collier Tractive ou Weenect, GPS du téléphone ou fichier GPX) et superpose les deux pour analyser son travail.

## Application Android (`app/`)

Kotlin + Jetpack Compose, carte MapLibre (fonds IGN plan / photos aériennes, OSM via CARTO).

Fonctionnalités (détail pour l'utilisateur : écran « Aide » de l'appli, `ui/HelpScreen.kt`) :
- **pose de la piste** au GPS du téléphone (service au premier plan, écran éteint), tracé et distance en direct,
  signalements du traceur (danger, point d'attention…) ;
- **recherche** sur une piste choisie, masquée pendant le travail ; trace du chien par collier **Tractive** (mode
  LIVE) ou **Weenect** (super live) — API non officielles, seul un jeton est conservé — ou GPS du téléphone ;
  notes rapides pendant la recherche ;
- **bilan** : piste et trace superposées, écarts, piste couverte, arrivée, notes placées ; **météo** Open-Meteo ;
- **carnet d'entraînement** (traceur, objet odorant, terrain, difficultés, note) et **progrès** de chaque chien ;
- **mode formateur** : piste visible avec un couloir, alerte (vibration) quand l'équipe en sort ;
- **cartes hors ligne** (zones IGN téléchargées avec l'OfflineManager de MapLibre) ;
- **import GPX** (Garmin…), **export** rapport PDF et fichier GPX ;
- plusieurs chiens, **sauvegarde en ligne** facultative et **équipes** (API `server/`, voir plus bas) ;
- tour guidé au premier lancement, aide intégrée, séance de démonstration, mises à jour proposées à l'ouverture.

### Construire et tester

Ouvrir le dossier dans Android Studio, ou en ligne de commande (JDK d'Android Studio) :

```sh
./gradlew testDebugUnitTest          # tests unitaires (API Tractive simulée, statistiques, période)
./gradlew connectedDebugAndroidTest  # tests d'interface : sur l'ÉMULATEUR seulement (ils vident l'appli d'un téléphone)
./gradlew installDebug               # installer sur le téléphone
```

### Publier sur le Play Store (tests internes)

Signature : clé d'envoi `%USERPROFILE%\.android-keys\mantrailing-upload.jks`, ses mots de passe dans
`mantrailing-keystore.properties` à côté. **À sauvegarder** (gestionnaire de mots de passe, clé USB) : sans elle,
il faut demander à Google de la réinitialiser. Pour construire, copier ce `.properties` à la racine du projet
sous le nom `keystore.properties` (non versionné).

```powershell
.\release.ps1               # tests (émulateur lancé), AAB signé, envoi sur la piste de test interne
.\release.ps1 -Track alpha  # autre piste : alpha (test fermé), beta, production
.\release.ps1 -NoUpload     # AAB seulement, dans release\
```

Le versionCode est le nombre de commits : il faut donc commiter entre deux envois. Les notes de version sont
les titres des commits depuis le dernier envoi ; un tag `play-v<N>` marque chaque version envoyée.

**Envoi automatique** (`tools/play-publish.mjs`, API Google Play Android Publisher) : il faut une clé de compte
de service, dont le chemin est donné par `playApiKey=` dans `keystore.properties` (par défaut
`%USERPROFILE%\.android-keys\mantrailing-play-api.json`). La clé du back-office des Brasseurs est réutilisée ;
pour en créer une nouvelle :
1. Google Cloud Console : créer un projet, activer « Google Play Android Developer API » ;
2. IAM > Comptes de service : créer un compte, puis Clés > Ajouter une clé > JSON ;
3. Play Console > Utilisateurs et autorisations : inviter l'email du compte de service, avec les droits
   « Publier en tests » (ou « Gérer les versions ») pour l'appli Mantrailing.

Tant que l'appli n'a jamais été publiée sur Google Play, l'API ne crée que des releases « brouillon » : le script
le détecte, il reste alors à cliquer « Examiner la release » dans la Play Console.

Politique de confidentialité : https://djkspr.github.io/mantrailing-site/politique-confidentialite.html
(source : dépôt public `mantrailing-site`, dans `..\mantrailing-site`). La mettre à jour à chaque nouvelle donnée
traitée (ex. GPS du téléphone pour la piste du traceur).

## API Tractive

Tractive n'a pas d'API publique : on utilise celle de leur appli (non officielle, référence : la bibliothèque
Python `aiotractive`), qui peut changer sans prévenir.

**Important :** hors mode LIVE, le collier n'envoie une position que toutes les quelques minutes.

### Script de test (`tools/tractive-probe.mjs`)

```sh
cp .env.example .env      # puis renseigner TRACTIVE_EMAIL / TRACTIVE_PASSWORD
node tools/tractive-probe.mjs                                   # aujourd'hui
node tools/tractive-probe.mjs --from 2026-11-16T09:00 --to 2026-11-16T12:00
```

Résultats dans `data/` (non versionné) : `positions-raw.json`, `trace.gpx`, `trace.html` (carte à ouvrir dans un navigateur).
