package sh.aki.tickcapacity.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every translation must define exactly the same keys as the English default bundle. */
class MessageBundleTest {

    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessageBundleTest.class.getClassLoader().getResourceAsStream("lang/" + name)) {
            assertTrue(in != null, name + " missing");
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void japaneseHasSameKeysAsEnglish() throws IOException {
        Set<String> en = new TreeSet<>(load("messages.properties").stringPropertyNames());
        Set<String> ja = new TreeSet<>(load("messages_ja.properties").stringPropertyNames());
        Set<String> missing = new TreeSet<>(en);
        missing.removeAll(ja);
        Set<String> extra = new TreeSet<>(ja);
        extra.removeAll(en);
        assertEquals(Set.of(), missing, "keys missing in messages_ja.properties");
        assertEquals(Set.of(), extra, "keys only in messages_ja.properties");
    }

    @Test
    void placeholdersMatchBetweenLanguages() throws IOException {
        Properties en = load("messages.properties");
        Properties ja = load("messages_ja.properties");
        for (String key : en.stringPropertyNames()) {
            for (int i = 0; i < 8; i++) {
                String ph = "{" + i + "}";
                assertEquals(en.getProperty(key).contains(ph), ja.getProperty(key, "").contains(ph),
                        key + " placeholder " + ph);
            }
        }
    }

    /**
     * Every literal message key in the source code must exist. Keys built dynamically
     * ("finding." + key, "confidence." + name, ...) are covered by the enum/rule tests instead.
     */
    @Test
    void everyLiteralKeyInSourceExists() throws IOException {
        Properties en = load("messages.properties");
        Pattern literal = Pattern.compile("\"((?:help|status|hardware|estimate|analyze|bench|report|results|compare|"
                + "monitor|activity|peak|calibration|profile|alert|html|error|common|ui|next|display|results)\\.[a-zA-Z0-9.\\-]+)\"");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            // Settings.java contains config.yml paths, not message keys.
            for (Path f : files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("Settings.java")).toList()) {
                Matcher mt = literal.matcher(Files.readString(f));
                while (mt.find()) {
                    String key = mt.group(1);
                    if (!key.endsWith(".") && !key.endsWith(".json") && !en.containsKey(key)) {
                        missing.add(key + " (" + f.getFileName() + ")");
                    }
                }
            }
        }
        assertEquals(Set.of(), missing);
    }
}
