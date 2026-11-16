# Fiche Play Store — français

## Nom de l'application (30 caractères max)

Mantrailing

## Brève description (80 caractères max)

La trace GPS de votre chien de mantrailing sur carte IGN, point par point.

## Description complète (4000 caractères max)

Mantrailing aide les maîtres-chiens à analyser les séances de pistage de personnes (mantrailing).

Au départ du chien, « Démarrer une piste » ; à l'arrivée, « Terminer la piste ». L'application enregistre le parcours du chien et l'affiche sur la carte : vous voyez où il a travaillé, où il a hésité, où il a fait demi-tour.

DEUX FAÇONS D'ENREGISTRER LA TRACE
• Avec le collier GPS Tractive du chien (compte Tractive) : le mode LIVE est activé automatiquement pendant la piste, la trace du collier est récupérée à l'arrivée
• Avec le GPS du téléphone du maître-chien, à quelques mètres du chien au bout de la longe : une position par seconde, même écran éteint

FONCTIONNALITÉS
• Mes pistes : toutes vos pistes enregistrées sur le téléphone, à revoir sur la carte
• Fonds de carte IGN (plan et photos aériennes) et OpenStreetMap
• Bilan de chaque piste : distance, durée, fréquence des positions, précision GPS
• Trace du collier sur une période libre, pour une sortie qui n'a pas été enregistrée
• Séance de démonstration pour découvrir l'application

VOS DONNÉES
La position du téléphone n'est lue que pendant une piste que vous avez démarrée, avec une notification affichée. Vos pistes restent sur votre téléphone. Votre mot de passe Tractive n'est jamais enregistré : seul un jeton de connexion est conservé. Aucune donnée n'est envoyée au développeur. Pas de publicité, pas de suivi d'audience.

EN PRÉPARATION
Enregistrement de la piste du traceur, superposition avec la trace du chien et mesure des écarts.

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
- Description : « L'utilisateur démarre l'enregistrement d'une piste de mantrailing (pistage par un chien) ;
  l'application enregistre la position GPS du téléphone du maître-chien jusqu'à ce qu'il appuie sur « Terminer
  la piste ». L'enregistrement doit continuer écran éteint pendant toute la recherche, qui dure de quelques
  minutes à une heure. Une notification reste affichée pendant tout l'enregistrement. »
- Vidéo : démarrer une piste, montrer la notification, écran éteint, terminer la piste (lien YouTube non répertorié).

## Visuels

- Icône 512 × 512 : `icon-512.png`
- Image de présentation 1024 × 500 : `feature-graphic-1024x500.png`
- Sources SVG à côté ; régénérer les PNG avec `.\render.ps1`.
- Captures d'écran 1080 × 1920 (9:16) : `screenshot-1-trace-plan-ign.png`, `screenshot-2-trace-photos-ign.png`, séance fictive en forêt de Fontainebleau. Régénérer avec `.\screenshots.ps1` (émulateur lancé).
