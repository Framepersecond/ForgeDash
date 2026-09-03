package dash;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Simple YAML-like config loader for Fabric (no Bukkit dependency).
 * Supports dot-notation paths, nested sections, and list values.
 * Provides an API compatible with Bukkit's YamlConfiguration for easy porting.
 */
public class FabricConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("FabricConfig");

    private final Path configPath;
    private final LinkedHashMap<String, Object> values = new LinkedHashMap<>();

    public FabricConfig(Path configPath) {
        this.configPath = configPath;
    }

    /* ─── static factories ─── */

    /** Load a config file (creates empty config if file doesn't exist). */
    public static FabricConfig loadConfiguration(Path path) {
        FabricConfig cfg = new FabricConfig(path);
        if (Files.exists(path)) {
            cfg.reload();
        }
        return cfg;
    }

    /**
     * Copies the default config.yml from mod resources if the file doesn't exist on disk.
     */
    public void saveDefaultConfig() {
        if (Files.exists(configPath)) return;

        try {
            Files.createDirectories(configPath.getParent());
            try (InputStream in = getClass().getClassLoader().getResourceAsStream("config.yml")) {
                if (in != null) {
                    Files.copy(in, configPath);
                    LOGGER.info("Default config.yml created at {}", configPath);
                } else {
                    Files.createFile(configPath);
                    LOGGER.info("Empty config.yml created at {}", configPath);
                }
            }
        } catch (IOException e) {
            LOGGER.error("Failed to save default config", e);
        }
    }

    /**
     * Reloads config from disk.
     */
    public void reload() {
        values.clear();
        if (!Files.exists(configPath)) return;

        try {
            List<String> lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            parseLines(lines);
        } catch (IOException e) {
            LOGGER.error("Failed to reload config from {}", configPath, e);
        }
    }

    // --- Getters ---

    public String getString(String path) {
        return getString(path, null);
    }

    public String getString(String path, String defaultValue) {
        Object val = values.get(path);
        if (val == null) return defaultValue;
        return unquoteAndUnescape(val.toString());
    }

    public int getInt(String path, int defaultValue) {
        Object val = values.get(path);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public long getLong(String path, long defaultValue) {
        Object val = values.get(path);
        if (val == null) return defaultValue;
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        Object val = values.get(path);
        if (val == null) return defaultValue;
        String s = val.toString().trim().toLowerCase(Locale.ROOT);
        if ("true".equals(s) || "yes".equals(s)) return true;
        if ("false".equals(s) || "no".equals(s)) return false;
        return defaultValue;
    }

    @SuppressWarnings("unchecked")
    public List<String> getStringList(String path) {
        Object val = values.get(path);
        if (val instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object item : list) {
                result.add(item.toString());
            }
            return result;
        }
        return new ArrayList<>();
    }

    public List<?> getList(String path) {
        Object val = values.get(path);
        if (val instanceof List<?> list) return list;
        return null;
    }

    /**
     * Returns true if the path has an explicit value OR if any keys exist
     * that would make it a configuration section (i.e. keys starting with path + ".").
     */
    public boolean contains(String path) {
        if (values.containsKey(path)) return true;
        String prefix = path + ".";
        for (String key : values.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Returns true if there are sub-keys under this path (i.e. it acts as a section).
     */
    public boolean isConfigurationSection(String path) {
        String prefix = path + ".";
        for (String key : values.keySet()) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    /**
     * Returns a ConfigSection helper for the given path.
     */
    public ConfigSection getConfigurationSection(String path) {
        if (!isConfigurationSection(path)) return null;
        return new ConfigSection(path, this);
    }

    /**
     * Returns the immediate child key names under the given section path.
     * E.g. for path "users" with keys "users.alice.hash", "users.bob.hash",
     * returns {"alice", "bob"}.
     */
    public Set<String> getSectionKeys(String path) {
        Set<String> keys = new LinkedHashSet<>();
        String prefix = path + ".";
        for (String key : values.keySet()) {
            if (key.startsWith(prefix)) {
                String rest = key.substring(prefix.length());
                int dot = rest.indexOf('.');
                keys.add(dot > 0 ? rest.substring(0, dot) : rest);
            }
        }
        return keys;
    }

    // --- Setters ---

    public void set(String path, Object value) {
        if (value == null) {
            // Remove exact key and all sub-keys
            values.remove(path);
            String prefix = path + ".";
            values.keySet().removeIf(k -> k.startsWith(prefix));
        } else if (value instanceof List<?> list) {
            values.put(path, new ArrayList<>(list));
        } else {
            values.put(path, value);
        }
    }

    /**
     * Writes the current config values back to disk at the configured path.
     */
    public void save() {
        save(this.configPath);
    }

    /**
     * Writes the current config values to the specified file.
     */
    public void save(Path targetPath) {
        if (targetPath == null) return;
        try {
            Files.createDirectories(targetPath.getParent());
            List<String> lines = serializeMap(values, 0);
            Files.write(targetPath, lines, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", targetPath, e);
        }
    }

    public Path getConfigPath() {
        return configPath;
    }

    /* ─── ConfigSection helper (Bukkit YamlConfiguration compatibility) ─── */

    public static class ConfigSection {
        private final String basePath;
        private final FabricConfig config;
        ConfigSection(String basePath, FabricConfig config) {
            this.basePath = basePath;
            this.config = config;
        }
        public Set<String> getKeys(boolean deep) {
            return config.getSectionKeys(basePath);
        }
    }

    // --- Parsing ---

    private void parseLines(List<String> lines) {
        Deque<String> prefixStack = new ArrayDeque<>();
        Deque<Integer> indentStack = new ArrayDeque<>();
        String currentListKey = null;
        int currentListIndent = -1;

        for (String rawLine : lines) {
            String trimmed = rawLine.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue;

            int indent = countIndent(rawLine);

            // Pop prefix stack to find current scope
            while (!indentStack.isEmpty() && indent <= indentStack.peek()) {
                indentStack.pop();
                prefixStack.pop();
            }

            // Handle list items (- value)
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                if (currentListKey != null && indent > currentListIndent) {
                    String itemValue = trimmed.length() > 2 ? trimmed.substring(2).trim() : "";
                    // Strip quotes
                    if (itemValue.length() >= 2 && ((itemValue.startsWith("\"") && itemValue.endsWith("\""))
                            || (itemValue.startsWith("'") && itemValue.endsWith("'")))) {
                        itemValue = itemValue.substring(1, itemValue.length() - 1);
                    }
                    itemValue = unquoteAndUnescape(itemValue);
                    Object existing = values.get(currentListKey);
                    @SuppressWarnings("unchecked")
                    List<String> list = (existing instanceof List) ? (List<String>) existing : new ArrayList<>();
                    list.add(itemValue);
                    values.put(currentListKey, list);
                }
                continue;
            }

            // Reset list context if we're not in a list item
            if (currentListKey != null && indent <= currentListIndent) {
                currentListKey = null;
                currentListIndent = -1;
            }

            int colonIdx = trimmed.indexOf(':');
            if (colonIdx < 0) continue;

            String key = trimmed.substring(0, colonIdx).trim();
            String valueStr = trimmed.substring(colonIdx + 1).trim();

            String fullKey = buildFullKey(prefixStack, key);

            if (valueStr.isEmpty()) {
                // Section header or list parent
                prefixStack.push(key);
                indentStack.push(indent);
                currentListKey = fullKey;
                currentListIndent = indent;
            } else if ("[]".equals(valueStr)) {
                values.put(fullKey, new ArrayList<>());
                currentListKey = null;
                currentListIndent = -1;
            } else {
                values.put(fullKey, unquoteAndUnescape(valueStr));
                currentListKey = null;
                currentListIndent = -1;
            }
        }
    }

    private int countIndent(String line) {
        int count = 0;
        for (char c : line.toCharArray()) {
            if (c == ' ') count++;
            else if (c == '\t') count += 2;
            else break;
        }
        return count;
    }

    private String buildFullKey(Deque<String> prefixStack, String key) {
        if (prefixStack.isEmpty()) return key;
        StringBuilder sb = new StringBuilder();
        Iterator<String> it = prefixStack.descendingIterator();
        while (it.hasNext()) {
            sb.append(it.next()).append('.');
        }
        sb.append(key);
        return sb.toString();
    }

    // --- Serialization ---

    @SuppressWarnings("unchecked")
    private List<String> serializeMap(Map<String, Object> map, int indentLevel) {
        List<String> lines = new ArrayList<>();
        String indent = "  ".repeat(indentLevel);

        // Group keys by top-level section
        LinkedHashMap<String, LinkedHashMap<String, Object>> sections = new LinkedHashMap<>();
        LinkedHashMap<String, Object> topLevel = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = entry.getKey();
            int dot = key.indexOf('.');
            if (dot > 0) {
                String section = key.substring(0, dot);
                String rest = key.substring(dot + 1);
                sections.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(rest, entry.getValue());
            } else {
                topLevel.put(key, entry.getValue());
            }
        }

        for (Map.Entry<String, Object> entry : topLevel.entrySet()) {
            Object val = entry.getValue();
            if (val instanceof List<?> list) {
                if (list.isEmpty()) {
                    lines.add(indent + entry.getKey() + ": []");
                } else {
                    lines.add(indent + entry.getKey() + ":");
                    for (Object item : list) {
                        lines.add(indent + "  - " + quoteIfNeeded(item.toString()));
                    }
                }
            } else {
                lines.add(indent + entry.getKey() + ": " + quoteIfNeeded(val.toString()));
            }
        }

        for (Map.Entry<String, LinkedHashMap<String, Object>> section : sections.entrySet()) {
            lines.add(indent + section.getKey() + ":");
            lines.addAll(serializeMap(section.getValue(), indentLevel + 1));
        }

        return lines;
    }

    private String quoteIfNeeded(String value) {
        if (value.isEmpty()) return "\"\"";
        if (value.contains(":") || value.contains("#") || value.contains("\"")
                || value.startsWith(" ") || value.endsWith(" ")) {
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        }
        return value;
    }

    private String unquoteAndUnescape(String value) {
        if (value == null) {
            return null;
        }
        String s = value.trim();
        for (int i = 0; i < 6; i++) {
            String before = s;
            if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
                s = s.substring(1, s.length() - 1);
            }
            s = s.replace("\\\"", "\"")
                    .replace("\\\\", "\\");
            if (s.equals(before)) {
                break;
            }
        }
        return s;
    }
}
