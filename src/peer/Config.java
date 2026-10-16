package src.peer;

import java.io.*;
import java.util.Properties;

public class Config {
    private final Properties props = new Properties();

    public Config(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String currentSection = "";
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
                if (line.startsWith("[") && line.endsWith("]")) {
                    currentSection = line.substring(1, line.length() - 1).trim();
                } else if (line.contains("=")) {
                    String[] parts = line.split("=", 2);
                    props.setProperty(currentSection + "." + parts[0].trim(), parts[1].trim());
                }
            }
        }
    }

    public String get(String section, String key) {
        return props.getProperty(section + "." + key);
    }

    public String getOrDefault(String section, String key, String fallback) {
        String v = get(section, key);
        return v == null ? fallback : v;
    }

    public int getInt(String section, String key) {
        String v = get(section, key);
        if (v == null) {
            throw new IllegalStateException("Configuration manquante : [" + section + "] " + key);
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Configuration invalide pour [" + section + "] "
                                            + key + " = '" + v + "'", e);
        }
    }

    public int getIntOrDefault(String section, String key, int fallback) {
        String v = get(section, key);
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            System.err.println("[config] Valeur non numérique pour " + section + "." + key
                               + " = '" + v + "', valeur par défaut " + fallback);
            return fallback;
        }
    }
}
