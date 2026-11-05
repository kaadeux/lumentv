# Lumen TV

Lecteur IPTV gratuit, sans publicité, sans compte et sans télémétrie, pour **Android TV / Google TV**, les
**téléphones Android**, **Windows** et l'**iPhone**. Il lit **vos propres** listes (Xtream Codes ou M3U/M3U8) et
guides TV (XMLTV) : il ne fournit aucune chaîne ni aucun contenu.

- Architecture et choix techniques : [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Stratégie de tests et checklist manuelle : [docs/TESTS.md](docs/TESTS.md)

## Fonctionnalités

| Domaine | Ce que fait l'application |
|---|---|
| Listes | Xtream Codes et M3U/M3U8 (URL ou fichier), plusieurs listes, rechargement automatique réglable, doublons ignorés |
| Direct | Groupes, logos, numéros, programme en cours, favoris, zapping, reconnexion automatique, qualité auto (FHD → HD → SD quand l'image coupe) |
| Guide TV | Import XMLTV (`.gz` compris), association automatique ou manuelle, grille, rattrapage (Xtream `timeshift`, M3U `catchup-source`) |
| Films et séries | Accueil façon plateforme de streaming (rangées TMDB, bannière, logos), une affiche par titre et choix de la version (langue, qualité), fiches, notes, distribution |
| Lecture | Reprise à la position, épisode suivant automatique, « Passer l'intro » et le générique (TheIntroDB), pistes audio et sous-titres (français forcés par défaut), formats d'image |
| Recherche | Tolérante aux fautes, par titre français ou original, par acteur ou réalisateur, dans les chaînes, épisodes et programmes |
| Sport | Calendrier officiel des compétitions choisies, scores en direct, chaînes de votre liste qui diffusent chaque match |
| Téléchargements | Films et épisodes à voir sans réseau, un par un dans une file d'attente |
| Profil en ligne | Listes, favoris, historique, reprises, notes et réglages partagés entre appareils, chiffrés de bout en bout |
| Famille | Code parental, groupes masqués, avertissement quand l'abonnement est déjà en lecture sur un autre écran |

Toutes les fonctions ne sont pas encore disponibles sur chaque application : l'application TV est la plus complète.

## Applications et modules

| Dossier | Rôle | Technologies |
|---|---|---|
| `shared/` | Code commun à toutes les apps : Xtream, M3U, XMLTV, TMDB, recherche, sport, profil chiffré | Kotlin Multiplatform (JVM + iOS) |
| `appcore/` | Base de données, réglages et dépôts communs à Android et à l'iPhone | Kotlin Multiplatform, Room 2.7, DataStore |
| `phoneui/` | Écrans téléphone partagés (framework iOS `LumenKit`) | Compose Multiplatform |
| `app/` | Une seule APK pour Android TV et téléphones Android | Jetpack Compose, tv-material, Media3 (ExoPlayer) |
| `desktop/` | Application Windows | Compose Desktop, VLC (vlcj) |
| `iosApp/` | Application iPhone : lecteur et liaison avec `LumenKit` | Swift, SwiftUI, KSPlayer (FFmpeg), XcodeGen |
| `sync-server/` | Serveur du profil en ligne | Cloudflare Worker + D1 |

## Compiler

### Android (TV et téléphone)

Prérequis : JDK 17 et le SDK Android (plateforme 34), avec son chemin dans `local.properties`
(`sdk.dir=…`). Android Studio n'est pas nécessaire.

```powershell
.\gradlew.bat assembleRelease              # APK : app\build\outputs\apk\release\app-release.apk
.\gradlew.bat :shared:jvmTest :app:testReleaseUnitTest
```

Installer sur une TV en débogage réseau (Paramètres → Système → Options pour les développeurs) :

```powershell
adb connect <IP-de-la-TV>:5555
.\deploy.ps1 -Tv <IP-de-la-TV> -Release
```

Pensez à couper le débogage sur la TV une fois fini : le port 5555 n'est pas chiffré.

### Windows

```powershell
desktop\get-vlc.ps1                 # une fois : télécharge et vérifie VLC
.\gradlew.bat :desktop:run          # lance l'application
.\gradlew.bat :desktop:packageMsi   # installateur dans desktop\build\compose\binaries\main\msi
```

### iPhone

L'application iPhone se compile sur les Mac de GitHub Actions : aucun Mac n'est nécessaire.

- `.github/workflows/ios-app.yml`, lancé à la main, compile `LumenKit` (Gradle), puis l'app (XcodeGen et Xcode),
  en une IPA non signée.
- L'IPA ne quitte la machine de compilation que **chiffrée**, avec le secret `LUMEN_IPA_PASSWORD`. Elle contient en
  effet la clé TMDB et l'adresse du serveur de synchronisation.
- Elle s'installe avec **SideStore** et un identifiant Apple gratuit.
- `.github/workflows/shared-ios.yml` lance les tests du code commun sur le simulateur iPhone.

### Serveur du profil en ligne

```powershell
cd sync-server
npx wrangler deploy
```

## Configuration locale (jamais commitée)

`local.properties` contient les réglages propres à chaque installation :

| Clé | Rôle |
|---|---|
| `sdk.dir` | Chemin du SDK Android |
| `lumen.signing` | Fichier des mots de passe de la clé de signature, rangée hors du projet |
| `lumen.accessCode` | Code d'accès familial : seule son empreinte est intégrée à l'APK |
| `lumen.tmdbKey` | Clé TMDB (gratuite, en lecture seule) pour les rangées, logos et notes |
| `lumen.syncUrl` | Adresse du serveur de profils |
| `lumen.updateUrl`, `lumen.releasesDir` | Canal de mise à jour des APK publiées |

Pour l'iPhone, les mêmes valeurs sont des secrets du dépôt : `LUMEN_TMDB_KEY`, `LUMEN_SYNC_URL`, et
`LUMEN_IPA_PASSWORD` pour le chiffrement de l'IPA.

Sans clé TMDB, les accueils Films et Séries n'affichent que les rangées de la liste. Sans adresse de synchro, il
n'y a pas de profil en ligne. Ni la clé de signature (`*.jks`, `keystore.properties`) ni `local.properties` ne
doivent être commités : ils sont dans `.gitignore`.

## Sécurité et vie privée

- **Identifiants** : les adresses et mots de passe des listes sont chiffrés sur l'appareil (Keystore Android,
  Trousseau iOS, DPAPI sous Windows) et masqués (`***`) dans les journaux.
- **Profil en ligne** : chiffré sur l'appareil avant l'envoi. La clé vient du nom et de la phrase secrète
  (PBKDF2, 200 000 tours), le contenu est chiffré en AES-256-GCM. Le serveur ne garde qu'un bloc chiffré et
  l'empreinte du jeton d'accès.
- **Réseau (TV et téléphone Android)** : DNS chiffré, et HTTPS quand le serveur le permet.
- **Aucun tiers** : ni télémétrie, ni publicité, ni compte chez un tiers.

## Publication (Android)

- **Canaux** : les APK signées sont publiées sur deux canaux, selon la branche du code.
  - `dev` : versions de test, nom de version en `-dev`.
  - `main` : versions stables.
- **Mises à jour** : chaque appareil suit le canal de l'APK installée, et propose la nouvelle version au démarrage.
- **Publier** : augmenter `versionCode` et `versionName` dans `app/build.gradle.kts`, puis
  `.\publish.ps1 -Notes "Ce qui change"`. Chaque version publiée a un tag `vX.Y.Z`.

## Limites connues

- Les DRM (Widevine) des services commerciaux ne sont pas gérés.
- Les codecs dépendent du matériel de la TV ou du téléphone.
- Le sport n'affiche que les compétitions suivies ; les chaînes viennent du guide de votre liste et du guide
  français xmltvfr.fr.
- Sur iPhone, les téléchargements s'arrêtent si l'app est fermée de force ; ils reprennent alors depuis le début.
