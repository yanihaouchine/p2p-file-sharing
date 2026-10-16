# Eirbone — peer-to-peer file sharing (C tracker + Java peers)

![C](https://img.shields.io/badge/tracker-C-blue) ![Java](https://img.shields.io/badge/peer-Java-orange) ![TCP](https://img.shields.io/badge/protocol-TCP-lightgrey)

*[Version française](README.fr.md)*

A BitTorrent-like peer-to-peer file-sharing system. A **tracker written in C** indexes which peer holds which file; **peers written in Java** exchange files piece by piece, downloading from several peers in parallel. Each peer also serves a small **web interface** to search, download and share files.

```
                ┌──────────────┐
   announce /   │   Tracker    │   look / getfile
   update /     │  (C, select) │
   stop         └──────▲───────┘
          ┌────────────┴────────────┐
   ┌──────┴──────┐   interested  ┌──┴──────────┐
   │   Peer A    │◄─────────────►│   Peer B    │
   │ (Java)      │  have/getpieces│ (Java)      │
   └──────▲──────┘               └─────────────┘
          │ HTTP (web UI + REST API)
       Browser
```

## Features

**Tracker (C)**
- Single-process TCP server multiplexing clients with `select()`
- Text protocol parser: `announce`, `look` (search with criteria such as `filename=` or `filesize>=`), `getfile`, `update`, `stop`
- In-memory file index (filename, size, piece size, MD5 key → list of peers)
- Background cleaner thread removing peers that stopped sending updates
- Configurable port, client limit, log verbosity and timeout (`config.ini`)

**Peer (Java)**
- Announces its shared files to the tracker and sends periodic updates
- Files split into pieces and identified by their MD5 hash; availability tracked with a **buffer map** (Base64-encoded bitmap)
- Peer-to-peer protocol: `interested` → `have <hash> <buffermap>` → `getpieces`
- **Parallel downloads** from several peers through a thread pool
- Embedded HTTP server (`com.sun.net.httpserver`) with a REST API (`/api/files`, `/api/download`, `/api/upload`, `/api/peers`, ...) and a single-page web UI showing download progress

## Build & run

Requirements: `gcc`, `make`, JDK 11+.

```bash
make                 # builds ./tracker and the Java classes into build/
make run-tracker     # terminal 1: tracker on port 9041
make run-peer        # terminal 2: peer (P2P port 3331, web UI on http://localhost:4331)
```

To start more peers on the same machine, copy `src/peer/config.ini`, change the ports and run
`java -cp build src.peer.MainPeer path/to/config.ini`.

## Project structure

```
src/
├── tracker/   C tracker: select() loop, protocol parser, file index, logs, config
├── peer/      Java peer: tracker client, P2P server, download manager, HTTP API, web UI
└── file/      Pieces, buffer maps and file metadata
```

## Context

Networking project at [ENSEIRB-MATMECA](https://enseirb-matmeca.bordeaux-inp.fr/) (Bordeaux INP).
