package src.peer;

import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import src.file.*;

public class PeerNode {
    private static int nbPeerNodes = 0;
    private final int PeerNodeId;
    private String addressStringIp;
    private int listenPort;

    /* CopyOnWriteArrayList et ConcurrentHashMap pour la lecture concurrente
       depuis PeerServer / HttpApiServer / thread updater. */
    private final List<FileStorage> seedFile = new CopyOnWriteArrayList<>();
    private final Map<FileStorage, BufferMap> leechFiles = new ConcurrentHashMap<>();

    public PeerNode() {
        synchronized (PeerNode.class) { nbPeerNodes++; this.PeerNodeId = nbPeerNodes; }
    }

    public PeerNode(String addressStringIp, int listenPort) {
        this();
        this.addressStringIp = addressStringIp;
        this.listenPort = listenPort;
    }

    public PeerNode(String addressStringIp, int listenPort, List<FileStorage> seedFile) {
        this(addressStringIp, listenPort);
        if (seedFile != null) this.seedFile.addAll(seedFile);
    }

    public int getPeerNodeId() { return PeerNodeId; }
    public String getAddressStringIp() { return addressStringIp; }
    public void setAddressStringIp(String addressStringIp) { this.addressStringIp = addressStringIp; }
    public int getListenPort() { return listenPort; }
    public void setListenPort(int listenPort) { this.listenPort = listenPort; }


    public List<FileStorage> getSeedFile() {
        return new ArrayList<>(seedFile);
    }

    public void setSeedFile(List<FileStorage> newList) {
        seedFile.clear();
        if (newList != null) seedFile.addAll(newList);
    }

    public void addSeedFile(FileStorage file) {
        if (!seedFile.contains(file)) seedFile.add(file);
    }

    public List<FileStorage> getLeechFiles() {
        return new ArrayList<>(leechFiles.keySet());
    }

    public void addLeechFile(FileStorage file) {
        leechFiles.put(file, new BufferMap(file.getNbPieces()));
    }

    public BufferMap getBufferMap(FileStorage file) {
        if (seedFile.contains(file)) {
            return new BufferMap(file.getNbPieces(), true);
        }
        return leechFiles.get(file);
    }

    /** Marque une pièce reçue. Si le fichier devient complet et que le MD5 vérifie,
        il bascule en seed. Si le MD5 échoue, le fichier est rejeté et supprimé. */
    public void markSegmentDownloaded(FileStorage file, int segmentIndex) {
        BufferMap bm = leechFiles.get(file);
        if (bm == null) return;

        synchronized (bm) {
            bm.setPiece(segmentIndex);
            if (!bm.isComplete()) return;
        }

        /* Vérification MD5 finale — un peer malveillant ou des trous de pièces
           peuvent corrompre le fichier, on ne devient pas seeder d'un fichier corrompu. */
        try {
            String actualMd5 = FileStorage.computeMD5(file.getFilePath());
            if (!actualMd5.equalsIgnoreCase(file.getHash())) {
                System.err.println("[error] MD5 invalide " + file.getFileName()
                                   + " attendu=" + file.getHash() + " obtenu=" + actualMd5);
                leechFiles.remove(file);
                new java.io.File(file.getFilePath()).delete();
                return;
            }
        } catch (Exception e) {
            System.err.println("[error] vérification MD5 : " + e.getMessage());
            return;
        }

        leechFiles.remove(file);
        if (!seedFile.contains(file)) seedFile.add(file);
        System.out.println("[download] " + file.getFileName() + " complet, ajouté aux seeds");
    }
}
