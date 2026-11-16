# Fiche Play Store — français

## Nom de l'application (30 caractères max)

Mantrailing

## Brève description (80 caractères max)

La trace GPS de votre chien comparée à la piste du traceur, sur carte IGN.

## Description complète (4000 caractères max)

Mantrailing aide les maîtres-chiens à analyser les séances de pistage de personnes (mantrailing) : la trace du chien est superposée à la piste réellement posée par le traceur.

1. LE TRACEUR POSE LA PISTE
« Poser une piste » enregistre son parcours avec le GPS du téléphone, jusqu'à la cachette.

2. LE CHIEN TRAVAILLE
Après le temps de vieillissement, « Démarrer une recherche » : choisissez la piste, l'application enregistre la trace du chien. La piste reste masquée pendant la recherche, pour ne pas influencer le maître-chien. D'un appui, notez ce qui se passe : hésitation, perte de piste, reprise, négatif, distraction… (plus de 20 notes, personnalisables).

3. LE BILAN
À l'arrivée, la piste (bleu), la trace du chien (orange) et vos notes s'affichent sur la carte, avec l'âge de la piste, l'écart moyen et maximal du chien, la part de la piste couverte et si l'arrivée a été atteinte. Vous voyez où il a travaillé, où il a dépassé un virage, où il a coupé.

DEUX FAÇONS D'ENREGISTRER LA TRACE DU CHIEN
• Avec son collier GPS Tractive (compte Tractive) : le mode LIVE est activé automatiquement pendant la recherche
• Avec le GPS du téléphone du maître-chien, à quelques mètres du chien au bout de la longe : une position par seconde, même écran éteint

FONCTIONNALITÉS
• Mes pistes : pistes posées et recherches, enregistrées sur le téléphone, à revoir sur la carte
• Fonds de carte IGN (plan et photos aériennes) et OpenStreetMap
• Distance, durée, fréquence et précision des positions
• Trace du collier sur une période libre, pour une sortie qui n'a pas été enregistrée
• Séance de démonstration pour découvrir l'application

VOS DONNÉES
La position du téléphone n'est lue que pendant une pose de piste ou une recherche que vous avez démarrée, avec une notification affichée. Tout reste sur votre téléphone. Votre mot de passe Tractive n'est jamais enregistré : seul un jeton de connexion est conservé. Aucune donnée n'est envoyée au développeur. Pas de publicité, pas de suivi d'audience.

EN PRÉPARATION
Partage d'une piste entre le téléphone du traceur et celui du maître-chien.

Mantrailing est une application indépendante, ni affiliée à Tractive, ni approuvée par Tractive. Un abonnement Tractive actif est nécessaire pour utiliser le collier.

## Accès à l'application (Play Console > Contenu de l'application)

Choisir « Tout ou partie des fonctionnalités sont limitées », puis ajouter des instructions :

- Nom : Compte de démonstration
- Nom d'utilisateur : demo@mantrailing.app
- Mot de passe : demo-mantrailing
- Autres informations :
  > L'application récupère la trace GPS d'un collier Tractive (service tiers, collier physique et abonnement requis).
  > Pour l'examen, aucun compte Tractive n'est nécessaire : sur l'écran d'accueil, appuyez sur « Voir une séance de
  > démonstration », ou saisissez les identifiants ci-dessus dans Menu (⋮) > Paramètres > Collier GPS Tractive.
  > Une séance fictive complète s'affiche alors (carte, trace, bilan), sans connexion à Tractive.

## Autorisations de service au premier plan (Play Console > Contenu de l'application)

Type « Localisation » (FOREGROUND_SERVICE_LOCATION) :
- Description : « L'utilisateur démarre un enregistrement de mantrailing (pistage par un chien) : la pose de la
  piste par le traceur, ou la recherche du chien. L'application enregistre la position GPS du téléphone jusqu'à ce
  que l'utilisateur appuie sur « Terminer ». L'enregistrement doit continuer écran éteint pendant toute la séance,
  qui dure de quelques minutes à une heure. Une notification reste affichée pendant tout l'enregistrement. »
- Vidéo : démarrer une piste, montrer la notification, écran éteint, terminer la piste (lien YouTube non répertorié).

## Visuels

- Icône 512 × 512 : `icon-512.png`
- Image de présentation 1024 × 500 : `feature-graphic-1024x500.png`
- Sources SVG à côté ; régénérer les PNG avec `.\render.ps1`.
- Captures d'écran 1080 × 1920 (9:16) : `screenshot-1-trace-plan-ign.png`, `screenshot-2-trace-photos-ign.png`, séance fictive en forêt de Fontainebleau. Régénérer avec `.\screenshots.ps1` (émulateur lancé).