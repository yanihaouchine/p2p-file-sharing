package src.file;

import java.util.Base64;

public class BufferMap {
    private final boolean[] pieces;
    private final int nbPieces;

    public BufferMap(int nbPieces) {
        if (nbPieces < 0) throw new IllegalArgumentException("nbPieces négatif");
        this.nbPieces = nbPieces;
        this.pieces = new boolean[nbPieces];
    }

    public BufferMap(int nbPieces, boolean allSet) {
        this(nbPieces);
        if (allSet) {
            for (int i = 0; i < nbPieces; i++) pieces[i] = true;
        }
    }

    public synchronized boolean hasPiece(int index) {
        if (index < 0 || index >= nbPieces) return false;
        return pieces[index];
    }

    public synchronized void setPiece(int index) {
        if (index >= 0 && index < nbPieces) pieces[index] = true;
    }

    public synchronized boolean isComplete() {
        for (int i = 0; i < nbPieces; i++) {
            if (!pieces[i]) return false;
        }
        return true;
    }

    public synchronized int getNbPieces() { return nbPieces; }

    public synchronized String toBase64() {
        byte[] bytes = new byte[(nbPieces + 7) / 8];
        for (int i = 0; i < nbPieces; i++) {
            if (pieces[i]) {
                int byteIndex = i / 8;
                int bitIndex  = 7 - (i % 8);
                bytes[byteIndex] |= (byte) (1 << bitIndex);
            }
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    /** Décode un buffermap base64. Bornes vérifiées : un peer malveillant ne peut
        plus crasher le client en envoyant un payload trop court. */
    public static BufferMap fromBase64(String encoded, int nbPieces) {
        BufferMap bm = new BufferMap(nbPieces);
        if (encoded == null || encoded.isEmpty()) return bm;

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("BufferMap base64 invalide", e);
        }

        int needed = (nbPieces + 7) / 8;
        if (bytes.length < needed) {
            throw new IllegalArgumentException("BufferMap trop court : " + bytes.length
                                               + " octets reçus, " + needed + " attendus");
        }

        for (int i = 0; i < nbPieces; i++) {
            int byteIndex = i / 8;
            int bitIndex  = 7 - (i % 8);
            bm.pieces[i] = (bytes[byteIndex] & (1 << bitIndex)) != 0;
        }
        return bm;
    }

    public synchronized int[] getMissingPiecesFrom(BufferMap other) {
        int count = 0;
        for (int i = 0; i < nbPieces; i++) {
            if (!pieces[i] && other.hasPiece(i)) count++;
        }
        int[] result = new int[count];
        int idx = 0;
        for (int i = 0; i < nbPieces; i++) {
            if (!pieces[i] && other.hasPiece(i)) result[idx++] = i;
        }
        return result;
    }
}
