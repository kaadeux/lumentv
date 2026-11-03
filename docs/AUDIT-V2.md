# Lumen TV – état des lieux avant la mise à niveau (V2.1)

Date : 3 novembre 2026 · branche `dev` · version de départ 2.0.2 (TV/téléphone), 1.6.0 (PC).

## Architecture

| Module | Rôle |
| --- | --- |
| `shared/` (JVM) | Code commun TV/téléphone/PC : client Xtream, parseurs M3U/XMLTV, normalisation du texte, sports (ESPN, TheSportsDB, LNR), IntroDB, synchronisation du profil, réseau privé (DoH/HTTPS), TMDB (logos, listes). |
| `app/` (Android TV + téléphone) | Room (v6) pour playlists, chaînes, films, séries, épisodes, préférences, guide, historique, téléchargements, rappels. Compose for TV, ExoPlayer. Un seul APK, interface TV ou tactile selon l'appareil. |
| `desktop/` (Windows) | Compose Desktop + VLC. Le profil synchronisé sert de stockage local. Interface V1. |

Données : la base Room (`AppDatabase`) contient tout ce qui est personnel (favoris, historique et positions de reprise, catégories masquées, verrous) ; `SettingsStore` (DataStore) les réglages ; le profil en ligne chiffré synchronise playlists, favoris, historique et une partie des réglages entre appareils.

## Fonctionnalités en place (vérifiées dans le code et sur la box)

Direct (onglets de groupes, aperçu vidéo), guide TV, rattrapage et « reprendre au début », multi-écran, mini-lecteur, image dans l'image (téléphone), enregistrements et téléchargements, films et séries (accueil type streaming, fiches, choix de version, reprise, « Reprendre la lecture », épisodes vus), recherche locale, favoris, historique, sports (calendrier officiel, scores en direct), contrôle parental (code d'accès, groupes verrouillés), qualité automatique, barre de retard, panneau réseau, adaptation de la fréquence d'écran, intro/récap/épisode suivant (IntroDB), confidentialité réseau (DoH + HTTPS), mises à jour automatiques, synchronisation du profil, sauvegarde/restauration.

## Problèmes relevés

1. **Recherche** : `LIKE '%q%'` sur le nom normalisé. « spiderman » ne trouve pas « Spider-Man » (le tiret devient une espace), aucune tolérance aux fautes, pas de titres TMDB, tri par longueur du nom seulement.
2. **Découverte** : les rangées TMDB (tendances, populaires, mieux notés) sont recalculées en mémoire et refaites à chaque démarrage ; pas de genres TMDB, pas de plateformes, pas de note personnelle ; les recommandations ne tiennent compte que des titres récemment regardés.
3. **Fiches à la télécommande** : la fiche film est une colonne défilante ; descendre puis remonter décale la colonne. La fiche série n'a pas de mémoire du dernier élément de chaque rangée.
4. **Appels réseau** : les réponses TMDB ne sont gardées qu'en mémoire (perdues au redémarrage) ; la fiche d'un film redemande `get_vod_info` au fournisseur à chaque visite dans une nouvelle session.
5. **Code mort** : l'ancien panneau Sports basé sur le guide TV (après le `return OfficialSports(...)`), son écran de réglages et les fonctions `events`, `official`, `eventChannels`, `suggestions` du dépôt sports ; `Featured.topRated` ; branche « colonne de catégories » de la TV restée derrière un `if (true)`.

## Décisions d'architecture

- **Deux tables Room ajoutées (v7)**, sans toucher aux existantes : `tmdb_titles` (correspondance titre IPTV → TMDB et ses métadonnées : genres, acteurs, réalisateurs, collection, mots-clés, popularité, titres originaux) et `ratings` (notes personnelles 1 à 5). Migration automatique : aucune donnée existante n'est modifiée. Ces informations restent sur l'appareil.
- **Cache disque HTTP pour TMDB** (OkHttp, 30 Mo, durée forcée : listes 6 h, fiches 7 jours) : les rangées et fiches reviennent sans réseau après un redémarrage, et TMDB n'est pas redemandé.
- **Correspondance à la demande** : on ne fait pas correspondre les 50 000 titres de la playlist à TMDB d'un coup (trop d'appels). On part des listes TMDB (genres, plateformes, tendances, recommandations) et on cherche leurs titres dans un index en mémoire de la bibliothèque (titre normalisé et compacté, année ± 1). Les titres regardés ou notés sont, eux, rattachés à leur fiche TMDB détaillée et enregistrés.
- **Plateformes** : TMDB (via JustWatch) dit sur quelle plateforme un titre est **disponible** en France, mais pas **depuis quand**. Les rangées s'appellent donc « Sur Netflix » etc., triées par date de sortie la plus récente, et non « Nouveau sur Netflix », pour ne pas affirmer une information non vérifiée.
