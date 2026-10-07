package dev.dsadebug.harness;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Type;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgConverterTest {
    @SuppressWarnings("unused")
    static void dummy(List<List<Integer>> a, List<Integer> b, List<String> c) {}

    private static Type[] generics() throws Exception {
        return ArgConverterTest.class
                .getDeclaredMethod("dummy", List.class, List.class, List.class)
                .getGenericParameterTypes();
    }

    @Test
    void intArray() {
        assertArrayEquals(new int[] {7, 4, 1, 5, 3}, (int[]) ArgConverter.convert("[7, 4, 1, 5, 3]", int[].class));
    }

    @Test
    void intMatrix() {
        assertArrayEquals(new int[][] {{1, 2}, {3}}, (int[][]) ArgConverter.convert("[[1,2],[3]]", int[][].class));
    }

    @Test
    void charMatrix() {
        assertArrayEquals(new char[][] {{'a', 'b'}}, (char[][]) ArgConverter.convert("[[\"a\",\"b\"]]", char[][].class));
    }

    @Test
    void nestedList() throws Exception {
        Object r = ArgConverter.convert("[[1,2],[]]", generics()[0]);
        assertEquals(List.of(List.of(1, 2), List.of()), r);
    }

    @Test
    void intListAndStringList() throws Exception {
        assertEquals(List.of(1, 2), ArgConverter.convert("[1,2]", generics()[1]));
        assertEquals(List.of("a", "b"), ArgConverter.convert("[\"a\",\"b\"]", generics()[2]));
    }

    @Test
    void scalars() {
        assertEquals(9, ArgConverter.convert("9", int.class));
        assertEquals(9L, ArgConverter.convert("9", long.class));
        assertEquals(3.0, ArgConverter.convert("3", double.class));
        assertEquals(2.5, ArgConverter.convert("2.5", double.class));
        assertEquals("hi", ArgConverter.convert("\"hi\"", String.class));
        assertEquals(true, ArgConverter.convert("true", boolean.class));
        assertEquals('x', ArgConverter.convert("\"x\"", char.class));
        assertArrayEquals(new String[] {"a", "b"}, (String[]) ArgConverter.convert("[\"a\",\"b\"]", String[].class));
    }

    @Test
    void wrongShapeThrows() {
        IllegalArgumentException e =
                assertThrows(IllegalArgumentException.class, () -> ArgConverter.convert("[1]", int.class));
        assertTrue(e.getMessage().contains("int"));
    }

    @Test
    void overflowThrows() {
        assertThrows(IllegalArgumentException.class, () -> ArgConverter.convert("3000000000", int.class));
        assertThrows(IllegalArgumentException.class, () -> ArgConverter.convert("[3000000000]", int[].class));
        assertThrows(IllegalArgumentException.class, () -> ArgConverter.convert("2.5", int.class));
    }

    @Test
    void badCharThrows() {
        assertThrows(IllegalArgumentException.class, () -> ArgConverter.convert("\"xy\"", char.class));
    }
}
