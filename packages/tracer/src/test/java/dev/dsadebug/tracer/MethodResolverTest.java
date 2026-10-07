package dev.dsadebug.tracer;

import static org.junit.jupiter.api.Assertions.*;

import dev.dsadebug.tracer.MethodResolver.ResolveException;
import dev.dsadebug.tracer.SourceCompiler.MethodSig;
import java.nio.file.Files;
import java.util.List;
import org.junit.jupiter.api.Test;

class MethodResolverTest {
    private static List<MethodSig> quickSortSigs() throws Exception {
        return SourceCompiler.compile(Fixtures.read("QuickSort.java"), Files.createTempDirectory("mr")).methods();
    }

    @Test
    void picksByArgNames() throws Exception {
        List<MethodSig> sigs = quickSortSigs();
        assertEquals("quickSort", MethodResolver.resolve(sigs, null, List.of("nums")).name());
        assertEquals("quick", MethodResolver.resolve(sigs, null, List.of("nums", "low", "high")).name());
    }

    @Test
    void noMatchListsSignatures() throws Exception {
        ResolveException e = assertThrows(ResolveException.class,
                () -> MethodResolver.resolve(quickSortSigs(), null, List.of("x")));
        assertTrue(e.getMessage().contains("quickSort(int[] nums)"), e.getMessage());
    }

    @Test
    void requestedMethodMustMatchArgs() throws Exception {
        assertThrows(ResolveException.class,
                () -> MethodResolver.resolve(quickSortSigs(), "placeInPosition", List.of("nums")));
        assertEquals("quick", MethodResolver.resolve(quickSortSigs(), "quick", List.of("high", "nums", "low")).name());
    }

    @Test
    void packagePrivateMethodResolves() {
        MethodSig f = new MethodSig("f", false, false, List.of("n"), List.of("int"));
        assertSame(f, MethodResolver.resolve(List.of(f), null, List.of("n")));
    }

    @Test
    void prefersPublicOverPackagePrivate() {
        MethodSig a = new MethodSig("a", false, false, List.of("n"), List.of("int"));
        MethodSig b = new MethodSig("b", true, false, List.of("n"), List.of("int"));
        assertSame(b, MethodResolver.resolve(List.of(a, b), null, List.of("n")));
    }
}
