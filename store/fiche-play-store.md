# Fiche Play Store — français

## Nom de l'application (30 caractères max)

Mantrailing

## Brève description (80 caractères max)

La trace GPS de votre chien comparée à la piste du traceur, sur carte IGN.

## Description complète (4000 caractères max)

Mantrailing aide les maîtres-chiens à analyser leurs séances de pistage de personnes : la trace du chien est superposée à la piste réellement posée par le traceur, sur carte IGN.

1. LE TRACEUR POSE LA PISTE
« Poser une piste » enregistre son parcours au GPS du téléphone, même écran éteint ; le tracé et la distance s'affichent en direct. En chemin, il signale ce que le maître-chien doit savoir : danger, point d'attention, route, changement de sol.

2. LE CHIEN TRAVAILLE
Après le vieillissement, « Démarrer une recherche » : la piste reste masquée pour ne pas influencer le maître-chien, seuls les signalements du traceur sont annoncés. D'un appui, notez hésitation, perte de piste, reprise, distraction… (plus de 20 notes, boutons personnalisables).

3. LE BILAN
La piste (bleu), la trace du chien (orange) et vos notes sur la carte, avec l'âge de la piste, l'écart moyen et maximal, la part de la piste couverte, l'arrivée atteinte ou non. La météo (vent, température, humidité) est ajoutée toute seule.

LA TRACE DU CHIEN
• Collier GPS Tractive (mode LIVE) ou Weenect (super live), activé automatiquement pendant la recherche
• GPS du téléphone du maître-chien, une position par seconde
• Fichier GPX d'un GPS Garmin (Alpha…) ou d'une autre application

POUR PROGRESSER
• Carnet d'entraînement : traceur, objet odorant, lieu, terrain, difficultés, récompense, note de l'équipe
• Progrès de chaque chien : % de pistes trouvées, âge de piste, écart moyen, records, courbes sur 3 mois, 12 mois ou tout
• Mode formateur : sur le téléphone du formateur, la piste reste visible avec un couloir de 10 à 50 m ; il vibre quand l'équipe en sort

SUR LE TERRAIN
• Cartes hors ligne : téléchargez le plan IGN du terrain avant de partir dans un bois sans réseau
• Fonds IGN (plan, photos aériennes) et OpenStreetMap, bouton « Me localiser »
• Plusieurs chiens, chacun ses recherches

PARTAGER ET GARDER
• Rapport PDF (carte, chiffres, météo, notes) et export GPX, à envoyer ou imprimer
• Sauvegarde en ligne facultative, hébergée en France : rien n'est perdu en changeant de téléphone, et seules les séances récentes restent en entier sur le téléphone ; synchronisation possible en Wi-Fi seulement
• Équipes (famille, club) : invitation par code ; une équipe ne voit que ce qui est fait en son nom, jamais le reste

Aide intégrée, tour guidé au premier lancement et séance de démonstration pour découvrir l'application.

VOS DONNÉES
La position du téléphone n'est lue que pendant une pose de piste ou une recherche que vous avez démarrée, avec une notification affichée. Sans la sauvegarde en ligne, tout reste sur votre téléphone. Vos mots de passe Tractive et Weenect ne sont jamais enregistrés : seul un jeton de connexion est conservé. Pas de publicité, pas de suivi d'audience.

Mantrailing est une application indépendante, affiliée ni à Tractive ni à Weenect, ni approuvée par eux. Un abonnement actif auprès du fabricant est nécessaire pour utiliser un collier.

## Accès à l'application (Play Console > Contenu de l'application)

Choisir « Tout ou partie des fonctionnalités sont limitées », puis ajouter des instructions :

- Nom : Compte de démonstration
- Nom d'utilisateur : demo@mantrailing.app
- Mot de passe : demo-mantrailing
- Autres informations :
  > L'application récupère la trace GPS d'un collier Tractive (service tiers, collier physique et abonnement requis).
  > Pour l'examen, aucun compte Tractive n'est nécessaire : sur l'écran d'accueil, appuyez sur « Voir une séance de
  > démonstration », ou saisissez les identifiants ci-dessus dans Menu (⋮) > Paramètres > Colliers GPS > Tractive.
  > Une séance fictive complète s'affiche alors (carte, trace, bilan), sans connexion à Tractive.

## Autorisations de service au premier plan (Play Console > Contenu de l'application)

Type « Localisation » (FOREGROUND_SERVICE_LOCATION) :
- Description : « L'utilisateur démarre un enregistrement de mantrailing (pistage par un chien) : la pose de la
  piste par le traceur, ou la recherche du chien. L'application enregistre la position GPS du téléphone jusqu'à ce
  que l'utilisateur appuie sur « Terminer ». L'enregistrement doit continuer écran éteint pendant toute la séance,
  qui dure de quelques minutes à une heure. Une notification reste affichée pendant tout l'enregistrement. »
- Vidéo : démarrer une piste, montrer la notification, écran éteint, terminer la piste (lien YouTube non répertorié).

## Sécurité des données — avec la sauvegarde en ligne (Play Console > Contenu de l'application)

Version avec la sauvegarde en ligne : versionCode 36 (test interne). Politique de confidentialité correspondante en
ligne depuis le 17/11/2026.

Réponses, dans l'ordre des questions du formulaire :

1. « Votre application collecte-t-elle ou partage-t-elle des données utilisateur ? » → **Oui**
2. « Toutes les données utilisateur collectées sont-elles chiffrées en transit ? » → **Oui** (HTTPS)
3. « Quelles méthodes de création de compte votre application prend-elle en charge ? » → **OAuth** seulement
   (connexion avec le compte Google).
4. « Lien de suppression du compte » →
   `https://djkspr.github.io/mantrailing-site/politique-confidentialite.html#suppression-compte`
   (la page explique la suppression dans l'appli : Paramètres › Sauvegarde en ligne › Supprimer mes données en ligne).
5. « Les utilisateurs peuvent-ils demander la suppression de certaines de leurs données sans supprimer leur compte ? »
   → **Oui** (chaque piste ou recherche se supprime depuis « Mes pistes », sur le serveur aussi) ; même lien si demandé.
6. Types de données : tableau ci-dessous.

| Type de données | Collectée | Partagée | Facultative | Finalité |
|---|---|---|---|---|
| Position exacte | Oui | Non¹ | Oui (sauvegarde désactivable) | Fonctionnalités de l'appli |
| Nom | Oui | Non¹ | Oui | Gestion du compte, fonctionnalités (nom montré aux membres d'une équipe) |
| Adresse e-mail | Oui | Non | Oui | Gestion du compte |
| ID utilisateur | Oui | Non | Oui | Gestion du compte |
| Autres contenus générés par l'utilisateur (notes, noms des chiens) | Oui | Non¹ | Oui | Fonctionnalités de l'appli |

¹ Visible des membres d'une équipe que l'utilisateur rejoint lui-même : Google ne compte pas comme « partage » un
transfert initié par l'utilisateur vers d'autres utilisateurs qu'il choisit, ni l'hébergeur (prestataire).

## Visuels

- Icône 512 × 512 : `icon-512.png`
- Image de présentation 1024 × 500 : `feature-graphic-1024x500.png`
- Sources SVG à côté ; régénérer les PNG avec `.\render.ps1`.
- Captures d'écran 1080 × 1920 (9:16) : `screenshot-1-trace-plan-ign.png`, `screenshot-2-trace-photos-ign.png`, séance fictive en forêt de Fontainebleau. Régénérer avec `.\screenshots.ps1` (émulateur lancé).