# Mantrailing

Application Android d'entraînement au mantrailing : enregistre la piste du traceur, récupère la trace du chien
depuis son collier GPS Tractive et superpose les deux pour analyser le travail du chien.

## Étape 1 — prototype API Tractive

Tractive n'a pas d'API publique : on utilise l'API de leur appli (non officielle, peut changer).

```sh
cp .env.example .env      # puis renseigner TRACTIVE_EMAIL / TRACTIVE_PASSWORD
node tools/tractive-probe.mjs                                   # aujourd'hui
node tools/tractive-probe.mjs --from 2026-11-16T09:00 --to 2026-11-16T12:00
```

Résultats dans `data/` (non versionné) : `positions-raw.json`, `trace.gpx`, `trace.html` (carte à ouvrir dans un navigateur).

**Important :** hors mode LIVE, le collier n'envoie une position que toutes les quelques minutes.
Pour une piste exploitable, activer le LIVE pendant toute la recherche.
