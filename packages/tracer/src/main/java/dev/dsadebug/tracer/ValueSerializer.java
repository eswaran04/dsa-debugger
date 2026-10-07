package dev.dsadebug.tracer;

import com.sun.jdi.ArrayReference;
import com.sun.jdi.ArrayType;
import com.sun.jdi.BooleanValue;
import com.sun.jdi.ByteValue;
import com.sun.jdi.CharValue;
import com.sun.jdi.DoubleValue;
import com.sun.jdi.Field;
import com.sun.jdi.FloatValue;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PrimitiveValue;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.ShortValue;
import com.sun.jdi.StringReference;
import com.sun.jdi.Value;
import dev.dsadebug.json.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * Serializes JDI values to the shared Value JSON by reading debuggee fields directly.
 * Never invokes methods in the debuggee.
 */
public final class ValueSerializer {
    private static final long MAX_SAFE = 9007199254740992L; // 2^53

    private final int maxElems;
    private final int maxDepth;

    public ValueSerializer(int maxElems, int maxDepth) {
        this.maxElems = maxElems;
        this.maxDepth = maxDepth;
    }

    public void write(Json.Writer w, Value v) {
        write(w, v, 0);
    }

    private void write(Json.Writer w, Value v, int depth) {
        if (v == null) {
            w.beginObj().key("t").str("null").endObj();
        } else if (v instanceof PrimitiveValue p) {
            writePrimitive(w, p);
        } else if (v instanceof StringReference s) {
            w.beginObj().key("t").str("str").key("id").num(s.uniqueID()).key("v").str(s.value()).endObj();
        } else if (v instanceof ArrayReference arr) {
            writeArray(w, arr, depth);
        } else if (v instanceof ObjectReference o) {
            writeObject(w, o, depth);
        } else {
            w.beginObj().key("t").str("void").endObj(); // VoidValue
        }
    }

    private void prim(Json.Writer w, String t) {
        w.beginObj().key("t").str(t).key("v");
    }

    private void writePrimitive(Json.Writer w, PrimitiveValue p) {
        if (p instanceof IntegerValue x) {
            prim(w, "int");
            w.num(x.value());
        } else if (p instanceof LongValue x) {
            prim(w, "long");
            long n = x.value();
            if (n > MAX_SAFE || n < -MAX_SAFE) w.str(Long.toString(n));
            else w.num(n);
        } else if (p instanceof DoubleValue x) {
            prim(w, "double");
            w.num(x.value());
        } else if (p instanceof FloatValue x) {
            prim(w, "float");
            w.num(Double.parseDouble(Float.toString(x.value())));
        } else if (p instanceof BooleanValue x) {
            prim(w, "boolean");
            w.bool(x.value());
        } else if (p instanceof CharValue x) {
            prim(w, "char");
            w.str(String.valueOf(x.value()));
        } else if (p instanceof ByteValue x) {
            prim(w, "byte");
            w.num(x.value());
        } else if (p instanceof ShortValue x) {
            prim(w, "short");
            w.num(x.value());
        } else {
            throw new IllegalArgumentException("unknown primitive " + p);
        }
        w.endObj();
    }

    private void writeRef(Json.Writer w, ObjectReference o) {
        w.beginObj()
                .key("t").str("ref")
                .key("id").num(o.uniqueID())
                .key("cls").str(o.referenceType().name())
                .endObj();
    }

    private void writeArray(Json.Writer w, ArrayReference arr, int depth) {
        if (depth > maxDepth) {
            writeRef(w, arr);
            return;
        }
        int len = arr.length();
        int n = Math.min(len, maxElems);
        List<Value> vals = n == 0 ? List.of() : arr.getValues(0, n);
        w.beginObj()
                .key("t").str("array")
                .key("id").num(arr.uniqueID())
                .key("elem").str(((ArrayType) arr.referenceType()).componentTypeName())
                .key("len").num(len)
                .key("v");
        writeValues(w, vals, depth + 1);
        if (n < len) w.key("truncated").bool(true);
        w.endObj();
    }

    private void writeValues(Json.Writer w, List<Value> vals, int childDepth) {
        w.beginArr();
        for (Value e : vals) write(w, e, childDepth);
        w.endArr();
    }

    // ---- objects ----

    private void writeObject(Json.Writer w, ObjectReference o, int depth) {
        ReferenceType rt = o.referenceType();
        String cls = rt.name();

        Value boxed = unbox(o, cls);
        if (boxed != null) {
            writePrimitive(w, (PrimitiveValue) boxed);
            return;
        }
        if (depth > maxDepth) {
            writeRef(w, o);
            return;
        }
        switch (cls) {
            case "java.util.ArrayList" -> {
                int size = intField(o, "size");
                ArrayReference data = arrayField(o, "elementData");
                int n = Math.min(size, maxElems);
                writeList(w, o, "list", size, data == null || n == 0 ? List.of() : data.getValues(0, n), depth);
            }
            case "java.util.LinkedList" -> {
                int size = intField(o, "size");
                List<Value> items = new ArrayList<>();
                ObjectReference node = objField(o, "first");
                while (node != null && items.size() < maxElems) {
                    items.add(field(node, "item"));
                    node = objField(node, "next");
                }
                writeList(w, o, "list", size, items, depth);
            }
            case "java.util.ArrayDeque" -> writeDeque(w, o, depth);
            case "java.util.HashSet", "java.util.LinkedHashSet" -> {
                ObjectReference map = objField(o, "map");
                List<Value[]> entries = map == null ? List.of() : hashEntries(map);
                List<Value> keys = new ArrayList<>();
                for (Value[] kv : entries) keys.add(kv[0]);
                writeList(w, o, "set", map == null ? 0 : intField(map, "size"), keys, depth);
            }
            case "java.util.HashMap", "java.util.LinkedHashMap" ->
                writeMap(w, o, intField(o, "size"), hashEntries(o), depth);
            case "java.util.ImmutableCollections$ListN" -> {
                ArrayReference els = arrayField(o, "elements");
                int len = els == null ? 0 : els.length();
                int n = Math.min(len, maxElems);
                writeList(w, o, "list", len, n == 0 ? List.of() : els.getValues(0, n), depth);
            }
            case "java.util.ImmutableCollections$List12" -> {
                List<Value> items = immutablePair(o);
                writeList(w, o, "list", items.size(), items, depth);
            }
            case "java.util.ImmutableCollections$SetN" -> {
                ArrayReference els = arrayField(o, "elements");
                List<Value> items = new ArrayList<>();
                int len = 0;
                if (els != null) {
                    for (Value e : els.getValues(0, els.length())) {
                        if (e == null) continue;
                        len++;
                        if (items.size() < maxElems) items.add(e);
                    }
                }
                writeList(w, o, "set", len, items, depth);
            }
            case "java.util.ImmutableCollections$Set12" -> {
                List<Value> items = immutablePair(o);
                writeList(w, o, "set", items.size(), items, depth);
            }
            case "java.util.ImmutableCollections$MapN" -> {
                ArrayReference table = arrayField(o, "table");
                List<Value[]> entries = new ArrayList<>();
                int len = 0;
                if (table != null) {
                    List<Value> slots = table.getValues(0, table.length());
                    for (int k = 0; k + 1 < slots.size(); k += 2) {
                        if (slots.get(k) == null) continue;
                        len++;
                        if (entries.size() < maxElems) entries.add(new Value[] {slots.get(k), slots.get(k + 1)});
                    }
                }
                writeMap(w, o, len, entries, depth);
            }
            case "java.util.ImmutableCollections$Map1" ->
                writeMap(w, o, 1, List.<Value[]>of(new Value[] {field(o, "k0"), field(o, "v0")}), depth);
            default -> writePlainObject(w, o, depth);
        }
    }

    private void writeList(Json.Writer w, ObjectReference o, String t, int len, List<Value> items, int depth) {
        w.beginObj()
                .key("t").str(t)
                .key("id").num(o.uniqueID())
                .key("cls").str(o.referenceType().name())
                .key("len").num(len)
                .key("v");
        writeValues(w, items, depth + 1);
        if (items.size() < len) w.key("truncated").bool(true);
        w.endObj();
    }

    private void writeMap(Json.Writer w, ObjectReference o, int len, List<Value[]> entries, int depth) {
        w.beginObj()
                .key("t").str("map")
                .key("id").num(o.uniqueID())
                .key("cls").str(o.referenceType().name())
                .key("len").num(len)
                .key("entries").beginArr();
        for (Value[] kv : entries) {
            w.beginArr();
            write(w, kv[0], depth + 1);
            write(w, kv[1], depth + 1);
            w.endArr();
        }
        w.endArr();
        if (entries.size() < len) w.key("truncated").bool(true);
        w.endObj();
    }

    private void writeDeque(Json.Writer w, ObjectReference o, int depth) {
        ArrayReference els = arrayField(o, "elements");
        int head = intField(o, "head");
        int tail = intField(o, "tail");
        List<Value> items = new ArrayList<>();
        int len = 0;
        if (els != null) {
            int cap = els.length();
            len = tail - head;
            if (len < 0) len += cap;
            int shown = Math.min(len, maxElems);
            // slots head .. head+shown-1 modulo cap (contiguous, or wrapped in two reads)
            int first = Math.min(shown, cap - head);
            if (first > 0) items.addAll(els.getValues(head, first));
            if (shown > first) items.addAll(els.getValues(0, shown - first));
        }
        writeList(w, o, "list", len, items, depth);
    }

    private void writePlainObject(Json.Writer w, ObjectReference o, int depth) {
        ReferenceType rt = o.referenceType();
        List<Field> fields = new ArrayList<>();
        for (Field f : rt.allFields()) if (!f.isStatic()) fields.add(f);
        w.beginObj()
                .key("t").str("obj")
                .key("id").num(o.uniqueID())
                .key("cls").str(rt.name())
                .key("fields").beginArr();
        for (Field f : fields) {
            w.beginObj().key("name").str(f.name()).key("value");
            write(w, o.getValue(f), depth + 1);
            w.endObj();
        }
        w.endArr().endObj();
    }

    // ---- helpers ----

    /** Returns the primitive inside a boxed value, or null if o is not a box type. */
    private static Value unbox(ObjectReference o, String cls) {
        switch (cls) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
                    "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean":
                return field(o, "value");
            default:
                return null;
        }
    }

    /** Entries of a HashMap/LinkedHashMap as {key, value}, capped at maxElems. */
    private List<Value[]> hashEntries(ObjectReference map) {
        List<Value[]> out = new ArrayList<>();
        if (map.referenceType().name().equals("java.util.LinkedHashMap")) {
            // insertion/access order via the doubly linked list
            ObjectReference e = objField(map, "head");
            while (e != null && out.size() < maxElems) {
                out.add(new Value[] {field(e, "key"), field(e, "value")});
                e = objField(e, "after");
            }
            return out;
        }
        ArrayReference table = arrayField(map, "table");
        if (table == null) return out;
        for (Value slot : table.getValues(0, table.length())) {
            ObjectReference node = (ObjectReference) slot;
            while (node != null && out.size() < maxElems) {
                out.add(new Value[] {field(node, "key"), field(node, "value")});
                node = objField(node, "next");
            }
            if (out.size() >= maxElems) break;
        }
        return out;
    }

    /** Elements e0/e1 of ImmutableCollections List12/Set12; e1 may be the EMPTY sentinel. */
    private static List<Value> immutablePair(ObjectReference o) {
        List<Value> items = new ArrayList<>();
        items.add(field(o, "e0"));
        Value e1 = field(o, "e1");
        if (e1 instanceof ObjectReference r && !isEmptySentinel(o, r)) items.add(e1);
        else if (e1 != null && !(e1 instanceof ObjectReference)) items.add(e1);
        return items;
    }

    private static boolean isEmptySentinel(ObjectReference holder, ObjectReference e1) {
        ReferenceType outer = holder.virtualMachine().classesByName("java.util.ImmutableCollections").stream()
                .findFirst().orElse(null);
        if (outer == null) return false;
        Field empty = outer.fieldByName("EMPTY");
        return empty != null && e1.equals(outer.getValue(empty));
    }

    private static Value field(ObjectReference o, String name) {
        Field f = o.referenceType().fieldByName(name);
        if (f == null) throw new IllegalStateException(o.referenceType().name() + " has no field " + name);
        return o.getValue(f);
    }

    private static int intField(ObjectReference o, String name) {
        return ((IntegerValue) field(o, name)).value();
    }

    private static ObjectReference objField(ObjectReference o, String name) {
        return (ObjectReference) field(o, name);
    }

    private static ArrayReference arrayField(ObjectReference o, String name) {
        return (ArrayReference) field(o, name);
    }
}
