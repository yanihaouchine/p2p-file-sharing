package src.peer;

import java.io.*;
import java.net.Socket;
import java.util.List;

import src.file.FileMetadata;
import src.file.FileStorage;

public class PeerManager {
    private final PeerNode peer;
    private final BufferedReader input;

    private final String trackerHost;
    private final int trackerPort;

    private Socket socket;
    private PrintWriter trackerOut;
    private BufferedReader trackerIn;

    private final Object trackerLock = new Object();

    public PeerManager(PeerNode peer, Socket socket, BufferedReader input) throws IOException {
        this(peer, socket, input,
             socket.getInetAddress().getHostAddress(), socket.getPort());
    }

    public PeerManager(PeerNode peer, Socket socket, BufferedReader input,
                       String trackerHost, int trackerPort) throws IOException {
        this.peer = peer;
        this.socket = socket;
        this.input = input;
        this.trackerHost = trackerHost;
        this.trackerPort = trackerPort;
        bindStreams(socket);
    }

    private void bindStreams(Socket s) throws IOException {
        this.trackerOut = new PrintWriter(s.getOutputStream(), true);
        this.trackerIn  = new BufferedReader(new InputStreamReader(s.getInputStream()));
    }

    public Socket getSocket() {
        return socket;
    }

    /** Reconnecte au tracker si la connexion a été coupée. Appelé sous trackerLock. */
    private void reconnectLocked() throws IOException {
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        socket = new Socket(trackerHost, trackerPort);
        bindStreams(socket);
        System.out.println("[tracker] reconnecté " + trackerHost + ":" + trackerPort);
    }

    public String sendToTracker(String message) throws IOException {
        synchronized (trackerLock) {
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    System.out.println("< " + message);
                    trackerOut.println(message);
                    if (trackerOut.checkError()) {
                        throw new IOException("trackerOut en erreur");
                    }
                    String response = trackerIn.readLine();
                    if (response == null) {
                        throw new IOException("EOF côté tracker");
                    }
                    System.out.println("> " + response);
                    return response;
                } catch (IOException e) {
                    if (attempt == 0) {
                        reconnectLocked();
                    } else {
                        throw e;
                    }
                }
            }
            throw new IOException("sendToTracker : abandon après reconnexion");
        }
    }

    public void sendAnnounce() throws IOException {
        String announce = TrackerRequestBuilder.buildAnnounce(
                peer.getListenPort(),
                peer.getSeedFile(),
                peer.getLeechFiles());
        String response = sendToTracker(announce);
        TrackerResponseParser.parse(response);
    }

    public void sendUpdate() throws IOException {
        String update = TrackerRequestBuilder.buildUpdate(
                peer.getSeedFile(),
                peer.getLeechFiles());
        String response = sendToTracker(update);
        TrackerResponseParser.parse(response);
    }

    public int showMenu() throws IOException {
        System.out.println("\nMenu:");
        System.out.println("1 - Upload");
        System.out.println("2 - Download");
        System.out.println("Entrez un choix: ");
        String line = input.readLine();
        if (line == null) return -1;

        try {
            int choix = Integer.parseInt(line.trim());
            if (choix == 1) {
                handleUpload();
            } else if (choix == 2) {
                System.out.print("Entrez le nom du fichier à télécharger: ");
                String fileName = input.readLine();
                System.out.print("Entrez la taille maximale: ");
                String size = input.readLine();
                handleDownload(fileName, size);
            }
            return choix;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void handleUpload() throws IOException {
        System.out.println("Entrez le chemin du fichier à uploader:");
        String filePath = input.readLine();
        if (filePath == null) return;
        File file = new File(filePath);
        if (!file.exists() || !file.isFile()) {
            System.out.println("Fichier invalide.");
            return;
        }
        FileStorage fileStorage;
        try {
            fileStorage = new FileStorage(file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("Erreur lors de la préparation du fichier: " + e.getMessage());
            return;
        }
        peer.addSeedFile(fileStorage);
        sendAnnounce();
    }

    public void handleDownload(String fileName, String fileSize) throws IOException {
        String response = sendToTracker(TrackerRequestBuilder.buildLook(fileName, fileSize));

        List<FileMetadata> files;
        try {
            files = TrackerResponseParser.parseFileList(response);
        } catch (IllegalArgumentException e) {
            System.out.println("Réponse tracker invalide : " + e.getMessage());
            return;
        }

        if (files.isEmpty()) {
            System.out.println("Aucun fichier trouve.");
            return;
        }

        System.out.println("Fichiers trouves :");
        for (int i = 0; i < files.size(); i++) {
            FileMetadata f = files.get(i);
            System.out.println(i + " - " + f.getFilename() + " " + f.getFilesize()
                               + " " + f.getPieceSize() + " " + f.getHash());
        }

        System.out.print("Entrez le numero du fichier a telecharger: ");
        String choice = input.readLine();
        int index;
        try {
            index = Integer.parseInt(choice == null ? "" : choice.trim());
        } catch (NumberFormatException e) {
            System.out.println("Choix invalide.");
            return;
        }

        if (index < 0 || index >= files.size()) {
            System.out.println("Choix invalide.");
            return;
        }

        FileMetadata selectedFile = files.get(index);
        System.out.println("Vous avez choisi de telecharger: " + selectedFile.getFilename());
        String hash = selectedFile.getHash();

        System.out.print("Confirmer le telechargement (oui/non): ");
        String confirm = input.readLine();
        if (confirm == null || !confirm.equalsIgnoreCase("oui")) {
            System.out.println("Telechargement annule.");
            return;
        }

        String downloadResponse = sendToTracker(TrackerRequestBuilder.buildGetFile(hash));
        TrackerResponseParser.GetFileResponse gfr;
        try {
            gfr = TrackerResponseParser.parsePeers(downloadResponse);
        } catch (IllegalArgumentException e) {
            System.out.println("Réponse tracker invalide : " + e.getMessage());
            return;
        }
        PeerRequestBuilder.lance(gfr, peer, selectedFile, this);

        String downloadDir = System.getProperty("user.home") + "/Téléchargements";
        if (!new File(downloadDir).exists()) {
            downloadDir = System.getProperty("user.home") + "/Downloads";
        }
        System.out.println("Fichier sauvegardé dans : " + downloadDir + "/" + selectedFile.getFilename());
    }
}
