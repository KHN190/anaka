package dev.anaka.task;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every key the Python client sends in an input task is one InputTask accepts ("use" was sent and refused, 400). */
class InputTaskKeysTest {
    static final Pattern KEYS = Pattern.compile("\"keys\"\\s*:\\s*\\[([^\\]]*)\\]");
    static final Pattern WORD = Pattern.compile("\"(\\w+)\"");

    /** Pure: the key names in every {@code "keys": [...]} literal of a Python source. */
    static Set<String> keysIn(String source) {
        Set<String> out = new TreeSet<>();
        Matcher m = KEYS.matcher(source);
        while (m.find()) {
            Matcher w = WORD.matcher(m.group(1));
            while (w.find()) out.add(w.group(1));
        }
        return out;
    }

    @Test
    void theReaderOverAFixture() {
        assertEquals(Set.of("forward", "jump"), keysIn("{\"type\": \"input\", \"keys\": [\"forward\", \"jump\"]}"));
        assertEquals(Set.of(), keysIn("{\"type\": \"look\"}"));
    }

    @Test
    void useIsAKey() {
        assertTrue(InputTask.KEYS.contains("use"));
        assertFalse(InputTask.KEYS.contains("use2"), "must fail: a key the task does not know");
    }

    @Test
    void everyKeyThePythonClientSends() throws IOException {
        String env = System.getenv("BONOBO_SRC");
        Path src = Path.of(env != null ? env
            : System.getProperty("user.home") + "/.claude/skills/minecraft-agent/scripts/bonobo");
        Assumptions.assumeTrue(Files.isDirectory(src), "the Python client is not on disk");
        Set<String> sent = new TreeSet<>();
        try (Stream<Path> files = Files.walk(src)) {
            for (Path p : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".py"))::iterator) {
                sent.addAll(keysIn(Files.readString(p)));
            }
        }
        assertFalse(sent.isEmpty(), "the client sends input tasks");
        Set<String> unknown = new TreeSet<>(sent);
        unknown.removeAll(InputTask.KEYS);
        assertEquals(Set.of(), unknown, "keys the client sends that InputTask refuses");
    }
}
