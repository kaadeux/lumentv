# Lumen TV 2.1.0 – compte rendu de la mise à niveau

Voir aussi [AUDIT-V2.md](AUDIT-V2.md) (état des lieux avant les modifications).

## Problèmes identifiés

1. Recherche limitée : « spiderman » ne trouvait pas « Spider-Man », aucune tolérance aux fautes, pas de titres TMDB, tri pauvre.
2. Rangées TMDB recalculées à chaque démarrage, sans genres, plateformes, ni note personnelle ; recommandations basées sur les seuls derniers titres vus.
3. Fiche film défilante : descendre puis remonter décalait la colonne et faisait sauter la sélection. Aucune mémoire de position dans les rangées de la fiche série.
4. Barre du haut : Gauche depuis les icônes arrivait sur « TV » quand l'écran ouvert n'était pas un onglet (Favoris, Recherche…).
5. Réponses TMDB non conservées entre deux démarrages.
6. « Marquer comme vu » datait l'épisode « maintenant » : il devenait « le dernier vu » et renvoyait vers un vieil épisode. Une série terminée pouvait rester dans « Reprendre la lecture ».
7. Code mort : ancien panneau Sports basé sur le guide TV et son écran de réglages, quatre fonctions du dépôt sports, `Featured.topRated`, branche « colonne de catégories » de la TV.

## Modifications et fonctionnalités ajoutées

- **Catégories TMDB** (Films et Séries), limitées aux titres réellement présents chez le fournisseur :
  - Recommandé pour vous, Tendances du moment, Parce que vous avez aimé…, Nouveautés cinéma / séries ;
  - Dans vos genres préférés, Films / Séries populaires, À découvrir, Les mieux notés, Vous pourriez aussi aimer ;
  - 12 genres de films (9 de séries) ;
  - 7 plateformes : Netflix, Prime Video, Disney+, Max, Apple TV+, Paramount+, Canal+.

  Les rangées plateformes s'appellent « Sur Netflix · sorties récentes » : TMDB/JustWatch dit qu'un titre est **disponible** en France sur la plateforme, pas **depuis quand**. On ne peut donc pas affirmer « nouveau sur ».
- **Correspondance IPTV ↔ TMDB** (`LibraryIndex`) :
  - index en mémoire de toute la bibliothèque, titre normalisé et compacté (sans espaces, tirets ni marques de qualité), année à ± 1 an ;
  - les titres vus ou notés sont rattachés à leur identifiant TMDB (celui du fournisseur s'il le donne, sinon une recherche), conservé dans la table `tmdb_links`.
- **Notes personnelles** :
  - 5 étoiles sur la fiche d'un film commencé ou d'une série dont un épisode a été vu, sur TV et téléphone ;
  - OK sur une étoile pour noter, OK à nouveau sur la même étoile pour retirer la note ;
  - table `ratings`, rangée « Vos notes », incluses dans la sauvegarde (format 3).
- **Moteur de recommandations** (`Discover`) :
  - poids par titre : note 5 → +2 … note 1 → −2 ; sans note, regardé jusqu'au bout → +1, commencé → moins ; les titres anciens comptent un peu moins ;
  - candidats : recommandations et titres similaires TMDB des titres aimés, autres titres des acteurs et réalisateurs les plus appréciés, titres populaires des genres préférés ;
  - score : genres aimés en plus, genres mal notés en moins (éliminés s'ils dominent), popularité et note TMDB en appoint ;
  - les titres vus ou notés sont exclus, et chaque titre n'apparaît qu'une fois par rangée ;
  - sans historique, les tendances servent de point de départ.
- **Recherche intelligente** (`SmartSearch`) :
  - niveaux : exact, début du titre, début d'un mot, n'importe où, tous les mots dans le désordre, faute de frappe (1 ou 2 lettres selon la longueur) ;
  - accents, majuscules, tirets, apostrophes et espaces ignorés ;
  - titres TMDB (français et originaux) ajoutés aux résultats ;
  - tri : niveau puis longueur du titre ;
  - délai de frappe (250 ms) et résultats mis en cache.
- **Navigation à la télécommande** :
  - fiche film sans défilement ;
  - mémoire de focus (`FocusMemory`) sur les boutons, les étoiles, les saisons et les épisodes : Haut/Bas revient sur l'élément quitté ;
  - Gauche depuis les icônes arrive toujours sur l'onglet le plus à droite.
- **Séries** :
  - « Lecture / Reprendre » et « Reprendre la lecture » suivent l'épisode le plus avancé de la série (saison, numéro) ;
  - une série vue jusqu'au dernier épisode quitte « Reprendre la lecture » ;
  - « Marquer comme vu » ne change plus « le dernier épisode vu ».

## Optimisations

- **Cache disque HTTP pour TMDB** (40 Mo) : listes 6 h, fiches et recherches 7 jours. Logos, notes et rangées partagent ce même client.
- **Rangées progressives** : en trois étapes (bibliothèque seule, puis TMDB et goûts, puis genres et plateformes), chacune affichée dès qu'elle est prête. Au plus 6 appels TMDB en parallèle.
- **Index de la bibliothèque** construit en arrière-plan au démarrage : la première recherche et les premières rangées ne l'attendent plus. Il n'est reconstruit qu'après une actualisation de la playlist.
- **Requêtes légères** pour l'index (identifiant, nom, catégorie, année, note), puis chargement des seules lignes affichées, par lots.
- **Images** : couleurs 16 bits quand l'image n'a pas de transparence, soit environ deux fois moins de mémoire.

## Fichiers

- **Créés** : `shared/…/core/SmartSearch.kt`, `shared/…/test/…/SmartSearchTest.kt`, `app/…/data/repo/LibraryIndex.kt`, `app/schemas/…/7.json`, `docs/AUDIT-V2.md`, ce rapport.
- **Réécrits** : `shared/…/data/Tmdb.kt`, `app/…/data/repo/Discover.kt`.
- **Modifiés** : `LumenApp.kt`, `AppDatabase.kt`, `Daos.kt`, `Entities.kt`, `LibraryRepository.kt`, `BackupManager.kt`, `SportsRepository.kt`, `Featured.kt`, `MainActivity.kt`, `MainViewModel.kt`, `PhoneShell.kt`, `TvShell.kt`, `Components.kt`, `OtherScreens.kt`, `SportsScreens.kt`, `VodScreens.kt`, `app/build.gradle.kts`.
- **Code supprimé** :
  - ancien panneau Sports par le guide TV et `SportsSettingsScreen` ;
  - `SportEvent` et les fonctions `events`, `eventChannels`, `official`, `suggestions` ;
  - `Featured.topRated` et la colonne de catégories TV.

## Tests réalisés

- **Tests unitaires** : 68 tests, 0 échec ; les tests ignorés ont besoin d'internet. Dont 6 nouveaux sur la recherche : tirets, accents, mots dans le désordre, fautes, classement.
- **Sur la box Google TV**, à la télécommande :
  - **Migration v6 → v7** : historique, favoris et « Reprendre la lecture » conservés.
  - **Rangées** : TMDB, genres (« Science-fiction et fantastique ») et plateformes (« Sur Prime Video · sorties récentes ») bien remplies avec des titres de la playlist.
  - **Recommandations** : après une note de 4/5 sur From, la rangée « Parce que vous avez aimé From » apparaît, avec des titres proches.
  - **Notes** : 4/5 conservé après un redémarrage de l'app, puis retiré.
  - **Recherche** : « spiderman », « spidrman », « le seigneur des anneaux » et « lecole » trouvent bien Spider-Man, Le Seigneur des anneaux et L'École.
  - **Fiche film** : boutons → barre du haut → retour sur le même bouton ; bouton « Version » présent.
  - **Barre du haut** : Gauche depuis les icônes arrive sur « Séries ».
  - **From** : la série terminée n'est plus dans « Reprendre la lecture ».
- Aucun plantage pendant la session de test.

## Ce qui reste à faire ou à surveiller

- **Plateformes** : seule la disponibilité est vérifiable, pas la date d'arrivée.
- **Recherche** : les copies « (IT) » d'un titre apparaissent avant les autres, car leur titre est exactement celui cherché.
- **Rangées par genre** : elles n'existent que pour les titres que TMDB classe dans ce genre ET que le fournisseur possède. Une catégorie peut donc être maigre ou absente.
- **Téléphone** : non testé (il n'était pas connecté) ; même code que la TV.
- **PC** : il garde l'interface et la recherche V1.
- **Imports inutilisés** : non nettoyés fichier par fichier (le compilateur Kotlin ne les signale pas).
