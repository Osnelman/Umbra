# Umbra — bloqueur porno système + verrou binaire

> Nom affiché : **Umbra** · paquet : `com.freebuff.barrage` (anciennement « Barrage »)

Application Android : un **VPN local** qui filtre le DNS de tout le téléphone
(Chrome, TikTok, Telegram, Twitter, navigateurs intégrés…) et **coupe tout
domaine porno**, plus un **verrou binaire** qui rend la désactivation
volontairement pénible.

## Principe

```
Appli (Chrome, TikTok, Telegram…)
        │  requête DNS
        ▼
┌─ Tunnel TUN (VpnService) ──────────────────────────────┐
│  1. La requête est lue depuis le tunnel                 │
│  2. Le nom de domaine est vérifié :                     │
│       • liste de domaines (sub.domaine.com → domaine)  │
│       • TLD entiers (xxx, sex, porn…)                   │
│       • mots-clés : porn, xxx, onlyfans, xnxx…          │
│  3a. BLOQUÉ  → réponse 0.0.0.0 + notification          │
│               « Site bloqué : pornhub.com »             │
│  3b. AUTORISÉ → relais vers le résolveur amont          │
│                 (1.1.1.1 / 9.9.9.9, socket protégée)    │
└─────────────────────────────────────────────────────────┘
        │
        ▼
  Rien d'autre ne transite : le tunnel ne route QUE le port DNS
  (10.111.222.53). Aucun paquet n'est envoyé ailleurs,
  aucun contenu n'est inspecté, tout reste sur l'appareil.
```

## Le verrou binaire

1. Au premier lancement, tu choisis un **code court** (ex. `4839`).
2. Il est converti en **code binaire** : `4839 → 1001011100111`.
3. Seul le hachage SHA-256 de ce code binaire est stocké (jamais en clair).
4. Pour **couper la protection** ou suivre la procédure de désinstallation, il
   faut retaper le **long code binaire** — volontairement long et chiant, pour
   te laisser le temps de te calmer.

## Icône masquée (usage solo)

Le bouton « Masquer l'icône de l'app » désactive l'`activity-alias` du
lanceur : l'icône disparaît de tous les lanceurs, le VPN continue en
arrière-plan. Pour rouvrir l'appli :

- **la notification** « Protection active » (entrée garantie) ;
- **le code secret** `*#*#4839#*#*` dans le composeur (selon le composeur) ;
- Réafficher l'icône depuis l'écran de l'app.

Après un **reboot**, le récepteur `BootReceiver` relance automatiquement la
protection si elle était active (`protection_wanted`). L'appli reste visible
dans Réglages → Applications, et l'icône VPN + la notification sont imposés
par le système : c'est le prix d'un vrai VPN local.

## Verrou de désinstallation (niveau 1 — actif)

Le bouton « Verrou de désinstallation » active le `DeviceAdminReceiver` :
Android refuse alors toute désinstallation d'Umbra
(`DELETE_FAILED_DEVICE_POLICY_MANAGER`), même via `adb uninstall`, tant que
l'admin n'a pas été désactivé dans Réglages → Applications → Accès spécial.
La procédure de retrait ne s'affiche dans l'app qu'après saisie du code
binaire.

**Niveau 2 (device owner)** : posable via
`adb shell dpm set-device-owner com.freebuff.barrage/com.freebuff.barrage.admin.BarrageAdminReceiver`
— Android refuse si des comptes sont connectés (cas de ce téléphone : 9
comptes). Une fois posé, `BarrageAdminReceiver` applique automatiquement
`setUninstallBlocked` (désinstallation bloquée) +
`DISALLOW_FACTORY_RESET` ; retrait uniquement par câble avec
`dpm remove-active-admin`.

## Construire

Prérequis : **Android Studio** (Ladybug ou + récent) avec SDK 34, JDK 17+.

```bash
# Option 1 : ouvrir le dossier dans Android Studio → Run ▶
# Option 2 : ligne de commande (JDK 17+ ; SDK 34 via local.properties)
./gradlew assembleDebug               # APK : app/build/outputs/apk/debug/app-debug.apk
./gradlew test                        # tests unitaires (DNS, verrou, filtrage)
```

Tests unitaires : `CodeLockTest`, `DnsPacketTest`, `IpPacketTest`,
`DomainBlocklistTest` — ils vérifient la conversion `4839 → 1001011100111`,
le parseur DNS, les paquets IP/UDP et toutes les règles de filtrage.

## La liste de blocage

- **Au démarrage** : `app/src/main/assets/blocklist.txt` (liste de départ :
  TLD entiers + sites connus). Les **mots-clés sont dans le code** et
  s'appliquent même sans liste.
- **Depuis l'app** : bouton « Mettre à jour la liste » → télécharge une liste
  distante (fichier hosts, AdGuard/EasyList ou domaines simples, gzip accepté),
  l'écrit sur disque et recharge le service. Liste par défaut :
  `StevenBlack/hosts` variante « porn » (~2 Mo de domaines).
- **Formats acceptés** : `domaine.com`, `0.0.0.0 domaine.com`,
  `||domaine.com^$…`, exceptions `@@domaine.com`, commentaires `!` et `#`.
- **Passer à « 2 millions de domaines »** : remplace l'URL dans
  `BlocklistRepository.DEFAULT_URL` (ou dans le dialogue de mise à jour) par la
  liste de ton choix. Mémoire : ≈ 40-60 Mo de RAM pour 2 M d'entrées brutes ;
  les listes dédoublonnées en domaines de base occupent 10× moins.

## Limites connues (à lire)

- **DNS chiffré (DoH/DoT)** : un navigateur ou un « DNS privé » configuré sur
  l'appareil contourne le filtre. Garde Réglages → Réseau → DNS privé sur
  *Désactivé* / *Automatique*. Conseillé dans l'écran « Aide » de l'app.
- **IPv6 et adresses IP en dur** : seul le nom de domaine est filtré.
- **Mots-clés dans l'URL après « / »** : invisibles au niveau DNS (il faudrait
  un proxy HTTP — voir roadmap).
- **Désinstallation** : Android n'autorise personne à bloquer réellement la
  désinstallation d'une app. Le verrou binaire est une **barrière
  volontaire** (il faut passer par l'app et retaper le code), pas une sécurité
  inviolable. Sur un appareil géré (MDM/Device Owner), la protection peut être
  rendue bien plus forte.
- **iOS** : impossible avec les mêmes primitives (pas de VpnService
  équivalent accessible).

## Structure du projet

```
app/src/main/java/com/freebuff/barrage/
├── MainActivity.kt              # UI : statut, toggle verrouillé, stats, aide
├── LauncherIcon.kt              # masquage de l'icône (activity-alias)
├── BootReceiver.kt              # relance la protection au reboot
├── SecretCodeReceiver.kt        # entrée secrète *#*#4839#*#*
├── admin/BarrageAdminReceiver.kt# verrou de désinstallation (admin / DO)
├── Prefs.kt                     # verrou, compteurs, historique
├── lock/CodeLock.kt             # 4839 → 1001011100111, SHA-256, vérification
├── filter/DomainBlocklist.kt    # domaines + TLD + mots-clés + exceptions
├── filter/BlocklistRepository.kt# chargement asset/fichier + mise à jour HTTP
├── dns/DnsPacket.kt             # parseur DNS + réponse de blocage 0.0.0.0
└── vpn/
    ├── IpPacket.kt              # lecture/écriture IPv4+UDP du tunnel
    └── LocalVpnService.kt       # le VPN local (TUN, proxy DNS, notifications)
```

## Roadmap

- **Page « Site bloqué » réelle** : adresse factice (198.18.0.0/15) routée dans
  le TUN + mini serveur TCP → le navigateur affiche une page au lieu d'une
  erreur réseau.
- Inspection SNI/HTTP pour les mots-clés dans les chemins d'URL.
- Protection Device Admin / mode kiosque pour appareils gérés.
- Quotidien de blocage, mode « invité », export/import du verrou.
