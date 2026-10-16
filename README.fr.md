# Eirbone — partage de fichiers en pair-à-pair (tracker C et pairs Java)

*[English version](README.md)*

Un système de partage de fichiers en pair-à-pair inspiré de BitTorrent. Un **tracker écrit en C** indexe quel pair possède quel fichier. Les **pairs, écrits en Java**, s'échangent les fichiers morceau par morceau et téléchargent depuis plusieurs pairs en parallèle. Chaque pair sert aussi une petite **interface web** pour rechercher, télécharger et partager des fichiers.

```
                ┌──────────────┐
   announce /   │   Tracker    │   look / getfile
   update /     │  (C, select) │
   stop         └──────▲───────┘
          ┌────────────┴────────────┐
   ┌──────┴──────┐   interested  ┌──┴──────────┐
   │   Pair A    │◄─────────────►│   Pair B    │
   │ (Java)      │  have/getpieces│ (Java)      │
   └──────▲──────┘               └─────────────┘
          │ HTTP (interface web + API REST)
      Navigateur
```

## Fonctionnalités

**Tracker (C)**
- Serveur TCP mono-processus qui multiplexe les clients avec `select()`
- Analyseur du protocole texte : `announce`, `look` (recherche avec critères comme `filename=` ou `filesize>=`), `getfile`, `update`, `stop`
- Index des fichiers en mémoire (nom, taille, taille des morceaux, clé MD5 → liste des pairs)
- Thread de nettoyage en arrière-plan qui retire les pairs qui n'envoient plus de mises à jour
- Port, nombre maximal de clients, verbosité des logs et délai d'expiration configurables (`config.ini`)

**Pair (Java)**
- Annonce ses fichiers partagés au tracker et lui envoie des mises à jour périodiques
- Fichiers découpés en morceaux et identifiés par leur empreinte MD5 ; disponibilité suivie par une **buffer map** (bitmap encodée en Base64)
- Protocole entre pairs : `interested` → `have <hash> <buffermap>` → `getpieces`
- **Téléchargements parallèles** depuis plusieurs pairs grâce à un pool de threads
- Serveur HTTP embarqué (`com.sun.net.httpserver`) avec une API REST (`/api/files`, `/api/download`, `/api/upload`, `/api/peers`, ...) et une interface web monopage qui affiche la progression des téléchargements

## Compilation et exécution

Prérequis : `gcc`, `make`, JDK 11 ou plus récent.

```bash
make                 # compile ./tracker et les classes Java dans build/
make run-tracker     # terminal 1 : tracker sur le port 9041
make run-peer        # terminal 2 : pair (port P2P 3331, interface web sur http://localhost:4331)
```

Pour lancer d'autres pairs sur la même machine, copiez `src/peer/config.ini`, changez les ports puis lancez
`java -cp build src.peer.MainPeer chemin/vers/config.ini`.

## Structure du projet

```
src/
├── tracker/   Tracker C : boucle select(), analyseur du protocole, index des fichiers, logs, configuration
├── peer/      Pair Java : client du tracker, serveur P2P, gestion des téléchargements, API HTTP, interface web
└── file/      Morceaux, buffer maps et métadonnées des fichiers
```

## Contexte

Projet de réseaux à l'[ENSEIRB-MATMECA](https://enseirb-matmeca.bordeaux-inp.fr/) (Bordeaux INP).
