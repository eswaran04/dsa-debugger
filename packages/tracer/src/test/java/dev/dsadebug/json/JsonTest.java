package dev.dsadebug.json;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesNested() {
        Object o = Json.parse("{\"a\":[1,-2.5,\"x\\\"y\",true,null]}");
        Map<?, ?> m = (Map<?, ?>) o;
        List<Object> expected = Arrays.asList(1L, -2.5, "x\"y", true, null);
        assertEquals(expected, m.get("a"));
    }

    @Test
    void rejectsTrailingGarbage() {
        assertThrows(Json.ParseException.class, () -> Json.parse("[1] x"));
    }

    @Test
    void writerEscapes() {
        Json.Writer w = new Json.Writer();
        w.beginObj().key("s").str("a\nb\u0001").endObj();
        assertEquals("{\"s\":\"a\\nb\\u0001\"}", w.toString());
    }

    @Test
    void writerCommasAndRaw() {
        Json.Writer w = new Json.Writer();
        w.beginArr().num(1L).num(2.5).bool(true).nul().raw("{}").beginArr().endArr().endArr();
        assertEquals("[1,2.5,true,null,{},[]]", w.toString());
        assertEquals(w.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length, w.size());
    }
}
