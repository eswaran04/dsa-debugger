package dev.dsadebug.tracer;

import static org.junit.jupiter.api.Assertions.*;

import dev.dsadebug.tracer.SourceCompiler.CompileResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceCompilerTest {
    private static CompileResult compile(String code) throws Exception {
        Path out = Files.createTempDirectory("sc");
        return SourceCompiler.compile(code, out);
    }

    @Test
    void compilesQuickSortFixture() throws Exception {
        CompileResult r = compile(Fixtures.read("QuickSort.java"));
        assertTrue(r.ok(), r.diags().toString());
        assertEquals(List.of("Solution"), r.userClasses());
        assertEquals(List.of("quickSort", "quick", "placeInPosition"),
                r.methods().stream().map(SourceCompiler.MethodSig::name).toList());
        assertEquals(List.of("nums", "low", "high"), r.methods().get(1).paramNames());
        assertEquals(List.of("int[]", "int", "int"), r.methods().get(1).paramTypes());
    }

    @Test
    void reportsSyntaxErrorLine() throws Exception {
        CompileResult r = compile("class Solution {\n  int f() {\n    int x = 1\n    return x;\n  }\n}\n");
        assertFalse(r.ok());
        assertEquals(3, r.diags().get(0).line());
        assertEquals(1, r.methods().size());
    }

    @Test
    void reportsSyntaxErrorOnLine4() throws Exception {
        CompileResult r = compile("class Solution {\n  int f() {\n    int y = 2;\n    int x = 1\n    return x;\n  }\n}\n");
        assertFalse(r.ok());
        assertEquals(4, r.diags().get(0).line());
    }

    @Test
    void helperAndNestedClasses() throws Exception {
        CompileResult r = compile("class Solution { static class Node{ void g(){} } int f(){return 1;} }\nclass Pair{ void h(){} }");
        assertTrue(r.ok(), r.diags().toString());
        assertTrue(r.userClasses().containsAll(List.of("Solution", "Solution$Node", "Pair")), r.userClasses().toString());
        assertEquals(List.of("f"), r.methods().stream().map(SourceCompiler.MethodSig::name).toList());
    }

    @Test
    void importsAndNonPublicClass() throws Exception {
        CompileResult r = compile("import java.util.*; class Solution { List<Integer> f(int n){ return new ArrayList<>(); } }");
        assertTrue(r.ok(), r.diags().toString());
        SourceCompiler.MethodSig f = r.methods().get(0);
        assertEquals("f", f.name());
        assertFalse(f.isPublic());
    }
}
