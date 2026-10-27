# Phase 2 — Conception de Lumen TV

## Identité

- Nom : **Lumen TV**. Package Android : `com.lumentv`.
- Un seul module Gradle `app`, découpé en paquets. C'est suffisant pour une app de cette taille, et ça compile vite.

## Choix techniques

| Sujet | Choix | Pourquoi |
|---|---|---|
| Langage | Kotlin 2.0.21 | Standard Android, coroutines |
| Interface | Jetpack Compose (BOM 2024.10.01) + `androidx.tv:tv-material` 1.0.0 | Composants TV maintenus par Google (focus, mise à l'échelle) ; listes paresseuses standard de Compose Foundation 1.7, que tv-material recommande depuis la 1.0 |
| Lecture | Media3 1.4.1 (ExoPlayer, HLS, source OkHttp) | HLS, MPEG-TS, MP4, MKV ; en-têtes HTTP par flux ; pistes audio/sous-titres |
| Réseau | OkHttp 4.12.0 | Délais d'attente, redirections, gzip, même client pour Media3 |
| Base locale | Room 2.6.1 (KSP) | Des milliers de chaînes et un EPG volumineux, requêtes indexées, recherche locale |
| Paramètres | DataStore Preferences 1.1.1 | Paramètres simples et asynchrones |
| JSON | kotlinx.serialization 1.7.3 | API Xtream, sauvegarde/restauration |
| Images | Coil 2.7.0 | Chargement progressif, cache mémoire et disque bornés |
| Tâches | WorkManager 2.9.1 | Uniquement pour l'actualisation automatique |
| Secrets | Android Keystore (AES-GCM) | Mots de passe Xtream et URL de playlist chiffrés au repos |

Aucune autre dépendance : pas d'injection de dépendances (un `AppGraph` manuel), pas de Navigation Compose
(une pile d'écrans de quelques lignes).

### minSdk, targetSdk, compileSdk

- **minSdk 23 (Android 6.0).** Couvre pratiquement tous les téléviseurs Android TV encore en service (les
  Android TV 5.x sont devenus très rares) ; c'est aussi le minimum pour un Keystore AES fiable. Les API
  `java.time` sont rendues disponibles en dessous d'Android 8 grâce au *desugaring*.
- **targetSdk 34 / compileSdk 34 (Android 14).** Exigé par les bibliothèques ci-dessus ; votre boîtier tourne
  sous Android 14 (API 34). Certains Google TV 14 refusent les apps qui ciblent un SDK trop ancien.

## Arborescence

```
LumenTV/
├─ app/build.gradle.kts            dépendances, minSdk/targetSdk, signature debug
├─ app/src/main/AndroidManifest.xml  LEANBACK_LAUNCHER, bannière, pas de tactile requis
├─ app/src/main/java/com/lumentv/
│  ├─ LumenApp.kt                  Application : graphe, cache d'images, actualisation programmée
│  ├─ AppGraph.kt                  « injection » manuelle : base, OkHttp, dépôts
│  ├─ core/                        erreurs lisibles, masquage des identifiants, normalisation de texte, Keystore
│  ├─ data/db/                     entités, DAO et base Room
│  ├─ data/m3u/                    parseur M3U/M3U8 en flux (attributs, #EXTVLCOPT, détection VOD)
│  ├─ data/xtream/                 client Xtream Codes (player_api.php) et construction des URL
│  ├─ data/epg/                    parseur XMLTV en flux (gzip) et association aux chaînes
│  ├─ data/repo/                   import / actualisation (préférences conservées), EPG, bibliothèque, historique
│  ├─ data/settings/               paramètres DataStore
│  ├─ data/backup/                 export / import JSON
│  ├─ work/                        actualisation automatique (WorkManager)
│  ├─ player/                      ExoPlayer : en-têtes, reconnexion, pistes, tampon
│  └─ ui/                          thème, navigation, composants, écrans, lecteur
└─ app/src/test/                   tests JVM : M3U, XMLTV, Xtream, recherche, fusion des préférences
   app/src/androidTest/            tests Room exécutés sur le téléviseur
```

## Modèle de données

- `playlists` : nom, type (M3U / Xtream), URL ou serveur, utilisateur, mot de passe chiffré, URL EPG,
  décalage EPG, intervalle d'actualisation, date et résultat de la dernière actualisation.
- `channels`, `movies`, `series` : remplacés à chaque actualisation.
- `prefs` : favoris (avec ordre), masquage, correction EPG, **indexés par un identifiant stable**
  (`streamId` Xtream, sinon nom + `tvg-id`). Ils survivent donc aux actualisations.
- `programs` : EPG, limité à la fenêtre utile (passé de rattrapage → +N jours), index `(epgKey, start)`.
- `progress` / `history` : position de lecture et historique.

## Écrans et navigation

Menu latéral (Direct, Guide, Favoris, Films, Séries, Recherche, Historique, Paramètres ; les entrées sans
contenu sont masquées). Lecteur plein écran dans une activité dédiée (`PlayerActivity`) pour libérer
proprement le décodeur à chaque sortie.

Télécommande dans le lecteur : OK = infos / liste des chaînes, Haut/Bas ou CH+/CH- = chaîne suivante,
Gauche = liste, Droite = pistes, chiffres = numéro de chaîne, Retour = masquer puis quitter, « 0 » ou
double Retour rapide = chaîne précédente.

## Risques identifiés

1. **Playlists et EPG énormes** (50 000 VOD, XMLTV de 100 Mo) → parsing en flux, insertion par lots, pas de
   chargement complet en mémoire, EPG limité aux chaînes connues et à une fenêtre de jours.
2. **Pas de sélecteur de fichiers** sur beaucoup de boîtiers (le vôtre n'a pas `DocumentsUI`) → navigateur de
   fichiers intégré (stockage de l'app, clés USB, Téléchargements) en plus du sélecteur système quand il existe.
3. **Saisie au clavier virtuel pénible** → champs pré-remplis (`http://`), et import depuis un fichier.
4. **Fournisseurs hétérogènes** (Xtream non standard, en-têtes requis) → messages d'erreur explicites.
5. **Codecs** : dépend du matériel ; Media3 signale les formats non pris en charge.
