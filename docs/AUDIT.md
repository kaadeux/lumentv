# Phase 1 — Audit fonctionnel d'IPTV Player Zero

Sources consultées le 27/10/2026 (informations publiques uniquement) :

- Site officiel : <https://iptvplayerzero.com/>, pages `/windows-iptv-player` et `/product-facts`
- Fiche Mac App Store (notes de version) : <https://apps.apple.com/ch/app/id6757467840>
- Fiche Microsoft Store : <https://apps.microsoft.com/detail/9p921z1kfsv9> (texte non lisible par l'outil)

## Ce qui est certain sur le modèle commercial

- Téléchargement gratuit. **Essai PRO de 72 h** après le premier import de playlist.
- **PRO à vie : 12,99 $**, paiement unique pour un appareil (packs de 3 ou 5 licences). Ce n'est pas un abonnement.
- Le site marque explicitement « PRO » : rappels / notifications / enregistrement depuis le guide, EPG complet
  (« full EPG horizon »), reprise et « Continuer à regarder », épisode suivant automatique, lecture films/séries
  illimitée, playlists / dossiers / favoris illimités, changement rapide de fournisseur, téléchargements hors
  ligne, sauvegarde/restauration.
- **Incertitude :** aucune page ne dit ce qui reste disponible en mode gratuit après l'essai, ni les limites
  chiffrées (nombre de playlists, de favoris…). La colonne « Gratuit / PRO » ci-dessous dit « PRO » seulement
  quand le site l'écrit, sinon « non précisé ».

## Matrice fonctionnelle

Légende décision : **R** = reproduire, **A** = adapter à Android TV, **E** = alternative équivalente,
**X** = écarté. Priorité : P1 = socle (phase 3), P2 = phase 4, P3 = plus tard.

| Fonctionnalité | Confirmée chez Player Zero | Gratuit / PRO | Faisable Android TV | Solution technique | Décision | Priorité |
|---|---|---|---|---|---|---|
| M3U/M3U8 par URL ou fichier | Oui (site) | non précisé | Oui | Parseur M3U en flux (OkHttp + lecture ligne à ligne) | R | P1 |
| Xtream Codes (serveur/utilisateur/mot de passe) | Oui | non précisé | Oui | `player_api.php` en JSON (kotlinx.serialization) | R | P2 |
| Stalker / MAC Portal | Oui | non précisé | Oui, mais protocole non documenté et variable selon les fournisseurs | Client portal (handshake, token, `get_all_channels`, `create_link`) | A | P3 |
| Plusieurs playlists, nom personnalisé, édition, suppression | Oui | « illimité » = PRO | Oui | Table `playlists` (Room) | R (illimité, gratuit) | P1 |
| Mode playlists combinées | Oui (notes de version) | non précisé | Oui | Filtre « toutes les playlists » | A | P3 |
| Actualisation manuelle / intervalle personnalisé | Oui | non précisé | Oui | Bouton + WorkManager périodique | R | P1 / P2 |
| Connexions Xtream actives | Oui (notes) | non précisé | Oui | `user_info` (connexions actives/max, expiration) affiché | R | P2 |
| Chaînes par catégories, logos | Oui | non précisé | Oui | Room + Coil (chargement progressif) | R | P1 |
| Changement rapide de chaîne | Oui | non précisé | Oui | Haut/Bas et CH+/CH- dans le lecteur | R | P1 |
| Favoris | Oui | « illimité » = PRO | Oui | Flag sur la chaîne, conservé après actualisation | R | P1 |
| Chaînes récentes / historique | Oui | non précisé | Oui | Table `history` | R | P1 |
| Recherche globale (toutes les playlists) | Oui | non précisé | Oui | Requêtes Room locales (sans réseau) | R | P2 |
| Tri A–Z, Z–A, ordre fournisseur | Oui (notes) | non précisé | Oui | Option de tri | R | P2 |
| Réordonner les chaînes à la main | Oui (glisser-déposer) | non précisé | Adapté : pas de souris | Mode « déplacer » à la télécommande (favoris) | A | P3 |
| Regrouper les versions (4K/FHD/HD/SD) | Oui (notes) | non précisé | Oui | Normalisation du nom + menu Versions | A | P3 |
| Masquer les chaînes « +1 » | Oui (notes) | non précisé | Oui | Masquage de chaînes et de groupes (plus général) | E | P2 |
| Bascule automatique sur un autre flux en panne | Oui (notes) | non précisé | Oui | Reconnexion auto + versions | A | P3 |
| Guide XMLTV, association EPG | Oui | non précisé | Oui | XmlPullParser en flux, gzip, insertion par lots | R | P2 |
| Correction manuelle de l'association EPG | Oui (« EPG mapping ») | non précisé | Oui | Champ `epgIdOverride` par chaîne | R | P2 |
| Décalage EPG | Oui | non précisé | Oui | Décalage en minutes par playlist | R | P2 |
| Grille avec marqueur « maintenant » | Oui | non précisé (« full horizon » = PRO) | Oui | Grille Compose virtualisée | R | P2 |
| En cours / À suivre en surimpression | Oui (notes) | non précisé | Oui | Bandeau d'infos dans le lecteur | R | P2 |
| Rappels de programmes | Oui | PRO | Oui | AlarmManager + notification (Android TV affiche peu les notifications) | A | P3 |
| Enregistrement / programmé / séries | Oui | PRO | Possible mais lourd (stockage limité, l'app doit tourner) | Copie du flux TS dans un fichier | X pour l'instant | P3 |
| Rattrapage TV jusqu'à 14 jours | Oui (notes) | non précisé | Oui si le fournisseur Xtream le propose (`tv_archive`) | URL `timeshift/` depuis le guide | R | P2 |
| Films / séries, affiches, saisons/épisodes | Oui | « illimité » = PRO | Oui | Xtream VOD + détection dans les M3U | R | P2 |
| Reprise de lecture / Continuer à regarder | Oui | PRO | Oui | Position sauvegardée toutes les 10 s | R | P2 |
| Épisode suivant automatique | Oui | PRO | Oui | À la fin d'un épisode, enchaîne le suivant | R | P2 |
| Bandes-annonces Xtream | Oui (notes) | non précisé | Partiel : liens YouTube | Ouvrir via une app YouTube installée | E | P3 |
| Section Découvrir / Top 10 / tendances | Oui (notes) | non précisé | Dépend de métadonnées externes | « Ajoutés récemment » / mieux notés depuis Xtream | E | P3 |
| Choix de piste audio, sous-titres, langue audio préférée | Oui | non précisé | Oui | Media3 `TrackSelectionParameters` | R | P1 / P2 |
| Sous-titres secondaires | Oui (notes) | non précisé | Media3 n'affiche qu'une piste texte | X | — |
| Miniatures sur la barre de recherche | Oui (notes) | non précisé | Nécessite des sprites fournis par la source | X | — |
| Picture-in-Picture | Oui (notes) | non précisé | Oui sur Android TV 8+ (selon l'appareil) | PiP Android | A | P3 |
| Multi-écran | Oui (notes) | non précisé | Plusieurs décodeurs simultanés : risqué sur 1–2 Go de RAM | X | — |
| Minuteur de mise en veille | Oui | non précisé | Oui | Pause / sortie après N minutes | R | P2 |
| Formats d'image (aspect ratio) | Oui | non précisé | Oui | `resizeMode` du lecteur | R | P2 |
| Lecture stable sur connexion faible | Oui | non précisé | Oui | Tampon plus long (LoadControl) | R | P2 |
| HDR tone mapping, bitstream passthrough | Oui | non précisé | Passthrough : géré par Media3 + l'appareil | Par défaut Media3 | A | — |
| Raccourcis clavier personnalisables | Oui | non précisé | Remplacé par la télécommande | Touches numériques, CH+/-, Info, Guide | E | P2 |
| Téléchargements hors ligne | Oui | PRO | Stockage interne des boîtiers souvent < 8 Go | X pour l'instant | P3 |
| Sauvegarde / restauration | Oui | PRO | Oui | Export/import JSON (sans mots de passe par défaut) | R | P2 |
| Contrôle parental (PIN, mots-clés) | Oui | non précisé | Oui | PIN à 4 chiffres sur groupes verrouillés | R | P2 |
| Thèmes clair/sombre | Oui | non précisé | Sombre adapté à la TV | Thème sombre unique | A | — |
| Trakt, Discord, casting | Oui | non précisé | Services propriétaires | X | — |
| Hub sport, planificateur foot, tableau Coupe du monde | Oui (notes) | non précisé | Données externes | X | — |
| Mise à jour intégrée | Oui | non précisé | Hors Play Store | Script de mise à jour ADB | E | P1 |

## Ajouts demandés absents de la liste ci-dessus

En-têtes HTTP par chaîne (`#EXTVLCOPT:http-user-agent/referrer`), reconnexion après coupure réseau, retour à
la chaîne précédente, masquage, détection des doublons, export/import des paramètres et diagnostic local
sans identifiants. Tout est prévu en P1/P2.

## Périmètre retenu

- **Socle (P1) :** playlists M3U (URL/fichier), TV en direct par groupes, lecteur Media3 avec reconnexion et
  pistes, favoris, historique, dernière chaîne, chaîne précédente, masquage.
- **P2 :** Xtream Codes, EPG XMLTV (grille, en cours/à suivre, décalage, correction), rattrapage Xtream, films et
  séries avec reprise et épisode suivant, recherche globale, tri, actualisation automatique, PIN parental, minuteur
  de veille, formats d'image, sauvegarde/restauration.
- **Écarté ou reporté :** enregistrement, téléchargements, multi-écran, sous-titres doubles, miniatures, Trakt,
  Discord, hub sport. Stalker, rappels et PiP sont reportés en P3.
