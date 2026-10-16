package src.peer;

import java.util.List;
import src.file.FileStorage;

public class TrackerRequestBuilder {
    public static String buildAnnounce(int port, List<FileStorage> seedFiles,
            List<FileStorage> leechFiles) {
        StringBuilder sb = new StringBuilder();

        sb.append("announce listen ").append(port);
        sb.append(" seed [");

        for (int i = 0; i < seedFiles.size(); i++) {
            FileStorage file = seedFiles.get(i);

            sb.append(file.getFileName()).append(" ")
                    .append(file.getFileSize()).append(" ")
                    .append(file.getPieceSize()).append(" ")
                    .append(file.getHash());

            if (i < seedFiles.size() - 1) {
                sb.append(" ");
            }
        }

        sb.append("] leech [");

        for (FileStorage file : leechFiles) {
            sb.append(file.getHash()).append(" ");
        }

        sb.append("]");

        return sb.toString();
    }

    public static String buildLook(String filename, String filesize) {
        StringBuilder sb = new StringBuilder();
        sb.append("look [");

        boolean hasPreviousCriterion = false;

        if (filename != null && !filename.trim().isEmpty()) {
            sb.append("filename=").append(filename.trim());
            hasPreviousCriterion = true;
        }

        if (filesize != null && !filesize.trim().isEmpty()) {
            if (hasPreviousCriterion) {
                sb.append(" ");
            }
            sb.append("filesize>=").append(filesize.trim());
        }

        sb.append("]");
        return sb.toString();
    }

    public static String buildGetFile(String fileHash) {
        return "getfile " + fileHash;
    }

    public static String buildUpdate(List<FileStorage> seedFiles, List<FileStorage> leechFiles) {
        StringBuilder sb = new StringBuilder();
        sb.append("update ");
        sb.append("seed [");
        for (FileStorage file : seedFiles) {
            sb.append(file.getHash()).append(" ");
        }
        sb.append("] leech [");
        for (FileStorage file : leechFiles) {
            sb.append(file.getHash()).append(" ");
        }
        sb.append("]");
        return sb.toString();
    }

}
