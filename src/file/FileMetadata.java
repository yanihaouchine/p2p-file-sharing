package src.file;

public class FileMetadata {
    private final String filename;
    private final long filesize;
    private final int pieceSize;
    private final String hash;

    public FileMetadata(String filename, long filesize, int pieceSize, String hash) {
        this.filename = filename;
        this.filesize = filesize;
        this.pieceSize = pieceSize;
        this.hash = hash;
    }

    public String getFilename() {
        return filename;
    }

    public long getFilesize() {
        return filesize;
    }

    public int getPieceSize() {
        return pieceSize;
    }

    public String getHash() {
        return hash;
    }

    @Override
    public String toString() {
        return filename + " " + filesize + " " +
                pieceSize + " " + hash;
    }

    public int getNbPieces() {
        return (int) Math.ceil((double) filesize / pieceSize);
    }

}