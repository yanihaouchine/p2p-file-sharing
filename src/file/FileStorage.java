package src.file;

import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.io.RandomAccessFile;

public class FileStorage {

    private final String fileName;
    private final String filePath;
    private final long fileSize;
    private final int pieceSize;
    private final String hash;

    public FileStorage(String filePath) throws Exception {
        java.io.File file = new java.io.File(filePath);

        this.filePath = filePath;
        this.fileName = file.getName();
        this.fileSize = file.length();
        this.pieceSize = 1024;
        this.hash = computeMD5(filePath);
    }

    public FileStorage(String fileName, long fileSize, int pieceSize, String hash) {
        this.fileName = new java.io.File(fileName).getName();
        this.filePath = fileName;
        this.fileSize = fileSize;
        this.pieceSize = pieceSize;
        this.hash = hash;
    }

    public String getFileName() { return fileName; }
    public String getFilePath() { return filePath; }
    public long getFileSize()   { return fileSize; }
    public int getPieceSize()   { return pieceSize; }
    public String getHash()     { return hash; }

    public int getNbPieces() {
        if (fileSize == 0) return 0;
        return (int) Math.ceil((double) fileSize / pieceSize);
    }

    public byte[] readPiece(int index) throws IOException {
        if (index < 0 || index >= getNbPieces()) {
            throw new IOException("readPiece: index hors borne (" + index + ")");
        }
        long offset = (long) index * pieceSize;
        int length = (int) Math.min(pieceSize, fileSize - offset);

        byte[] data = new byte[length];
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "r")) {
            raf.seek(offset);
            raf.readFully(data);
        }
        return data;
    }

    public synchronized void writePiece(int index, byte[] data) throws IOException {
        if (index < 0 || index >= getNbPieces()) {
            throw new IOException("writePiece: index hors borne (" + index + ")");
        }
        long offset = (long) index * pieceSize;
        try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw")) {
            /* Pré-allouer le fichier à sa taille finale au premier write : évite
               les trous (lus comme zéros) qui corrompraient les pièces servies. */
            if (raf.length() < fileSize) {
                raf.setLength(fileSize);
            }
            raf.seek(offset);
            raf.write(data);
        }
    }

    public static String computeMD5(String path) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        try (FileInputStream fis = new FileInputStream(path)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md.update(buffer, 0, bytesRead);
            }
        }
        byte[] digest = md.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileStorage)) return false;
        FileStorage that = (FileStorage) o;
        return hash != null && hash.equals(that.hash);
    }

    @Override
    public int hashCode() {
        return hash == null ? 0 : hash.hashCode();
    }
}
