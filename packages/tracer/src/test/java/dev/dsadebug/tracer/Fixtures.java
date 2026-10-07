package dev.dsadebug.tracer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Test helper: loads files from src/test/resources/fixtures. */
public final class Fixtures {
    private Fixtures() {}

    public static String read(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/fixtures/" + name)) {
            if (in == null) throw new IllegalArgumentException("missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
