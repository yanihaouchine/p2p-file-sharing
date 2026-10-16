package src.peer;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import src.file.*;

public class PeerRequestBuilder {

    private static final int CONNECT_TIMEOUT_MS  = 3000;
    private static final int READ_TIMEOUT_MS     = 10000;
    private static final int INTERESTED_TIMEOUT_MS = 5000;

    public static void lance(TrackerResponseParser.GetFileResponse response,
                             PeerNode localPeer,
                             FileMetadata metadata,
                             PeerManager peerManager) {
        lance(response, localPeer, metadata, peerManager, 5);
    }

    public static void lance(TrackerResponseParser.GetFileResponse response,
                             PeerNode localPeer,
                             FileMetadata metadata,
                             PeerManager peerManager,
                             int maxPeers) {

        String hash = response.gethash();
        List<TrackerResponseParser.PeerInfo> peers = response.getPeers();

        if (peers.isEmpty()) {
            System.out.println("[download] aucun peer disponible");
            return;
        }

        if (peers.size() > maxPeers) {
            peers = new ArrayList<>(peers).subList(0, maxPeers);
        }

        /* interested en parallèle. */
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, peers.size()));
        Map<TrackerResponseParser.PeerInfo, Socket> peerSockets = new ConcurrentHashMap<>();
        Map<TrackerResponseParser.PeerInfo, BufferMap> peerBufferMaps = new ConcurrentHashMap<>();

        List<Future<?>> futures = new ArrayList<>();
        for (TrackerResponseParser.PeerInfo p : peers) {
            futures.add(pool.submit(() -> {
                Socket socket = null;
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(p.getIp(), p.getPort()), CONNECT_TIMEOUT_MS);
                    socket.setSoTimeout(INTERESTED_TIMEOUT_MS);

                    BufferMap bm = sendInterested(socket, hash, metadata.getNbPieces());
                    if (bm != null) {
                        peerBufferMaps.put(p, bm);
                        peerSockets.put(p, socket);
                    } else {
                        socket.close();
                    }
                } catch (Exception e) {
                    System.err.println("[error] interested " + p + " : " + e.getMessage());
                    if (socket != null) try { socket.close(); } catch (IOException ignored) {}
                }
            }));
        }
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception ignored) {}
        }

        if (peerBufferMaps.isEmpty()) {
            System.out.println("[download] aucun peer disponible");
            pool.shutdown();
            return;
        }

        /* création du FileStorage local */
        String downloadDir = System.getProperty("user.home") + "/Téléchargements";
        File dir = new File(downloadDir);
        if (!dir.exists()) {
            downloadDir = System.getProperty("user.home") + "/Downloads";
            dir = new File(downloadDir);
        }
        if (!dir.exists()) dir.mkdirs();

        FileStorage localFile = new FileStorage(downloadDir + "/" + metadata.getFilename(),
                                                metadata.getFilesize(),
                                                metadata.getPieceSize(),
                                                metadata.getHash());
        localPeer.addLeechFile(localFile);

        /* assignation rarest-first */
        int nbPieces = metadata.getNbPieces();
        List<TrackerResponseParser.PeerInfo> peerList = new ArrayList<>(peerBufferMaps.keySet());
        BufferMap localBm = localPeer.getBufferMap(localFile);

        Map<TrackerResponseParser.PeerInfo, List<Integer>> assignments = new ConcurrentHashMap<>();
        for (TrackerResponseParser.PeerInfo p : peerList) {
            assignments.put(p, Collections.synchronizedList(new ArrayList<>()));
        }

        int[] rarity = new int[nbPieces];
        for (int piece = 0; piece < nbPieces; piece++) {
            for (BufferMap bm : peerBufferMaps.values()) {
                if (bm.hasPiece(piece)) rarity[piece]++;
            }
        }

        List<Integer> missingPieces = new ArrayList<>();
        for (int piece = 0; piece < nbPieces; piece++) {
            if (!localBm.hasPiece(piece) && rarity[piece] > 0) {
                missingPieces.add(piece);
            }
        }
        missingPieces.sort(Comparator.comparingInt(p -> rarity[p]));

        for (int piece : missingPieces) {
            TrackerResponseParser.PeerInfo bestPeer = null;
            int minLoad = Integer.MAX_VALUE;
            for (TrackerResponseParser.PeerInfo peer : peerList) {
                if (peerBufferMaps.get(peer).hasPiece(piece)) {
                    int load = assignments.get(peer).size();
                    if (load < minLoad) { minLoad = load; bestPeer = peer; }
                }
            }
            if (bestPeer != null) assignments.get(bestPeer).add(piece);
        }

        /* téléchargement parallèle */
        Set<Integer> failedPieces = ConcurrentHashMap.newKeySet();
        List<Future<?>> dlFutures = new ArrayList<>();
        for (Map.Entry<TrackerResponseParser.PeerInfo, List<Integer>> entry : assignments.entrySet()) {
            TrackerResponseParser.PeerInfo p = entry.getKey();
            List<Integer> pieces = entry.getValue();
            if (pieces.isEmpty()) continue;

            Socket socket = peerSockets.get(p);
            if (socket == null || socket.isClosed()) continue;

            dlFutures.add(pool.submit(() -> {
                Set<Integer> failed = downloadPieces(socket, hash, pieces, localFile,
                                                     localPeer, metadata.getFilesize());
                failedPieces.addAll(failed);
            }));
        }
        for (Future<?> f : dlFutures) {
            try { f.get(); } catch (Exception ignored) {}
        }

        /* retry des pièces échouées sur d'autres peers */
        if (!failedPieces.isEmpty()) {
            System.out.println("[download] retry " + failedPieces.size() + " pièces...");
            retryFailed(failedPieces, peerBufferMaps, peerSockets, hash, localFile,
                        localPeer, metadata.getFilesize(), pool);
        }

        /* fermeture des sockets et notification tracker. */
        for (Socket s : peerSockets.values()) {
            try { s.close(); } catch (IOException ignored) {}
        }
        pool.shutdown();

        try {
            peerManager.sendAnnounce();
        } catch (IOException e) {
            System.err.println("[error] sendAnnounce : " + e.getMessage());
        }
    }

    private static void retryFailed(Set<Integer> failedPieces,
                                    Map<TrackerResponseParser.PeerInfo, BufferMap> peerBufferMaps,
                                    Map<TrackerResponseParser.PeerInfo, Socket> peerSockets,
                                    String hash, FileStorage localFile, PeerNode localPeer,
                                    long fileSize, ExecutorService pool) {
        for (Integer piece : new ArrayList<>(failedPieces)) {
            for (Map.Entry<TrackerResponseParser.PeerInfo, BufferMap> e : peerBufferMaps.entrySet()) {
                if (!e.getValue().hasPiece(piece)) continue;
                Socket s = peerSockets.get(e.getKey());
                if (s == null || s.isClosed()) continue;
                Set<Integer> stillFailed = downloadPieces(s, hash, Collections.singletonList(piece),
                                                          localFile, localPeer, fileSize);
                if (stillFailed.isEmpty()) {
                    failedPieces.remove(piece);
                    break;
                }
            }
        }
        if (!failedPieces.isEmpty()) {
            System.err.println("[error] pièces non récupérables : " + failedPieces);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if ((char) b == '\n') break;
            sb.append((char) b);
        }
        if (b == -1 && sb.length() == 0) throw new EOFException("connexion fermée");
        return sb.toString().trim();
    }

    private static BufferMap sendInterested(Socket socket, String hash, int nbPieces) {
        try {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            String msg = "interested " + hash;
            System.out.println("< " + msg + "  [" + socket.getRemoteSocketAddress() + "]");
            out.write((msg + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            String response = readLine(in);
            if (response == null || !response.startsWith("have ")) return null;
            System.out.println("> " + response + "  [" + socket.getRemoteSocketAddress() + "]");

            String[] parts = response.split(" ");
            if (parts.length < 3) return null;

            return BufferMap.fromBase64(parts[2], nbPieces);
        } catch (Exception e) {
            System.err.println("[error] interested : " + e.getMessage());
            return null;
        }
    }

    /** Renvoie le set des pièces qu'on n'a PAS pu récupérer (pour retry ailleurs). */
    private static Set<Integer> downloadPieces(Socket socket, String hash,
                                                List<Integer> pieces, FileStorage localFile,
                                                PeerNode localPeer, long fileSize) {
        Set<Integer> failed = new HashSet<>(pieces);
        try {
            socket.setSoTimeout(READ_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            InputStream rawIn = socket.getInputStream();

            StringBuilder req = new StringBuilder("getpieces " + hash + " [");
            for (int i = 0; i < pieces.size(); i++) {
                if (i > 0) req.append(" ");
                req.append(pieces.get(i));
            }
            req.append("]");
            System.out.println("< " + req + "  [" + socket.getRemoteSocketAddress() + "]");
            out.write((req + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            String header = readLine(rawIn);
            if (!header.startsWith("data ")) {
                System.err.println("[error] réponse inattendue : " + header);
                return failed;
            }
            System.out.println("> " + header + "  [" + socket.getRemoteSocketAddress() + "]");

            int open = header.indexOf('[');
            int close = header.indexOf(']');
            if (open < 0 || close < 0 || close <= open) return failed;

            String inside = header.substring(open + 1, close).trim();
            if (inside.isEmpty()) return failed;
            String[] tokens = inside.split("\\s+");

            List<int[]> pieceDescriptors = new ArrayList<>();
            for (int i = 0; i + 1 < tokens.length; i += 2) {
                pieceDescriptors.add(new int[]{
                    Integer.parseInt(tokens[i]),
                    Integer.parseInt(tokens[i + 1])
                });
            }

            int pieceSize = localFile.getPieceSize();
            int totalPieces = localFile.getNbPieces();

            for (int[] desc : pieceDescriptors) {
                int pieceIndex = desc[0];
                int chunkSize = desc[1];

                byte[] buffer = rawIn.readNBytes(chunkSize);
                if (buffer.length < chunkSize) {
                    System.err.println("[error] données tronquées pièce " + pieceIndex);
                    return failed;
                }

                int actualSize;
                if (pieceIndex == totalPieces - 1) {
                    actualSize = (int)(fileSize % pieceSize);
                    if (actualSize == 0) actualSize = pieceSize;
                } else {
                    actualSize = pieceSize;
                }

                byte[] actual = Arrays.copyOf(buffer, actualSize);
                localFile.writePiece(pieceIndex, actual);
                localPeer.markSegmentDownloaded(localFile, pieceIndex);
                failed.remove(pieceIndex);
            }

        } catch (Exception e) {
            System.err.println("[error] downloadPieces : " + e.getMessage());
        }
        return failed;
    }
}
