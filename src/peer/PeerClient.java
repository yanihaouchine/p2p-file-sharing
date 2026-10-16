package src.peer;

import java.io.*;
import java.net.*;

public class PeerClient {

    /** Crée la connexion avec le tracker */
    public static Socket connectToTracker(String hostname, int port) throws IOException {
        Socket socket = new Socket(hostname, port);
        System.out.println("[tracker] connecté " + hostname + ":" + port);
        return socket;
    }

    public static void sendMessage(Socket socket, String message) throws IOException {
        PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
        out.println(message);
    }

    public static String receiveMessage(Socket socket) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
        return in.readLine();
    }

    public static void closeConnection(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException e) {
            System.err.println("Erreur fermeture socket : " + e.getMessage());
        }
    }

    public static boolean isPortAvailable(int port) {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
