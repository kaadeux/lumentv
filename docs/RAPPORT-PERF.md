# Rapport : audit, optimisation et réseau (v2.3.0)

Mesures faites sur la box (192.168.1.160), application en version release, le 4 novembre 2026.

## 1. Audit : ce qui a été examiné

| Domaine | Constat |
|---|---|
| Démarrage | Démarrage à froid de 511 à 582 ms (`am start -W`). Au lancement, l'index de la bibliothèque, la synchro de profil et la préparation des sports (guide FR de 9 Mo quand la copie a plus de 12 h) partaient tous en même temps. |
| Navigation Films / Séries | 24 % d'images en retard, médiane 40 ms par image (objectif 16,7 ms). Le GPU tient (8 ms par image) : c'est le fil d'interface qui ralentit (« Slow UI thread »). |
| Fil principal | 4 lectures bloquantes du fichier de réglages (`runBlocking { settings.current() }`) à l'ouverture d'un lecteur : plein écran, aperçu Direct, mini-lecteur, multi-vue. Celle de TransferService tourne sur un fil d'arrière-plan : elle ne pose pas de problème. |
| Réseau | 12 points d'appel (Xtream, M3U/EPG, TMDB, logos, sports ESPN/TheSportsDB/LNR, guide FR, mises à jour, synchro, présence, intro, panneau réseau). TMDB avait déjà un cache disque (40 Mo, listes 6 h, titres 7 jours). Il n'y avait aucune reprise après un « 429 » ou un serveur occupé, et aucune déduplication des requêtes identiques simultanées. |
| Mémoire | PSS de 149 Mo après navigation, stable. Coil est déjà en RGB565, et la taille des fonds d'écran est limitée. |
| Dépendances | Toutes sont utilisées. Minify et shrinkResources sont actifs. Aucune n'a été retirée. |

## 2. Modifications

### Fichiers créés
- `shared/.../core/Net.kt` :
  - `RetryInterceptor` : 2 nouvelles tentatives sur 429/502/503/504 ou sur coupure, GET uniquement, attente croissante (~0,5 s puis ~1,5 s ±25 %), `Retry-After` suivi jusqu'à 4 s ;
  - `NetStats` : compteurs par hôte (appels, réponses du cache, erreurs, durée moyenne) ;
  - `InFlight` : une seule requête quand plusieurs demandes identiques arrivent en même temps.
- `shared/src/test/.../core/NetTest.kt` : 6 tests (reprise, abandon, 4xx non repris, Retry-After court ou long, analyse de l'en-tête, déduplication concurrente).
- `docs/RAPPORT-PERF.md` (ce document).

### Fichiers modifiés
- `ui/screens/VodScreens.kt` :
  - l'état de focus de l'accueil Films / Séries (poster survolé, rangée, bannière) est isolé dans `HomeFocus` ; il n'est lu que par la bannière (`HeroArea`) et par le défilement des rangées (`RowScroller`), plus par tout l'accueil ;
  - le mode « bannière réduite » est supprimé : il n'était plus utilisé (toujours `false`), ainsi que son animation de hauteur.
- `ui/TvShell.kt` : le fond d'écran est composé une fois dans un calque (dégradés compris), l'image est demandée en 1280×720, et le fondu est plus court (220 ms).
- `data/settings/SettingsStore.kt`, `LumenApp.kt` : les réglages restent en mémoire (`keepWarm`, `now()`). Les 4 lecteurs les lisent sans attendre le fichier.
- `ui/player/PlayerActivity.kt`, `ui/player/MultiViewActivity.kt`, `ui/Overlays.kt`, `ui/screens/LiveScreen.kt` : passent par `settings.now()`.
- `shared/.../data/Tmdb.kt` : reprises (`RetryInterceptor`) et déduplication (`InFlight`) sur chaque requête. Les endpoints, paramètres et clé ne changent pas.
- `shared/.../data/sports/Fixtures.kt` : reprises sur le client sports (TheSportsDB répond « 429 »).
- `LumenApp.kt` :
  - `NetStats` est branché sur le client HTTP commun ;
  - la préparation des sports démarre 8 s après le lancement, pour ne pas concurrencer le premier écran ;
  - les compteurs vont dans le journal quand on le demande (voir §4).
- `data/sports/SportsRepository.kt` : les mesures de durée internes (`lap`) ne s'écrivent plus dans le journal en usage normal.
- `app/build.gradle.kts` : version 2.3.0 (52).

Aucune fonctionnalité, aucun réglage et aucune donnée utilisateur n'ont été supprimés. Le design reste identique.

## 3. Mesures avant / après (box, release)

| Mesure | Avant | Après |
|---|---|---|
| Démarrage à froid (3 essais) | 582 / 536 / 511 ms | 545 / 520 / 550 ms |
| Navigation Films : images en retard | 24,3 % | 19,0 % |
| Navigation Films : médiane par image | 40 ms | 31 ms |
| Navigation Films : 90e centile | 69 ms | 61 ms |
| Navigation Films : 99e centile | 150 ms | 150 ms |
| Mémoire (PSS) | 149 Mo | 149 Mo |

Lecture honnête de ces chiffres :
- **Navigation** : le gain vient surtout du fond d'écran mis en calque (médiane 36 → 31 ms dans le test de rangée).
- **Focus isolé** : dans le test de rangée seul, il n'a pas changé la médiane de façon mesurable (31 ms avant et après, à ±3 ms de bruit). Le coût restant vient des posters eux-mêmes (animation de focus, images), pas de l'accueil entier.
- **Démarrage** : inchangé, dans le bruit de mesure.
- **Réseau** : les gains (reprises, déduplication) jouent surtout quand TMDB ou TheSportsDB sont saturés ; le temps manquait pour les mesurer.

## 4. Mesurer le réseau

```
adb shell setprop log.tag.LumenNet DEBUG
adb logcat -s LumenNet
```

Toutes les minutes, une ligne par hôte s'affiche, par exemple : `api.themoviedb.org 42 calls, 30 cached, 0 errors, avg 85 ms`.

## 5. Tests

- **Automatiques** : 77 tests (`:shared:test :app:testReleaseUnitTest`), tous réussis. Compilation de l'app Android et de l'app Windows réussie.
- **Sur la box** :
  - lancement ;
  - Guide (EPG et fiche d'un programme) ;
  - Direct (groupes, chaînes, aperçu en direct, plein écran, changement de chaîne) ;
  - Sports (filtres, chaînes FR) ;
  - Films (bannière qui suit le focus, fiche d'un film, retour sur le même poster, défilement avec le titre de la rangée visible) ;
  - lecture d'un film ;
  - Séries (fiche, saisons, épisodes, retour avec le focus).

  Aucun plantage.
- **Pas testé sur la box** : recherche, réglages, favoris, téléchargements, multi-vue, mini-lecteur. Les lecteurs multi-vue et mini-lecteur utilisent le même `settings.now()` que le plein écran, qui a été testé.

## 6. Ce qui reste

- **Images en retard** : il en reste 19 % pendant la navigation rapide dans les rangées. Prochaine piste : alléger l'animation de focus des posters (échelle + bordure) et précharger les images des posters voisins.
- **Démarrage** : le reste est surtout le chargement de la base et du premier écran.
