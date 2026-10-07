package dev.dsadebug.tracer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.jdi.StackFrame;
import dev.dsadebug.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

class ValueSerializerTest {
    @SuppressWarnings("unchecked")
    private static Map<String, Object> ser(ValueSerializer vs, StackFrame f, String local) throws Exception {
        dev.dsadebug.json.Json.Writer w = new Json.Writer();
        vs.write(w, f.getValue(f.visibleVariableByName(local)));
        return (Map<String, Object>) Json.parse(w.toString());
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) {
        return (List<Object>) o;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void serializesAllKinds() throws Exception {
        String src = Fixtures.read("ValuesProbe.java");
        int line = 0;
        String[] lines = src.split("\n");
        for (int k = 0; k < lines.length; k++) if (lines[k].contains("int stop = 0;")) line = k + 1;
        Path dir = Files.createTempDirectory("probe");
        Path file = dir.resolve("ValuesProbe.java");
        Files.writeString(file, src);
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, jc.run(null, null, null, "-g", "-d", dir.toString(), file.toString()));

        try (JdiFixture fx = JdiFixture.launch(dir, "ValuesProbe", line)) {
            StackFrame f = fx.frame();
            ValueSerializer vs = new ValueSerializer(1000, 3);

            Map<String, Object> i = ser(vs, f, "i");
            assertEquals("int", i.get("t"));
            assertEquals(3L, i.get("v"));
            assertEquals("9007199254740993", ser(vs, f, "big").get("v"));
            Map<String, Object> c = ser(vs, f, "c");
            assertEquals("char", c.get("t"));
            assertEquals("x", c.get("v"));
            Map<String, Object> s = ser(vs, f, "s");
            assertEquals("str", s.get("t"));
            assertEquals("hi", s.get("v"));

            Map<String, Object> a = ser(vs, f, "a");
            assertEquals("array", a.get("t"));
            assertEquals("int", a.get("elem"));
            assertEquals(2L, a.get("len"));
            assertEquals(2L, map(list(a.get("v")).get(1)).get("v"));
            assertFalse(a.containsKey("truncated"));
            assertEquals(a.get("id"), ser(vs, f, "a").get("id"));

            Map<String, Object> g = ser(vs, f, "g");
            assertEquals("int[]", g.get("elem"));
            assertEquals(2L, map(list(g.get("v")).get(1)).get("len"));

            Map<String, Object> l = ser(vs, f, "l");
            assertEquals("list", l.get("t"));
            assertEquals("java.util.ArrayList", l.get("cls"));
            assertEquals(2L, l.get("len"));
            assertEquals(4L, map(list(l.get("v")).get(0)).get("v"));
            assertEquals(5L, map(list(l.get("v")).get(1)).get("v"));

            Map<String, Object> ll = ser(vs, f, "ll");
            assertEquals(2L, ll.get("len"));
            Map<String, Object> inner0 = map(list(ll.get("v")).get(0));
            Map<String, Object> inner1 = map(list(ll.get("v")).get(1));
            assertEquals("list", inner0.get("t"));
            assertEquals(2L, map(list(inner0.get("v")).get(1)).get("v"));
            assertEquals("java.util.LinkedList", inner1.get("cls"));
            assertEquals(3L, map(list(inner1.get("v")).get(0)).get("v"));

            Map<String, Object> m = ser(vs, f, "m");
            assertEquals("map", m.get("t"));
            List<Object> e0 = list(list(m.get("entries")).get(0));
            assertEquals("k", map(e0.get(0)).get("v"));
            assertEquals(1L, map(e0.get(1)).get("v"));

            Map<String, Object> st = ser(vs, f, "st");
            assertEquals("set", st.get("t"));
            assertEquals(7L, map(list(st.get("v")).get(0)).get("v"));

            Map<String, Object> dq = ser(vs, f, "dq");
            List<Object> dv = list(dq.get("v"));
            assertEquals(3, dv.size());
            for (int k = 0; k < 3; k++) assertEquals((long) k, map(dv.get(k)).get("v"));

            Map<String, Object> huge = ser(vs, f, "huge");
            assertEquals(1500L, huge.get("len"));
            assertEquals(1000, list(huge.get("v")).size());
            assertEquals(Boolean.TRUE, huge.get("truncated"));

            assertEquals(3, list(ser(vs, f, "imN").get("v")).size());
            Map<String, Object> im1 = ser(vs, f, "im1");
            assertEquals(1L, im1.get("len"));
            assertEquals(9L, map(list(im1.get("v")).get(0)).get("v"));
            assertEquals(1L, ser(vs, f, "imMap1").get("len"));
            assertEquals("set", ser(vs, f, "imSet").get("t"));

            // depth cut-off: ll's inner lists are depth 1; with maxDepth 0 they become refs
            Map<String, Object> shallow = ser(new ValueSerializer(1000, 0), f, "ll");
            assertEquals("ref", map(list(shallow.get("v")).get(0)).get("t"));
            assertTrue(map(list(shallow.get("v")).get(0)).containsKey("id"));
        }
    }
}
