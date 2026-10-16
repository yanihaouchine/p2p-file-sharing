package src.peer;

import java.io.*;
import java.net.*;

public class MainPeer {
    public static void main(String[] args) {
        String configPath = args.length > 0 ? args[0] : "src/peer/config.ini";
        try {
            Config config = new Config(configPath);

            String trackerHost = config.get("tracker", "host");
            int trackerPort   = config.getInt("tracker", "port");
            String peerHost   = config.get("peer", "host");
            int peerPort      = config.getInt("peer", "port");
            int webPort       = config.getInt("web", "port");
            int updateIntervalSec = config.getIntOrDefault("peer", "update_interval", 30);

            BufferedReader userInput = new BufferedReader(new InputStreamReader(System.in));

            while (true) {
                try (ServerSocket test = new ServerSocket(peerPort)) {
                    break;
                } catch (IOException e) {
                    System.out.println("Port peer " + peerPort + " déjà utilisé.");
                    System.out.print("Choisis un autre port peer : ");
                    String line = userInput.readLine();
                    if (line == null) {
                        System.err.println("Pas d'entrée disponible, abandon.");
                        return;
                    }
                    try {
                        peerPort = Integer.parseInt(line.trim());
                    } catch (NumberFormatException nfe) {
                        System.out.println("Numéro de port invalide.");
                    }
                }
            }

            System.out.println("[peer] " + peerHost + ":" + peerPort);
            System.out.println("[tracker] " + trackerHost + ":" + trackerPort);

            PeerNode peer = new PeerNode(peerHost, peerPort);
            PeerServer server = new PeerServer(peerPort, peer);
            server.start();

            Socket socket;
            try {
                socket = PeerClient.connectToTracker(trackerHost, trackerPort);
            } catch (IOException e) {
                System.err.println("Tracker injoignable : " + e.getMessage());
                System.err.println("Le peer ne peut pas démarrer sans tracker.");
                server.stop();
                return;
            }
            PeerManager peerManager = new PeerManager(peer, socket, userInput,
                                                     trackerHost, trackerPort);
            try {
                peerManager.sendUpdate();
            } catch (IOException e) {
                System.err.println("Update initial échoué : " + e.getMessage());
            }

            final int updateIntervalMs = updateIntervalSec * 1000;
            Thread updater = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        Thread.sleep(updateIntervalMs);
                        peerManager.sendUpdate();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (IOException e) {
                        System.err.println("Erreur update périodique : " + e.getMessage());
                    }
                }
            }, "tracker-updater");
            updater.setDaemon(true);
            updater.start();

            HttpApiServer webServer = new HttpApiServer(peerManager, peer);
            try {
                webServer.start(webPort);
            } catch (IOException e) {
                System.err.println("[error] web : " + e.getMessage());
            }

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                server.stop();
                webServer.stop();
                try { socket.close(); } catch (IOException ignored) {}
            }));

            while (true) {
                int choix = peerManager.showMenu();
                if (choix == -1) System.out.println("Choix invalide.");
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
