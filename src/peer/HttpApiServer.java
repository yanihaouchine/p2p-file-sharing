package src.peer;

import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.*;
import src.file.*;

public class HttpApiServer {
    private final PeerManager peerManager;
    private final PeerNode peer;
    private HttpServer server;
    private final Map<String, String> downloadStatuses = new java.util.concurrent.ConcurrentHashMap<>();

    /* Racine canonique pour le serveur statique : tout chemin résolu doit
       commencer par celle-ci, sinon path-traversal rejeté. */
    private final File staticRoot;
    private final File downloadsRoot;

    public HttpApiServer(PeerManager peerManager, PeerNode peer) {
        this.peerManager = peerManager;
        this.peer = peer;
        try {
            this.staticRoot = new File("src/peer/web").getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException("Impossible de canoniser src/peer/web", e);
        }
        File preferred = new File(System.getProperty("user.home"), "Téléchargements");
        if (!preferred.exists()) preferred = new File(System.getProperty("user.home"), "Downloads");
        if (!preferred.exists()) preferred.mkdirs();
        try {
            this.downloadsRoot = preferred.getCanonicalFile();
        } catch (IOException e) {
            throw new RuntimeException("Impossible de canoniser le dossier de téléchargements", e);
        }
    }

    public void start(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/files", this::handleFiles);
        server.createContext("/api/getfile", this::handleGetFile);
        server.createContext("/api/download", this::handleDownload);
        server.createContext("/api/upload", this::handleUpload);
        server.createContext("/api/peers", this::handlePeers);
        server.createContext("/", this::handleStatic);

        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        System.out.println("Interface web disponible sur http://localhost:" + port);
    }

    public void stop() {
        if (server != null) server.stop(0);
    }

    // GET /api/files?name=test&maxsize=5000
    private void handleFiles(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String query = ex.getRequestURI().getQuery();
        String nameFilter = getParam(query, "name");
        String maxSizeStr = getParam(query, "maxsize");

        String lookMsg = TrackerRequestBuilder.buildLook(nameFilter, maxSizeStr);
        String response;
        try {
            response = peerManager.sendToTracker(lookMsg);
        } catch (IOException e) {
            sendJson(ex, 502, "{\"error\":\"tracker injoignable\"}");
            return;
        }

        List<FileMetadata> files;
        try {
            files = TrackerResponseParser.parseFileList(response);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 502, "{\"error\":\"réponse tracker invalide\"}");
            return;
        }

        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < files.size(); i++) {
            FileMetadata f = files.get(i);
            if (i > 0) json.append(",");
            json.append("{\"filename\":\"").append(jsonEscape(f.getFilename()))
                .append("\",\"filesize\":").append(f.getFilesize())
                .append(",\"pieceSize\":").append(f.getPieceSize())
                .append(",\"hash\":\"").append(jsonEscape(f.getHash()))
                .append("\",\"nbPieces\":").append(f.getNbPieces())
                .append("}");
        }
        json.append("]");

        sendJson(ex, 200, json.toString());
    }

    // POST /api/download
    private void handleDownload(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String hash, filename;
        long filesize;
        int pieceSize;
        try {
            String body = new String(ex.getRequestBody().readAllBytes());
            hash      = jsonGet(body, "hash");
            filename  = jsonGet(body, "filename");
            filesize  = Long.parseLong(jsonGet(body, "filesize"));
            pieceSize = Integer.parseInt(jsonGet(body, "pieceSize"));
            if (hash.isEmpty() || filename.isEmpty() || pieceSize <= 0 || filesize <= 0) {
                throw new IllegalArgumentException("champs manquants");
            }
        } catch (Exception e) {
            sendJson(ex, 400, "{\"error\":\"requête invalide\"}");
            return;
        }

        if (!isSafeFilename(filename)) {
            sendJson(ex, 400, "{\"error\":\"nom de fichier invalide\"}");
            return;
        }

        String resp;
        try {
            resp = peerManager.sendToTracker(TrackerRequestBuilder.buildGetFile(hash));
        } catch (IOException e) {
            sendJson(ex, 502, "{\"error\":\"tracker injoignable\"}");
            return;
        }

        TrackerResponseParser.GetFileResponse gfr;
        try {
            gfr = TrackerResponseParser.parsePeers(resp);
        } catch (IllegalArgumentException e) {
            sendJson(ex, 502, "{\"error\":\"réponse tracker invalide\"}");
            return;
        }

        FileMetadata meta = new FileMetadata(filename, filesize, pieceSize, hash);
        downloadStatuses.put(hash, "downloading");

        new Thread(() -> {
            try {
                PeerRequestBuilder.lance(gfr, peer, meta, peerManager);
                downloadStatuses.put(hash, "done");
            } catch (Exception e) {
                downloadStatuses.put(hash, "error");
                e.printStackTrace();
            }
        }, "download-" + hash).start();

        sendJson(ex, 200, "{\"status\":\"started\",\"hash\":\"" + jsonEscape(hash) + "\"}");
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String hash = getParam(ex.getRequestURI().getQuery(), "hash");
        String status = downloadStatuses.getOrDefault(hash, "unknown");
        sendJson(ex, 200, "{\"status\":\"" + jsonEscape(status)
                          + "\",\"peerProgress\":{},\"recentPieces\":[]}");
    }

    // GET /api/peers?hash=...
    private void handlePeers(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        String hash = getParam(ex.getRequestURI().getQuery(), "hash");
        if (hash.isEmpty()) {
            sendJson(ex, 400, "{\"error\":\"hash manquant\"}");
            return;
        }

        try {
            String resp = peerManager.sendToTracker(TrackerRequestBuilder.buildGetFile(hash));
            TrackerResponseParser.GetFileResponse gfr = TrackerResponseParser.parsePeers(resp);

            StringBuilder json = new StringBuilder("{\"peers\":[");
            for (int i = 0; i < gfr.getPeers().size(); i++) {
                if (i > 0) json.append(",");
                json.append("\"").append(jsonEscape(gfr.getPeers().get(i).toString())).append("\"");
            }
            json.append("]}");
            sendJson(ex, 200, json.toString());
        } catch (Exception e) {
            sendJson(ex, 502, "{\"error\":\"" + jsonEscape(e.getMessage()) + "\"}");
        }
    }

    // POST /api/upload
    private void handleUpload(HttpExchange ex) throws IOException {
        addCors(ex);
        if ("OPTIONS".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(204, -1); return; }

        try {
            String contentType = ex.getRequestHeaders().getFirst("Content-Type");
            String savedPath;
            String filename;

            if (contentType != null && contentType.startsWith("multipart/form-data")) {
                byte[] body = ex.getRequestBody().readAllBytes();
                String[] split = contentType.split("boundary=");
                if (split.length < 2) {
                    sendJson(ex, 400, "{\"error\":\"multipart sans boundary\"}");
                    return;
                }
                String boundary = split[1].trim();
                MultipartData mp = parseMultipart(body, boundary);
                if (mp.filename == null || mp.data == null) {
                    sendJson(ex, 400, "{\"error\":\"multipart invalide\"}");
                    return;
                }
                filename = mp.filename;

                if (!isSafeFilename(filename)) {
                    sendJson(ex, 400, "{\"error\":\"nom de fichier invalide\"}");
                    return;
                }

                File tempBase = new File(System.getProperty("java.io.tmpdir"), "freewire_uploads");
                tempBase.mkdirs();
                File target = new File(tempBase, filename).getCanonicalFile();
                if (!target.toPath().startsWith(tempBase.getCanonicalFile().toPath())) {
                    sendJson(ex, 400, "{\"error\":\"chemin invalide\"}");
                    return;
                }
                savedPath = target.getAbsolutePath();

                try (FileOutputStream fos = new FileOutputStream(savedPath)) {
                    fos.write(mp.data);
                }
            } else {
                String body = new String(ex.getRequestBody().readAllBytes());
                savedPath = jsonGet(body, "path");
                if (savedPath.isEmpty()) {
                    sendJson(ex, 400, "{\"error\":\"path manquant\"}");
                    return;
                }
                File f = new File(savedPath);
                if (!f.exists() || !f.isFile()) {
                    sendJson(ex, 400, "{\"error\":\"fichier introuvable\"}");
                    return;
                }
                filename = f.getName();
            }

            FileStorage fs = new FileStorage(savedPath);
            peer.addSeedFile(fs);
            peerManager.sendAnnounce();
            sendJson(ex, 200, "{\"status\":\"ok\",\"filename\":\"" + jsonEscape(filename) + "\"}");
        } catch (Exception e) {
            sendJson(ex, 500, "{\"error\":\"" + jsonEscape(String.valueOf(e.getMessage())) + "\"}");
        }
    }

    private static class MultipartData {
        String filename;
        byte[] data;
    }

    private MultipartData parseMultipart(byte[] body, String boundary) {
        MultipartData result = new MultipartData();
        String bodyStr = new String(body);

        int fnIdx = bodyStr.indexOf("filename=\"");
        if (fnIdx >= 0) {
            int start = fnIdx + 10;
            int end = bodyStr.indexOf('"', start);
            if (end > start) result.filename = bodyStr.substring(start, end);
        }

        byte[] headerSep = "\r\n\r\n".getBytes();
        int dataStart = -1;
        for (int i = 0; i + headerSep.length <= body.length; i++) {
            boolean match = true;
            for (int j = 0; j < headerSep.length; j++) {
                if (body[i + j] != headerSep[j]) { match = false; break; }
            }
            if (match) { dataStart = i + headerSep.length; break; }
        }

        String boundaryEnd = "\r\n--" + boundary;
        byte[] endBytes = boundaryEnd.getBytes();
        int dataEnd = body.length - endBytes.length - 2;

        if (dataStart >= 0 && dataEnd > dataStart) {
            result.data = Arrays.copyOfRange(body, dataStart, dataEnd);
        }
        return result;
    }

    // Sert les fichiers statiques depuis src/peer/web/ — anti path-traversal.
    private void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) path = "/index.html";

        File requested = new File(staticRoot, path);
        File canonical;
        try {
            canonical = requested.getCanonicalFile();
        } catch (IOException e) {
            ex.sendResponseHeaders(404, -1);
            return;
        }

        if (!canonical.toPath().startsWith(staticRoot.toPath())) {
            ex.sendResponseHeaders(403, -1);
            return;
        }
        if (!canonical.exists() || !canonical.isFile()) {
            ex.sendResponseHeaders(404, -1);
            return;
        }

        byte[] bytes = Files.readAllBytes(canonical.toPath());
        String mime = path.endsWith(".html") ? "text/html" :
                      path.endsWith(".js")   ? "application/javascript" :
                      path.endsWith(".css")  ? "text/css" : "text/plain";
        ex.getResponseHeaders().set("Content-Type", mime);
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private void addCors(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type");
    }

    private void sendJson(HttpExchange ex, int code, String json) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "application/json");
        addCors(ex);
        byte[] bytes = json.getBytes();
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }

    private String getParam(String query, String key) {
        if (query == null) return "";
        for (String p : query.split("&")) {
            String[] kv = p.split("=", 2);
            if (kv[0].equals(key)) return kv.length > 1 ? kv[1] : "";
        }
        return "";
    }

    private String jsonGet(String json, String key) {
        String search = "\"" + key + "\":";
        int i = json.indexOf(search);
        if (i < 0) return "";
        i += search.length();
        while (i < json.length() && (json.charAt(i) == ' ')) i++;
        if (i >= json.length()) return "";
        if (json.charAt(i) == '"') {
            int end = json.indexOf('"', i + 1);
            if (end < 0) return "";
            return json.substring(i + 1, end);
        } else {
            int end = i;
            while (end < json.length() && ",}".indexOf(json.charAt(end)) < 0) end++;
            return json.substring(i, end).trim();
        }
    }

    private static String jsonEscape(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isSafeFilename(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.contains("/") || name.contains("\\")) return false;
        if (name.equals(".") || name.equals("..")) return false;
        if (name.contains("\0")) return false;
        return true;
    }

    // GET /api/getfile?filename=...
    private void handleGetFile(HttpExchange ex) throws IOException {
        addCors(ex);
        String filename = getParam(ex.getRequestURI().getQuery(), "filename");
        if (!isSafeFilename(filename)) {
            ex.sendResponseHeaders(400, -1);
            return;
        }

        File requested = new File(downloadsRoot, filename);
        File canonical;
        try {
            canonical = requested.getCanonicalFile();
        } catch (IOException e) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        if (!canonical.toPath().startsWith(downloadsRoot.toPath())
            || !canonical.exists() || !canonical.isFile()) {
            ex.sendResponseHeaders(404, -1);
            return;
        }

        byte[] bytes = Files.readAllBytes(canonical.toPath());
        ex.getResponseHeaders().set("Content-Type", "application/octet-stream");
        ex.getResponseHeaders().set("Content-Disposition",
            "attachment; filename=\"" + filename.replace("\"", "_") + "\"");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = ex.getResponseBody()) { out.write(bytes); }
    }
}
