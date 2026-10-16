package src.peer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import src.file.*;

public class PeerServer implements Runnable {
    private final int listenPort; // Port sur lequel ce serveur(peer!) ouvre son ServerSocketChannel
    private final PeerNode peer; // L'objet Peer qui possède ce serveur
    private Selector selector; // L'objet NIO qui fait le select()
    private ServerSocketChannel serverChannel; // Socket serveur
    private volatile boolean running; // Le flag d'arrêt (volatile pour la relire dépuis la mémoire)

    private static final int MAX_LINE_BYTES = 64 * 1024;

    private static class ClientState {
        ByteBuffer readBuffer = ByteBuffer.allocate(4096);
        StringBuilder accumulator = new StringBuilder();
        Queue<ByteBuffer> writeQueue = new LinkedList<>();
    }

    public PeerServer(int listenPort, PeerNode peer) {
        this.listenPort = listenPort;
        this.peer = peer;
    }

    // la fonction qui lance le serveur
    public void start() {
        running = true;
        new Thread(this, "PeerServer -" + listenPort).start(); // on lance le thread du serveur
    }

    public void stop() {
        running = false;
        if (selector != null)
            selector.wakeup();
    }

    @Override
    public void run() {
        try {
            selector = Selector.open();
            serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(listenPort));
            serverChannel.configureBlocking(false);
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            System.out.println("[server] écoute sur port " + listenPort);

            while (running) {
                selector.select();
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();

                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    if (!key.isValid())
                        continue;

                    try {
                        if (key.isAcceptable())
                            handleAccept(key);
                        else if (key.isReadable())
                            handleRead(key);
                        else if (key.isWritable())
                            handleWrite(key);
                    } catch (IOException e) {
                        System.err.println("[server] erreur client : " + e.getMessage());
                        closeKey(key);
                    }
                }
            }
        } catch (IOException e) {
            if (running)
                e.printStackTrace();
        } finally {
            try {
                if (serverChannel != null)
                    serverChannel.close();
            } catch (IOException ignored) {
            }
            try {
                if (selector != null)
                    selector.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeKey(SelectionKey key) {
        try {
            key.channel().close();
        } catch (IOException ignored) {
        }
        key.cancel();
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel server = (ServerSocketChannel) key.channel();
        SocketChannel client = server.accept();

        if (client == null)
            return;

        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ, new ClientState());

        System.out.println("[server] connexion de " + client.getRemoteAddress());
    }

    private void handleWrite(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        while (!state.writeQueue.isEmpty()) {
            ByteBuffer buff = state.writeQueue.peek();
            channel.write(buff);

            if (buff.hasRemaining())
                return; // socket saturée
            state.writeQueue.poll(); // retirer le buffer terminé
        }

        // plus rien a ecrire : on retire l'interet OP_WRITE
        key.interestOps(key.interestOps() & ~SelectionKey.OP_WRITE);
    }

    private void handleRead(SelectionKey key) throws IOException {
        SocketChannel channel = (SocketChannel) key.channel();
        ClientState state = (ClientState) key.attachment();

        int bytesRead = channel.read(state.readBuffer);
        if (bytesRead == -1) {
            closeKey(key);
            return;
        }

        state.readBuffer.flip();
        state.accumulator.append(StandardCharsets.UTF_8.decode(state.readBuffer));
        state.readBuffer.clear();


        if (state.accumulator.length() > MAX_LINE_BYTES) {
            System.err.println("[server] message trop long, fermeture client");
            closeKey(key);
            return;
        }

        String accumulated = state.accumulator.toString();
        int lastNewline = accumulated.lastIndexOf('\n');
        if (lastNewline == -1)
            return;

        String complete = accumulated.substring(0, lastNewline);
        state.accumulator = new StringBuilder(accumulated.substring(lastNewline + 1));

        String[] messages = complete.split("\n");
        for (String msg : messages) {
            msg = msg.trim();
            if (msg.isEmpty())
                continue;

            if (msg.startsWith("getpieces")) {
                byte[] response = handleGetPieces(msg);
                if (response != null) {
                    state.writeQueue.add(ByteBuffer.wrap(response));
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            } else {
                String response = processMessage(msg);
                if (response != null) {
                    state.writeQueue.add(ByteBuffer.wrap(response.getBytes(StandardCharsets.UTF_8)));
                    key.interestOps(key.interestOps() | SelectionKey.OP_WRITE);
                }
            }
        }
    }

    // Lit le message et répond
    private String processMessage(String msg) {
        String[] parts = msg.split(" ");
        String command = parts[0];

        switch (command) {
            case "interested":
                return handleInterested(parts);
            case "have":
                return handleHave(parts);
            default:
                return null;
        }
    }

    // chercher un fichier par son hash dans les seeds et les leechs du peer
    private FileStorage findFileByHash(String hash) {
        for (FileStorage f : peer.getSeedFile()) {
            if (f.getHash().equals(hash))
                return f;
        }
        for (FileStorage f : peer.getLeechFiles()) {
            if (f.getHash().equals(hash))
                return f;
        }
        return null;
    }

    // interested <hash> -> on répond "have <hash> <buffermap_base64>\n"
    private String handleInterested(String[] parts) {
        if (parts.length < 2)
            return null;
        String hash = parts[1];

        FileStorage file = findFileByHash(hash);
        if (file == null) return null;

        BufferMap bm = peer.getBufferMap(file);
        if (bm == null) return null;

        String resp = "have " + hash + " " + bm.toBase64();
        System.out.println("[server] < interested " + hash);
        System.out.println("[server] > " + resp);
        return resp + "\n";
    }

    // have <hash> -> même réponse que interested
    private String handleHave(String[] parts) {
        return handleInterested(parts);
    }

    // getpieces <hash> [3 5 7]\n
    // -> data <hash> [3 1024 5 1024 7 1024]\n<binary payload>
    private byte[] handleGetPieces(String msg) {
        String[] head = msg.split("\\s+", 3);
        if (head.length < 3) return null;
        String hash = head[1];

        FileStorage file = findFileByHash(hash);
        if (file == null) return null;

        int open = msg.indexOf('[');
        int close = msg.indexOf(']');
        if (open < 0 || close < 0 || close <= open) return null;

        String indicesStr = msg.substring(open + 1, close).trim();
        if (indicesStr.isEmpty()) return null;
        String[] tokens = indicesStr.split("\\s+");

        int pieceSize = file.getPieceSize();
        BufferMap bm = peer.getBufferMap(file);

        List<byte[]> piecesData = new ArrayList<>();
        List<Integer> servedIndices = new ArrayList<>();

        for (String tok : tokens) {
            int idx;
            try {
                idx = Integer.parseInt(tok);
            } catch (NumberFormatException e) {
                continue;
            }

            /* Anti corruption : on ne sert PAS une pièce qu'on ne possède pas
               (sinon on enverrait des zéros lus depuis un trou de fichier). */
            if (bm == null || !bm.hasPiece(idx)) {
                continue;
            }

            try {
                byte[] raw = file.readPiece(idx);
                byte[] padded = raw;
                if (raw.length < pieceSize) {
                    padded = new byte[pieceSize];
                    System.arraycopy(raw, 0, padded, 0, raw.length);
                }
                piecesData.add(padded);
                servedIndices.add(idx);
            } catch (IOException e) {
                System.err.println("[server] erreur lecture pièce " + idx);
            }
        }

        if (piecesData.isEmpty()) return null;

        System.out.println("[server] < getpieces " + hash + " [" + indicesStr + "]");
        StringBuilder header = new StringBuilder("data ").append(hash).append(" [");
        for (int i = 0; i < servedIndices.size(); i++) {
            if (i > 0) header.append(" ");
            header.append(servedIndices.get(i)).append(" ").append(pieceSize);
        }
        header.append("]\n");

        System.out.println("[server] > " + header.toString().trim());
        byte[] headerBytes = header.toString().getBytes(StandardCharsets.UTF_8);
        int totalPayload = piecesData.size() * pieceSize;
        byte[] result = new byte[headerBytes.length + totalPayload];

        System.arraycopy(headerBytes, 0, result, 0, headerBytes.length);
        int offset = headerBytes.length;
        for (byte[] piece : piecesData) {
            System.arraycopy(piece, 0, result, offset, piece.length);
            offset += piece.length;
        }
        return result;
    }
}