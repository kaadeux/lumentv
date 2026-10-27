# Tests

## Automatiques

| Suite | Où | Contenu | Résultat (27/10/2026) |
|---|---|---|---|
| `M3uTest` (11) | JVM | attributs, virgules entre guillemets, `#EXTVLCOPT`, `#KODIPROP`, `url|User-Agent=`, BOM/CRLF, `#EXTGRP`, liste vide, page HTML, texte invalide, XMLTV donné comme playlist, classement direct/film/épisode, doublons, uid stables après actualisation, 60 000 entrées | 11/11 ✅ |
| `EpgTest` (6) | JVM | fuseaux horaires XMLTV, chaînes et programmes, CDATA/entités, fin manquante, fichier non XMLTV, guide vide, association (correction > id > nom), 45 000 programmes | 6/6 ✅ |
| `XtreamAndCoreTest` (10) | JVM | adresse serveur, lien `get.php`, URL de rattrapage dans le fuseau du serveur, flux JSON avec éléments invalides, épisodes (objet/tableau/null), import Xtream, masquage des identifiants, recherche sans accents, modèles de rattrapage, tri | 10/10 ✅ |
| `StorageTest` (6) | **sur la TV** | favoris et masquages conservés après actualisation, réordonnancement des favoris, recherche accent-insensible, reprise de lecture, chiffrement Keystore, 50 000 chaînes + 30 000 films (temps mesurés) | 6/6 ✅ |

Mesures sur le Strong Leap-S3 Pro (Realtek RTD1325, build debug instrumenté, noms accentués) :
analyse 19,7 s, écriture 14,9 s, liste des groupes 0,15 s, un groupe 0,12 s, recherche 0,14 s.

## Vérifié manuellement sur le boîtier (captures dans `shots/`)

- [x] Premier lancement : écran d'accueil Paramètres, focus visible, menu latéral.
- [x] Ajout d'une playlist depuis le navigateur de fichiers ; guide `url-tvg` relatif trouvé et associé.
- [x] Direct : groupes, programme en cours avec progression, aperçu du guide.
- [x] Lecture HLS (Mux, Apple BipBop multi-pistes, Akamai live) avec décodeur matériel.
- [x] Zapping Haut/Bas, bandeau en cours / à suivre, saisie d'un numéro.
- [x] Pistes audio : AAC stéréo, AC3 5.1, EAC3 5.1 listées et sélectionnables.
- [x] Liste des chaînes + mini-guide dans le lecteur ; Retour ferme le panneau.
- [x] Hôte injoignable : message clair, nouvelle tentative programmée ; HTTP 403/404 expliqués.
- [x] Film : lecture, avance +30 s, sortie, « Reprendre à 1:10 ».
- [x] Série : fiche, épisodes, lecture, épisode suivant automatique, retour à la fiche à la fin.
- [x] Guide en grille : repère « maintenant », décalage horaire aux bords, détail du programme.
- [x] Recherche par le clavier virtuel, résultats instantanés.
- [x] Actualisation manuelle d'une playlist.
- [x] Version release : démarrage à froid 0,7–0,8 s, 46 Mo de mémoire (PSS), aucun plantage.
- [x] Navigation rapide entre écrans (test d'endurance) sans plantage après correction du bug `focusRestorer`.

## Vérifié avec l'abonnement réel de l'utilisateur (27/10/2026)

- [x] Chargement de la playlist : groupes, logos, numéros, guide associé, badges « Replay ».
- [x] Chaînes en direct et zapping.
- [x] Rattrapage d'un programme passé.
- [x] Film.
- [x] Séries : plantage (« Key "0" was already used ») à l'ouverture des épisodes Xtream, corrigé ; saisons,
      épisodes et lecture confirmés ensuite. Mémoire avec la liste réelle : environ 110 Mo (PSS).

## À vérifier par vous (checklist)

- [ ] Guide de votre fournisseur : corriger une association (appui long › Corriger le guide).
- [ ] Reprise d'un film ou d'un épisode Xtream après l'avoir quitté.
- [ ] Sous-titres sur un film qui en contient.
- [ ] Mise en veille du boîtier pendant une lecture, puis réveil : la lecture reprend / l'app revient.
- [ ] Couper le Wi-Fi 10 s pendant une chaîne : « Reconnexion… » puis reprise automatique.
- [ ] Code PIN : verrouiller un groupe, redémarrer l'app, vérifier que le PIN est demandé.
- [ ] Sauvegarde puis restauration (Paramètres › Sauvegarde).
- [ ] Actualisation automatique : réglez 6 h et vérifiez la date « actualisée le » le lendemain.
