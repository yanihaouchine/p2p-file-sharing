package src.peer;

import java.util.ArrayList;
import java.util.List;

import src.file.FileMetadata;

public class TrackerResponseParser {

    public static final class PeerInfo {
        private final String ip;
        private final int port;

        public PeerInfo(String ip, int port) {
            this.ip = ip;
            this.port = port;
        }

        public String getIp() {
            return ip;
        }

        public int getPort() {
            return port;
        }

        @Override
        public String toString() {
            return ip + ":" + port;
        }
    }

    public static final class GetFileResponse {
        private final String hash;
        private final List<PeerInfo> peers;

        public GetFileResponse(String hash, List<PeerInfo> peers) {
            this.hash = hash;
            this.peers = peers;
        }

        public String gethash() {
            return hash;
        }

        public List<PeerInfo> getPeers() {
            return peers;
        }

        @Override
        public String toString() {
            return "hash=" + hash + ", peers=" + peers;
        }
    }

    public static void parse(String response) {
    }

    public static List<FileMetadata> parseFileList(String response) {
        List<FileMetadata> files = new ArrayList<>();

        if (response == null) {
            return files;
        }

        response = response.trim();

        if (!response.startsWith("list [") || !response.endsWith("]")) {
            throw new IllegalArgumentException("Format de reponse list invalide: " +
                    response);
        }
        String inside = response.substring("list [".length(), response.length()
                - 1).trim();

        if (inside.isEmpty()) {
            return files;
        }

        String[] tokens = inside.split("\\s+");

        if (tokens.length % 4 != 0) {
            throw new IllegalArgumentException(
                    "La reponse list doit contenir des groupes de 4 champs: " +
                            response);
        }

        for (int i = 0; i < tokens.length; i += 4) {
            String filename = tokens[i];
            long filesize = Long.parseLong(tokens[i + 1]);
            int pieceSize = Integer.parseInt(tokens[i + 2]);
            String hash = tokens[i + 3];

            files.add(new FileMetadata(filename, filesize, pieceSize, hash));
        }

        return files;
    }

    public static GetFileResponse parsePeers(String response) {
        if (response == null) {
            throw new IllegalArgumentException("Reponse peers nulle");
        }

        response = response.trim();

        if (!response.startsWith("peers ")) {
            throw new IllegalArgumentException("Format de reponse peers invalide: " + response);
        }

        int firstSpace = response.indexOf(' ');
        int bracketOpen = response.indexOf('[', firstSpace + 1);
        int bracketClose = response.lastIndexOf(']');

        if (bracketOpen < 0 || bracketClose < 0 || bracketClose < bracketOpen) {
            throw new IllegalArgumentException(
                    "Crochets invalides dans la reponse peers: " + response);
        }

        String hashPart = response.substring(firstSpace + 1,
                bracketOpen).trim();
        String peersPart = response.substring(bracketOpen + 1,
                bracketClose).trim();

        List<PeerInfo> peers = new ArrayList<>();

        if (!peersPart.isEmpty()) {
            String[] peerTokens = peersPart.split("\\s+");

            for (String peerToken : peerTokens) {
                int colonIndex = peerToken.lastIndexOf(':');

                if (colonIndex <= 0 || colonIndex == peerToken.length() - 1) {
                    throw new IllegalArgumentException("Peer invalide: " +
                            peerToken);
                }

                String ip = peerToken.substring(0, colonIndex);
                int port = Integer.parseInt(peerToken.substring(colonIndex +
                        1));

                peers.add(new PeerInfo(ip, port));
            }
        }

        return new GetFileResponse(hashPart, peers);
    }
}