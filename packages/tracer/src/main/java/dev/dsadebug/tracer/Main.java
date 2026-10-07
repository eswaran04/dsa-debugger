package dev.dsadebug.tracer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * CLI: {@code java -jar tracer.jar <job.json | ->}. Prints exactly one Trace JSON object and exits 0
 * for every trace status; exit 2 means a tracer bug (stack trace on stderr).
 */
public final class Main {
    private Main() {}

    public static void main(String[] argv) {
        try {
            if (argv.length != 1) {
                System.err.println("usage: java -jar tracer.jar <job.json | ->");
                System.exit(2);
            }
            String json = argv[0].equals("-")
                    ? new String(System.in.readAllBytes(), StandardCharsets.UTF_8)
                    : Files.readString(Path.of(argv[0]));
            String trace = new TraceRecorder().run(Job.fromJson(json));
            System.out.write((trace + "\n").getBytes(StandardCharsets.UTF_8));
            System.out.flush();
            System.exit(0);
        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(2);
        }
    }
}
