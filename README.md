# Mantrailing

Application Android d'entraînement au mantrailing : enregistre la piste du traceur, récupère la trace du chien
depuis son collier GPS Tractive et superpose les deux pour analyser le travail du chien.

## Application Android (`app/`)

Kotlin + Jetpack Compose, carte MapLibre (fonds IGN plan / photos aériennes, OSM via CARTO).

État actuel (v0.1) :
- connexion au compte Tractive (seul un jeton est conservé, jamais le mot de passe) ;
- choix du chien ;
- choix d'une période (date + heures) et affichage de la trace du collier, avec bilan :
  distance, durée, intervalle entre points, précision, part de positions GPS ;
- bouton « Activer LIVE » (une position toutes les 2-3 s, indispensable pour le mantrailing).

À venir : enregistrement de la piste du traceur, superposition et écarts chien / piste, carnet d'entraînement.

### Construire et tester

Ouvrir le dossier dans Android Studio, ou en ligne de commande (JDK d'Android Studio) :

```sh
./gradlew testDebugUnitTest          # tests unitaires (API Tractive simulée, statistiques, période)
./gradlew connectedDebugAndroidTest  # tests d'interface sur émulateur ou téléphone branché
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
