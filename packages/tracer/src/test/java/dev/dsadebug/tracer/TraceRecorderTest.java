package dev.dsadebug.tracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.dsadebug.json.Json;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TraceRecorderTest {
    private static final List<Job.Arg> QS_ARGS = List.of(new Job.Arg("nums", "[7,4,1,5,3]"));

    private static Map<String, Object> trace(String code, String method, List<Job.Arg> args, boolean record) {
        String json = new TraceRecorder().run(new Job(code, method, args, record, Job.Limits.DEFAULTS));
        return map(Json.parse(json));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    private static List<Map<String, Object>> steps(Map<String, Object> t) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object s : list(t.get("steps"))) out.add(map(s));
        return out;
    }

    private static Map<String, Object> top(Map<String, Object> step) {
        return map(list(step.get("stack")).get(0));
    }

    private static List<Long> ints(Object valueArray) {
        List<Long> out = new ArrayList<>();
        for (Object e : list(map(valueArray).get("v"))) out.add((Long) map(e).get("v"));
        return out;
    }

    private static long lineOf(String src, String text) {
        String[] lines = src.split("\n");
        for (int k = 0; k < lines.length; k++) if (lines[k].contains(text)) return k + 1;
        throw new IllegalArgumentException("no line containing " + text);
    }

    @Test
    void quickSortHappyPath() {
        String src = Fixtures.read("QuickSort.java");
        Map<String, Object> t = trace(src, "quickSort", QS_ARGS, true);
        assertEquals("ok", t.get("status"), String.valueOf(t.get("error")));
        assertEquals(List.of(1L, 3L, 4L, 5L, 7L), ints(t.get("result")));
        assertEquals("quickSort", map(t.get("entry")).get("method"));
        assertEquals(List.of(Map.of("name", "nums", "type", "int[]")), map(t.get("entry")).get("params"));

        List<Map<String, Object>> steps = steps(t);
        assertEquals(lineOf(src, "int low = 0;"), steps.get(0).get("line"));
        assertEquals((long) steps.size(), map(t.get("stats")).get("steps"));

        boolean placeLocals = false;
        boolean placeReturn = false;
        long maxDepth = 0;
        long entryFid = (Long) top(steps.get(0)).get("fid");
        for (Map<String, Object> s : steps) {
            Map<String, Object> f = top(s);
            assertEquals(s.get("line"), f.get("line"), "stack[0].line == line for " + s);
            long depth = (Long) s.get("depth");
            maxDepth = Math.max(maxDepth, depth);
            List<Object> stack = list(s.get("stack"));
            assertEquals(depth, stack.size());
            // the entry frame keeps its fid; recursive frames get distinct fids
            assertEquals(entryFid, map(stack.get(stack.size() - 1)).get("fid"));
            Set<Object> fids = new HashSet<>();
            for (Object fr : stack) fids.add(map(fr).get("fid"));
            assertEquals(stack.size(), fids.size(), "distinct fids in " + s);
            if ("placeInPosition".equals(f.get("method"))) {
                Set<Object> names = new HashSet<>();
                for (Object v : list(f.get("locals"))) names.add(map(v).get("name"));
                if (names.contains("i") && names.contains("j")) placeLocals = true;
            }
            if ("return".equals(s.get("event")) && "placeInPosition".equals(s.get("method"))) {
                placeReturn = true;
                assertEquals("int", map(s.get("returnValue")).get("t"));
            }
        }
        assertTrue(placeLocals, "placeInPosition step with locals i and j");
        assertTrue(placeReturn, "return step from placeInPosition");
        assertTrue(maxDepth >= 3, "max depth " + maxDepth);
    }

    @Test
    void voidEntryEmitsFinalArgs() {
        Map<String, Object> t = trace(Fixtures.read("VoidSort.java"), "sortColors",
                List.of(new Job.Arg("nums", "[2,0,1]")), true);
        assertEquals("ok", t.get("status"), String.valueOf(t.get("error")));
        assertEquals("void", map(t.get("result")).get("t"));
        Map<String, Object> arg = map(list(t.get("finalArgs")).get(0));
        assertEquals("nums", arg.get("name"));
        assertEquals(List.of(0L, 1L, 2L), ints(arg.get("value")));
    }

    @Test
    void helperClassIsStepped() {
        Map<String, Object> t = trace(Fixtures.read("WithHelper.java"), "total", List.of(), true);
        assertEquals("ok", t.get("status"), String.valueOf(t.get("error")));
        assertEquals(3L, map(t.get("result")).get("v"));
        assertTrue(steps(t).stream().anyMatch(s -> "Pair".equals(top(s).get("cls"))), "a step inside Pair");
    }

    @Test
    void stdoutAttachedToStep() {
        Map<String, Object> t = trace(Fixtures.read("Printer.java"), "print", List.of(), true);
        assertEquals("ok", t.get("status"), String.valueOf(t.get("error")));
        StringBuilder joined = new StringBuilder();
        for (Map<String, Object> s : steps(t)) if (s.get("stdout") != null) joined.append(s.get("stdout"));
        assertEquals("a\nb\n", joined.toString());
        assertEquals("a\nb\n", t.get("stdout"));
    }

    @Test
    void runOnlyHasNoSteps() {
        Map<String, Object> t = trace(Fixtures.read("QuickSort.java"), "quickSort", QS_ARGS, false);
        assertEquals("ok", t.get("status"), String.valueOf(t.get("error")));
        assertEquals(0, list(t.get("steps")).size());
        assertEquals(List.of(1L, 3L, 4L, 5L, 7L), ints(t.get("result")));
    }

    @Test
    void jreOnlyReportsError() {
        String json = new TraceRecorder(() -> null)
                .run(new Job(Fixtures.read("QuickSort.java"), "quickSort", QS_ARGS, true, Job.Limits.DEFAULTS));
        Map<String, Object> t = map(Json.parse(json));
        assertEquals("runtime_error", t.get("status"));
        assertEquals("A JDK is required (javac not available) — a JRE is not enough.", t.get("error"));
        assertEquals(0, list(t.get("steps")).size());
        assertEquals("", t.get("stdout"));
        assertNotNull(t.get("stats"));
    }

    @Test
    void compileErrorIsReported() {
        Map<String, Object> t = trace("class Solution { int f() { return x; } }", "f", List.of(), true);
        assertEquals("compile_error", t.get("status"));
        Map<String, Object> d = map(list(t.get("compileErrors")).get(0));
        assertEquals(1L, d.get("line"));
        assertEquals(0, list(t.get("steps")).size());
    }

    @Test
    void unresolvableMethodIsRuntimeError() {
        Map<String, Object> t = trace(Fixtures.read("QuickSort.java"), "nope", QS_ARGS, true);
        assertEquals("runtime_error", t.get("status"));
        assertTrue(((String) t.get("error")).contains("nope"), String.valueOf(t.get("error")));
        assertNull(t.get("entry"));
    }

    @Test
    void badArgIsRuntimeError() {
        Map<String, Object> t = trace(Fixtures.read("QuickSort.java"), "quickSort",
                List.of(new Job.Arg("nums", "5")), true);
        assertEquals("runtime_error", t.get("status"));
        assertTrue(((String) t.get("error")).startsWith("argument nums"), String.valueOf(t.get("error")));
        assertEquals(0, list(t.get("steps")).size());
    }

    @Test
    void uncaughtExceptionEndsTrace() {
        String code = "class Solution {\n"
                + "    int f(int[] a) {\n"
                + "        int k = 5;\n"
                + "        return a[k];\n"
                + "    }\n"
                + "}\n";
        Map<String, Object> t = trace(code, "f", List.of(new Job.Arg("a", "[1]")), true);
        assertEquals("runtime_error", t.get("status"));
        Map<String, Object> ex = map(t.get("exception"));
        assertEquals("java.lang.ArrayIndexOutOfBoundsException", ex.get("type"));
        assertEquals("Index 5 out of bounds for length 1", ex.get("message"));
        assertEquals(4L, ex.get("line"));
        assertNull(t.get("result"));
        List<Map<String, Object>> steps = steps(t);
        Map<String, Object> last = steps.get(steps.size() - 1);
        assertEquals("exception", last.get("event"));
        assertEquals(4L, last.get("line"));
        assertEquals(4L, top(last).get("line"));
        assertFalse(steps.stream().anyMatch(s -> "return".equals(s.get("event"))), "no return step on throw");
    }

    @Test
    void caughtExceptionKeepsFidsAligned() {
        String code = "class Solution {\n"
                + "    int f(int n) {\n"
                + "        try {\n"
                + "            boom(n);\n"
                + "        } catch (IllegalStateException e) {\n"
                + "            n = 0;\n"
                + "        }\n"
                + "        return g(n);\n"
                + "    }\n"
                + "    void boom(int n) { throw new IllegalStateException(\"x\"); }\n"
                + "    int g(int n) { return n + 1; }\n"
                + "}\n";
        Map<String, Object> t = trace(code, "f", List.of(new Job.Arg("n", "3")), true);
        assertEquals("ok", t.get("status"), String.valueOf(t.get("error")));
        assertEquals(1L, map(t.get("result")).get("v"));
        assertNull(t.get("exception"));
        Set<Object> fids = new HashSet<>();
        for (Map<String, Object> s : steps(t)) {
            List<Object> stack = list(s.get("stack"));
            assertEquals(s.get("depth"), (long) stack.size());
            Map<String, Object> f = top(s);
            if (stack.size() == 2) fids.add(f.get("method") + "#" + f.get("fid"));
        }
        // boom and g each get their own fid
        assertEquals(2, fids.stream().map(o -> ((String) o).split("#")[1]).distinct().count(), fids.toString());
    }

    @Test
    void mainCliReadsStdin() throws Exception {
        String jar = System.getenv().getOrDefault("TRACER_JAR", "build/tracer.jar");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Json.Writer w = new Json.Writer();
        w.beginObj().key("code").str(Fixtures.read("QuickSort.java")).key("method").str("quickSort")
                .key("args").beginArr().beginObj().key("name").str("nums").key("raw").str("[7,4,1,5,3]").endObj()
                .endArr().key("record").bool(true).endObj();
        Process p = new ProcessBuilder(java, "-jar", jar, "-")
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try (OutputStream in = p.getOutputStream()) {
            in.write(w.toString().getBytes(StandardCharsets.UTF_8));
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor());
        Map<String, Object> t = map(Json.parse(out.trim()));
        assertEquals("ok", t.get("status"));
        assertEquals(List.of(1L, 3L, 4L, 5L, 7L), ints(t.get("result")));
    }
}
