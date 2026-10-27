# Lumen TV — lecteur IPTV pour Android TV / Google TV

Lecteur IPTV natif, gratuit, sans publicité, sans compte et sans télémétrie. Il lit **vos propres** listes
(M3U/M3U8 ou Xtream Codes) et guides TV (XMLTV) ; il ne fournit aucune chaîne.

- Audit d'IPTV Player Zero et périmètre : [docs/AUDIT.md](docs/AUDIT.md)
- Architecture, choix techniques, versions : [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)
- Stratégie de tests et checklist manuelle : [docs/TESTS.md](docs/TESTS.md)

## État des fonctionnalités

Légende : ✅ développé **et vérifié sur le boîtier** (Strong Leap-S3 Pro, Google TV 14) · ☑️ développé, testé
seulement par des tests automatiques ou partiellement · ⏳ non développé.

| Domaine | Fonctionnalité | État |
|---|---|---|
| Listes | M3U/M3U8 par URL ou fichier local (navigateur de fichiers intégré, clés USB) | ✅ fichier · ☑️ URL |
| | Plusieurs listes, nom, modification, suppression, liste active | ✅ |
| | Xtream Codes (serveur / utilisateur / mot de passe), détection d'un lien `get.php` | ☑️ (pas de serveur Xtream de test) |
| | Bouton « Recharger la liste » dans Paramètres ; rechargement automatique réglable (toutes les 24 h par défaut, 6 h à 1 semaine ou désactivé, WorkManager) | ✅ manuel (test sur le boîtier) · ☑️ automatique (tâche programmée vérifiée) |
| | Favoris, masquages et corrections EPG conservés après actualisation | ✅ (test sur le boîtier) |
| | Doublons (même adresse) ignorés, messages d'erreur clairs, listes énormes | ✅ |
| Direct | Groupes, logos, numéros, programme en cours + progression, aperçu | ✅ |
| | HLS, MPEG-TS, MP4, MKV/MOV/AVI ; en-têtes User-Agent / Referer par chaîne | ✅ HLS/MP4/MOV · ☑️ TS, en-têtes |
| | Zapping Haut/Bas et CH+/CH−, numéro au clavier, chaîne précédente (touche 0) | ✅ |
| | Reconnexion automatique (1 s → 15 s, et dès le retour du réseau), « Réessayer » | ✅ |
| | Pistes audio / sous-titres, format d'image, minuteur de veille | ✅ audio · ☑️ autres |
| | Masquer chaînes et groupes, tri A→Z / Z→A / fournisseur, PIN parental par groupe | ☑️ |
| | Reprise de la dernière chaîne au lancement (option) | ☑️ |
| Guide TV | Import XMLTV (URL, fichier, `.gz`), association auto (id puis nom), décalage horaire | ✅ |
| | Grille avec repère « maintenant », navigation horaire, détail du programme | ✅ |
| | Mini-guide dans le lecteur, en cours / à suivre, correction manuelle de l'association | ✅ / ☑️ correction |
| | Rattrapage (Xtream `timeshift`, M3U `catchup-source`) depuis le guide | ☑️ (pas de fournisseur de test) |
| Films / séries | Catégories, affiches, fiches, saisons/épisodes, favoris | ✅ (M3U) · ☑️ (Xtream) |
| | Reprise à la position, « Depuis le début », épisode suivant automatique | ✅ |
| Recherche | Chaînes, films, séries, épisodes, programmes TV — locale, sans réseau, par onglets avec compteurs | ✅ |
| | Champ « Rechercher dans cette liste » dans Direct (nom ou numéro), Films et Séries (catégorie ou toute la bibliothèque) | ✅ |
| Sports | Panneau des matchs en cours et à venir tiré du guide TV, filtré par sports / compétitions / équipes, chaînes de diffusion, lecture directe | ✅ (guide réel) |
| Téléchargements | Bouton « ⬇ Télécharger le film » sur la fiche, bouton ⬇ à droite de chaque épisode, écran « Téléchargés », reprise, lecture hors ligne | ✅ |
| Séries | « Passer le récap / l'intro » (OK) en début d'épisode, carte « Épisode suivant » au début du générique, réglages appris par série | ✅ |
| Image dans l'image | PiP Android si la TV le gère ; sinon mini-lecteur intégré pendant la navigation | ☑️ |
| Multi-écran | 2 à 4 chaînes, son sur la vignette sélectionnée | ☑️ |
| Historique | Chaînes, films, épisodes avec progression ; effacement | ✅ |
| Paramètres | Tampon normal/stable, langues audio/sous-titres, démarrage, jours d'EPG, User-Agent, cache, diagnostic | ✅ affichage · ☑️ effets |
| | Sauvegarde / restauration JSON (avec ou sans identifiants) | ☑️ |
| Non développé | regroupement 4K/HD/SD, données sportives hors guide TV (scores en direct) | ⏳ |

## Installer sur la TV depuis Windows (PowerShell)

### 1. Prérequis sur le PC (déjà installés sur ce PC pendant le développement)

```powershell
winget install -e --id Microsoft.OpenJDK.17
winget install -e --id Google.PlatformTools
```

Le SDK Android (plateforme 34, build-tools 34) est dans `%LOCALAPPDATA%\Android\Sdk` ; son chemin est indiqué
dans `local.properties` (`sdk.dir=C\:/Users/<vous>/AppData/Local/Android/Sdk`). Android Studio n'est pas
nécessaire ; il peut ouvrir le dossier `LumenTV` si vous voulez modifier le code.

### 2. Activer le débogage sur la TV

1. Paramètres → Système → À propos → appuyez 7 fois sur **Build** (« Vous êtes développeur »).
2. Paramètres → Système → Options pour les développeurs → **Débogage USB** activé.
3. Si l'option existe : **Débogage sans fil**.

### 3. Trouver l'adresse IP de la TV

Paramètres → Réseau et Internet → votre Wi-Fi → **Adresse IP** (ici `192.168.1.160`). Vérifiez que le PC la voit :

```powershell
Test-NetConnection 192.168.1.160 -Port 5555
```

### 4. Connecter ADB

- **Débogage USB réseau (port 5555)**, Android 6 à 14 sur la plupart des boîtiers :

  ```powershell
  adb connect 192.168.1.160:5555
  ```

  Acceptez « Autoriser le débogage USB ? » sur la TV (cochez « Toujours autoriser »).

- **Débogage sans fil** (Android 11+) : deux étapes, deux ports **différents**, affichés par la TV.
  *Appairer* (une seule fois) : Débogage sans fil → « Associer l'appareil avec un code » → la TV affiche
  `IP:port` et un code à 6 chiffres. *Connecter* : utilisez l'`IP:port` affiché sur l'écran principal
  « Débogage sans fil » (ce port change quand le débogage est réactivé).

  ```powershell
  adb pair 192.168.1.160:<port-d-appairage>
  adb connect 192.168.1.160:<port-de-connexion>
  ```

`adb devices` doit afficher la TV avec l'état `device` (`unauthorized` = accepter le message sur la TV).

### 5. Compiler et installer (une commande)

```powershell
cd E:\TV\LumenTV
.\deploy.ps1 -Tv 192.168.1.160 -Release
```

Le script compile, envoie l'APK, l'installe avec `pm install -r` (`adb install` peut rester bloqué sur
Android 14 en attente d'une confirmation à l'écran) et lance l'application. Sans `-Release`, il installe la
version de débogage (plus lourde, plus lente). L'application apparaît aussi dans la liste des applis de la TV.

### 6. Ajouter votre liste

Paramètres → **Ajouter une playlist M3U** ou **Ajouter un compte Xtream**. Pour éviter de taper une longue
adresse à la télécommande, copiez un fichier dans le dossier de l'application et choisissez-le avec
« … ou choisir un fichier » :

```powershell
adb push C:\chemin\ma-liste.m3u /sdcard/Android/data/com.lumentv/files/
```

Une liste de démonstration (flux de test publics) est déjà dans ce dossier : `demo.m3u`.

### Journaux en cas d'erreur

```powershell
adb logcat -s Player Playlists Epg AndroidRuntime
```

Les adresses et identifiants sont masqués (`***`) dans les journaux de l'application.

### Mettre à jour / désinstaller

```powershell
.\deploy.ps1 -Tv 192.168.1.160 -Release     # met à jour en gardant vos listes et favoris
adb uninstall com.lumentv                    # supprime l'application et ses données
```

Pensez à couper le débogage USB / sans fil sur la TV quand vous avez fini (le port 5555 n'est pas chiffré).

## Utilisation à la télécommande

| Écran | Touches |
|---|---|
| Partout | Flèches + OK ; **Retour** = écran précédent ; **appui long OK** = menu (favori, masquer, verrouiller, corriger le guide…) |
| Lecteur — direct | Haut/Bas ou CH+/CH− = chaîne ; chiffres = numéro ; **0** = chaîne précédente ; OK = infos puis liste ; Gauche = liste + mini-guide ; Droite/Menu = options |
| Lecteur — film/épisode | OK = pause ; Gauche/Droite = −10 s / +30 s (maintenir pour accélérer) ; Bas/Menu = options |
| Guide | Gauche/Droite = horaires (la fenêtre se décale de 1 h aux bords) ; OK = regarder (direct ou rattrapage) |

## Compiler et tester sans la TV

```powershell
.\gradlew.bat testDebugUnitTest          # 27 tests JVM (parseurs M3U/XMLTV, Xtream, recherche, sécurité)
.\gradlew.bat assembleRelease            # APK : app\build\outputs\apk\release\app-release.apk
$env:ANDROID_SERIAL = "192.168.1.160:5555"
.\gradlew.bat connectedDebugAndroidTest  # 6 tests sur la TV (Room, Keystore, 80 000 entrées)
```

⚠️ `connectedDebugAndroidTest` désinstalle l'application à la fin (c'est le comportement de Gradle) : vos
listes sont alors effacées. Réinstallez avec `deploy.ps1`.

## Limites connues

- **Xtream Codes, rattrapage et films/séries Xtream** sont implémentés selon l'API publique `player_api.php`,
  mais n'ont pas pu être testés faute de serveur de test : ils seront à valider avec votre abonnement.
- Import d'une très grosse liste sur ce boîtier : environ 20 s d'analyse et 15 s d'écriture pour 80 000 entrées
  (mesuré en debug ; la release est plus rapide). L'interface reste utilisable pendant l'actualisation.
- Pas de sélecteur de fichiers système sur beaucoup de TV : le navigateur intégré voit le dossier de l'app, les
  clés USB et, sous Android 6–12, le dossier Téléchargements. Sous Android 13+, utilisez le dossier de l'app.
- Les DRM (Widevine) des services commerciaux ne sont pas gérés : un flux protégé affiche « Contenu protégé ».
- Les codecs dépendent du matériel (ici décodeurs Realtek : H.264, HEVC, AC3/EAC3 vus en test).
- **Panneau sportif** : il ne connaît que ce que le guide TV de votre fournisseur annonce (pas de scores ni de
  calendrier externe), et il reconnaît les titres du type « Sport : Compétition | A / B », « A x B – Compétition »,
  « A vs B ». Un match mal titré par le fournisseur peut manquer.
- **Téléchargements** : dans `Android/data/com.lumentv/files/Telechargements` (ou sur une clé USB de plus de 2 Go
  libres). Un épisode d'une heure pèse environ 1 à 2 Go.
- **Intro et générique** : les fournisseurs IPTV ne donnent aucun repère. Le premier « Passer l'intro » avance de 85 s ;
  si vous corrigez avec Gauche/Droite dans les 15 s, la position est retenue pour toute la série. La carte « Épisode
  suivant » apparaît dans les 2,5 % finaux (30 à 90 s) ; si vous la lancez plus tôt, la durée du générique est retenue.
- **Multi-écran** : ce boîtier décode 4 flux H.264 mais seulement 2 en H.265 ; au-delà, une vignette affiche une
  erreur de codec.
- **Image dans l'image** : le Strong Leap-S3 Pro ne propose pas le PiP système ; c'est le mini-lecteur intégré qui
  s'affiche (en bas à droite des menus, géré depuis l'entrée « Mini-lecteur » du menu latéral).
- Sauvegarde « avec identifiants » : le fichier JSON contient vos mots de passe en clair ; ne le partagez pas.

## Arborescence

```
app/src/main/java/com/lumentv/
  LumenApp.kt            graphe de dépendances, cache d'images, actualisation programmée
  core/                  messages d'erreur, masquage des secrets, normalisation de texte, Keystore
  data/db/               Room : entités, DAO, base
  data/m3u/              parseur M3U en flux + classement direct / film / épisode
  data/xtream/           client Xtream Codes (JSON lu en flux)
  data/epg/              parseur XMLTV en flux + association guide ↔ chaînes
  data/repo/             import/actualisation, EPG, bibliothèque, résolution des URL de lecture
  data/settings/         paramètres DataStore (+ PIN haché)
  data/backup/           export/import JSON
  work/                  actualisation automatique (WorkManager)
  player/                moteur Media3 : en-têtes, reconnexion, pistes, tampon
  ui/                    thème, navigation, composants télécommande, écrans, lecteur
app/src/test/            tests JVM        app/src/androidTest/   tests sur la TV
samples/                 playlist et guide de démonstration (flux de test publics)
deploy.ps1               compilation + installation sur la TV
```

## Clé de signature (versions publiées)

Les APK release sont signés avec une clé dédiée, rangée **hors du projet** dans `E:\TV\LumenTV-signing\`
(`lumentv-release.jks` + `keystore.properties` qui contient le mot de passe). Le chemin est donné au build par
`local.properties` : `lumen.signing=E\:/TV/LumenTV-signing/keystore.properties`. Sans ce fichier, le build retombe
sur la clé de debug (utile pour développer, mais un tel APK ne peut pas mettre à jour une installation publiée).

- Gardez une copie de ce dossier dans un cloud privé : sans cette clé, plus aucune mise à jour ne pourra s'installer
  par-dessus les copies existantes (il faudrait désinstaller et tout reconfigurer).
- Ne la mettez jamais sur GitHub (`*.jks` et `keystore.properties` sont dans `.gitignore`).
- Augmentez `versionCode` dans `app/build.gradle.kts` à chaque version publiée.

## Publication et mises à jour (famille)

- Code source : dépôt **privé** `kaadeux/lumentv`. Versions : dépôt **public** `kaadeux/lumentv-releases`
  (uniquement `LumenTV.apk` + `version.json`), cloné dans `E:\TV\lumentv-releases`.
- `local.properties` (jamais commité) contient : `lumen.signing`, `lumen.accessCode` (code familial à 6 chiffres,
  seule son empreinte est dans l'APK), `lumen.updateUrl`, `lumen.releasesDir`.
- Publier : augmenter `versionCode` / `versionName` dans `app/build.gradle.kts`, puis
  `.\publish.ps1 -Notes "Ce qui change"`. Les TV proposent la mise à jour au démarrage suivant (jusqu'à 5 min de cache GitHub).
- Première installation (Downloader) : `https://raw.githubusercontent.com/kaadeux/lumentv-releases/main/LumenTV.apk`.
  À la première mise à jour, Android demande d'autoriser « Lumen TV » dans « Installation d'applis inconnues ».
- Changer `lumen.accessCode` puis publier redemande le code sur tous les appareils.

### Branches et canaux : tester chez soi avant la famille

| Branche du code | Canal | Qui le reçoit | Nom de version |
|---|---|---|---|
| `dev` | `dev` (branche `dev` du dépôt public) | votre boîtier | `1.2.2-dev` |
| `main` | `main` (branche `main` du dépôt public) | vos parents / proches | `1.2.2` |

`publish.ps1` et `deploy.ps1` choisissent le canal d'après la branche en cours. Chaque appareil suit le canal de
l'APK installé : une version dev ne reçoit que des mises à jour dev, une version main que des mises à jour main.

Tester une nouveauté (plusieurs jours si besoin) :
```powershell
git checkout dev
# … modifications, puis augmenter versionCode / versionName dans app\build.gradle.kts
git commit -am "Ce qui change"
.\publish.ps1 -Notes "Ce qui change"      # votre boîtier la propose au prochain démarrage
```

Valider et envoyer à la famille :
```powershell
git checkout main
git merge dev
.\publish.ps1 -Notes "Ce qui change"      # les TV de la famille la proposent au prochain démarrage
git push
git checkout dev                          # retour sur la branche de test
```
Chaque version publiée a un tag `vX.Y.Z` (ou `vX.Y.Z-dev`) qui pointe sur son commit.

## Profil en ligne et application Windows

- **Serveur de profils** : `sync-server/` (Cloudflare Worker + base D1, offre gratuite), en ligne sur
  `https://lumentv-sync.lumentv-sync.workers.dev`. Il ne stocke qu'un bloc chiffré par profil : nom + phrase secrète
  → clés dérivées sur l'appareil (PBKDF2 200 000 tours), contenu en AES-256-GCM. Le serveur garde seulement
  l'empreinte du jeton d'accès. Redéployer : `cd sync-server` puis `npx wrangler deploy`.
- **Contenu synchronisé** : playlists (avec identifiants), favoris, historique et positions de reprise, réglages
  partagés (langues, sous-titres, épisode suivant, sports). Fusion élément par élément, la modification la plus
  récente l'emporte ; les suppressions se propagent.
- **TV** : Paramètres → Profil en ligne. **PC** : Paramètres → Profil en ligne. Même nom, même phrase secrète.
- **Application Windows** (`desktop/`, Compose Desktop + moteur VLC 3.0.21 intégré) :
  `desktop\get-vlc.ps1` une fois (télécharge et vérifie VLC), puis `.\gradlew :desktop:run` pour la lancer,
  `.\gradlew :desktop:packageMsi` pour l'installateur (`desktop\build\compose\binaries\main\msi`).
- `local.properties` : `lumen.syncUrl` en plus des réglages déjà décrits.
