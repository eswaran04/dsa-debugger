package dev.dsadebug.harness;

import static org.junit.jupiter.api.Assertions.*;

import dev.dsadebug.tracer.Fixtures;
import dev.dsadebug.tracer.SourceCompiler;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class HarnessTest {
    private record Run(int exit, String stderr) {}

    private static Run run(String code, String invokeJson) throws Exception {
        Path classes = Files.createTempDirectory("hc");
        SourceCompiler.CompileResult r = SourceCompiler.compile(code, classes);
        assertTrue(r.ok(), r.diags().toString());
        Path invoke = Files.createTempFile("invoke", ".json");
        Files.writeString(invoke, invokeJson);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        // The JUnit console launcher hides the test classpath behind its own loader, so use the
        // location Harness itself was loaded from (plus the user's compiled classes).
        String harnessLoc = Path.of(Harness.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String cp = System.getProperty("java.class.path") + File.pathSeparator + harnessLoc + File.pathSeparator + classes;
        Process p = new ProcessBuilder(java, "-cp", cp, "dev.dsadebug.harness.Harness", invoke.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Run(p.waitFor(), err);
    }

    @Test
    void runsAndExitsZero() throws Exception {
        Run r = run(Fixtures.read("QuickSort.java"),
                "{\"method\":\"quickSort\",\"paramNames\":[\"nums\"],\"args\":{\"nums\":\"[7,4,1,5,3]\"}}");
        assertEquals(0, r.exit(), r.stderr());
    }

    @Test
    void badArgExits3() throws Exception {
        Run r = run(Fixtures.read("QuickSort.java"),
                "{\"method\":\"quickSort\",\"paramNames\":[\"nums\"],\"args\":{\"nums\":\"5\"}}");
        assertEquals(3, r.exit());
        assertTrue(r.stderr().startsWith("HARNESS_ARG_ERROR"), r.stderr());
    }

    @Test
    void userExceptionExits1() throws Exception {
        Run r = run("class Solution { int f(int n) { return 1 / n; } }",
                "{\"method\":\"f\",\"paramNames\":[\"n\"],\"args\":{\"n\":\"0\"}}");
        assertEquals(1, r.exit());
        assertTrue(r.stderr().contains("ArithmeticException"), r.stderr());
    }

    @Test
    void missingMethodExits3() throws Exception {
        Run r = run(Fixtures.read("QuickSort.java"),
                "{\"method\":\"nope\",\"paramNames\":[\"nums\"],\"args\":{\"nums\":\"[1]\"}}");
        assertEquals(3, r.exit());
    }
}
