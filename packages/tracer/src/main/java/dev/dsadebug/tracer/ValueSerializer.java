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
            double d = x.value();
            if (Double.isFinite(d)) w.num(d);
            else w.str(Double.toString(d)); // "NaN", "Infinity", "-Infinity"
        } else if (p instanceof FloatValue x) {
            prim(w, "float");
            float fl = x.value();
            if (Float.isFinite(fl)) w.num(Double.parseDouble(Float.toString(fl)));
            else w.str(Float.toString(fl));
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

    /** Thrown when an expected JDK-internal field is absent; callers fall back to a plain obj. */
    private static final class MissingField extends RuntimeException {
        MissingField(String m) {
            super(m, null, false, false);
        }
    }

    private static final int CHUNK = 256;

    private final java.util.Map<ReferenceType, java.util.Map<String, Field>> fieldsByName =
            new java.util.HashMap<>();
    private final java.util.Map<ReferenceType, List<Field>> instanceFields = new java.util.HashMap<>();
    private boolean sentinelResolved;
    private ObjectReference emptySentinel;

    private void writeObject(Json.Writer w, ObjectReference o, int depth) {
        ReferenceType rt = o.referenceType();
        String cls = rt.name();

        Value boxed = unbox(o, cls);
        if (boxed instanceof PrimitiveValue pv) {
            writePrimitive(w, pv);
            return;
        }
        if (depth > maxDepth) {
            writeRef(w, o);
            return;
        }
        try {
            if (!writeKnownCollection(w, o, cls, depth)) writePlainObject(w, o, depth);
        } catch (MissingField e) {
            // all reads happen before any output for this value, so falling back is safe
            writePlainObject(w, o, depth);
        }
    }

    /** Returns false if cls is not a recognized collection. */
    private boolean writeKnownCollection(Json.Writer w, ObjectReference o, String cls, int depth) {
        switch (cls) {
            case "java.util.ArrayList" -> {
                Value[] f = get(o, "size", "elementData");
                int size = asInt(f[0]);
                ArrayReference data = (ArrayReference) f[1];
                int n = Math.min(size, maxElems);
                writeList(w, o, "list", size, data == null || n == 0 ? List.of() : data.getValues(0, n), depth);
            }
            case "java.util.LinkedList" -> {
                Value[] f = get(o, "size", "first");
                List<Value> items = new ArrayList<>();
                ObjectReference node = (ObjectReference) f[1];
                while (node != null && items.size() < maxElems) {
                    Value[] nv = get(node, "item", "next");
                    items.add(nv[0]);
                    node = (ObjectReference) nv[1];
                }
                writeList(w, o, "list", asInt(f[0]), items, depth);
            }
            case "java.util.ArrayDeque" -> writeDeque(w, o, depth);
            case "java.util.HashSet", "java.util.LinkedHashSet" -> {
                ObjectReference map = (ObjectReference) get(o, "map")[0];
                List<Value> keys = new ArrayList<>();
                int size = 0;
                if (map != null) {
                    size = asInt(get(map, "size")[0]);
                    for (Value[] kv : hashEntries(map)) keys.add(kv[0]);
                }
                writeList(w, o, "set", size, keys, depth);
            }
            case "java.util.HashMap", "java.util.LinkedHashMap" -> {
                int size = asInt(get(o, "size")[0]);
                writeMap(w, o, size, hashEntries(o), depth);
            }
            case "java.util.ImmutableCollections$ListN" -> {
                ArrayReference els = (ArrayReference) get(o, "elements")[0];
                int len = els == null ? 0 : els.length();
                int n = Math.min(len, maxElems);
                writeList(w, o, "list", len, n == 0 ? List.of() : els.getValues(0, n), depth);
            }
            case "java.util.ImmutableCollections$List12" -> {
                List<Value> items = immutablePair(o);
                writeList(w, o, "list", items.size(), items, depth);
            }
            case "java.util.ImmutableCollections$SetN" -> {
                Value[] f = get(o, "elements", "size");
                ArrayReference els = (ArrayReference) f[0];
                List<Value> items = new ArrayList<>();
                if (els != null) {
                    int len = els.length();
                    for (int from = 0; from < len && items.size() < maxElems; from += CHUNK) {
                        for (Value e : els.getValues(from, Math.min(CHUNK, len - from))) {
                            if (e != null && items.size() < maxElems) items.add(e);
                        }
                    }
                }
                writeList(w, o, "set", asInt(f[1]), items, depth);
            }
            case "java.util.ImmutableCollections$Set12" -> {
                List<Value> items = immutablePair(o);
                writeList(w, o, "set", items.size(), items, depth);
            }
            case "java.util.ImmutableCollections$MapN" -> {
                Value[] f = get(o, "table", "size");
                ArrayReference table = (ArrayReference) f[0];
                List<Value[]> entries = new ArrayList<>();
                if (table != null) {
                    int len = table.length();
                    for (int from = 0; from + 1 < len && entries.size() < maxElems; from += CHUNK) {
                        List<Value> slots = table.getValues(from, Math.min(CHUNK, len - from));
                        for (int k = 0; k + 1 < slots.size() && entries.size() < maxElems; k += 2) {
                            if (slots.get(k) != null) entries.add(new Value[] {slots.get(k), slots.get(k + 1)});
                        }
                    }
                }
                writeMap(w, o, asInt(f[1]), entries, depth);
            }
            case "java.util.ImmutableCollections$Map1" -> {
                Value[] f = get(o, "k0", "v0");
                writeMap(w, o, 1, List.<Value[]>of(new Value[] {f[0], f[1]}), depth);
            }
            default -> {
                return false;
            }
        }
        return true;
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
        Value[] f = get(o, "elements", "head", "tail");
        ArrayReference els = (ArrayReference) f[0];
        int head = asInt(f[1]);
        int tail = asInt(f[2]);
        List<Value> items = new ArrayList<>();
        int len = 0;
        if (els != null) {
            int cap = els.length();
            len = tail - head;
            if (len < 0) len += cap;
            int shown = Math.min(len, maxElems);
            int first = Math.min(shown, cap - head);
            if (first > 0) items.addAll(els.getValues(head, first));
            if (shown > first) items.addAll(els.getValues(0, shown - first));
        }
        writeList(w, o, "list", len, items, depth);
    }

    private void writePlainObject(Json.Writer w, ObjectReference o, int depth) {
        ReferenceType rt = o.referenceType();
        List<Field> fields = instanceFields.computeIfAbsent(rt, t -> {
            List<Field> out = new ArrayList<>();
            for (Field f : t.allFields()) if (!f.isStatic()) out.add(f);
            return out;
        });
        java.util.Map<Field, Value> vals = fields.isEmpty() ? java.util.Map.of() : o.getValues(fields);
        w.beginObj()
                .key("t").str("obj")
                .key("id").num(o.uniqueID())
                .key("cls").str(rt.name())
                .key("fields").beginArr();
        for (Field f : fields) {
            w.beginObj().key("name").str(f.name()).key("value");
            write(w, vals.get(f), depth + 1);
            w.endObj();
        }
        w.endArr().endObj();
    }

    // ---- helpers ----

    /** Returns the primitive inside a boxed value, or null if o is not a box type. */
    private Value unbox(ObjectReference o, String cls) {
        switch (cls) {
            case "java.lang.Integer", "java.lang.Long", "java.lang.Double", "java.lang.Float",
                    "java.lang.Short", "java.lang.Byte", "java.lang.Character", "java.lang.Boolean":
                try {
                    return get(o, "value")[0];
                } catch (MissingField e) {
                    return null;
                }
            default:
                return null;
        }
    }

    /** Entries of a HashMap/LinkedHashMap as {key, value}, capped at maxElems, reading bounded chunks. */
    private List<Value[]> hashEntries(ObjectReference map) {
        List<Value[]> out = new ArrayList<>();
        if (map.referenceType().name().equals("java.util.LinkedHashMap")) {
            // insertion/access order via the doubly linked list
            ObjectReference e = (ObjectReference) get(map, "head")[0];
            while (e != null && out.size() < maxElems) {
                Value[] nv = get(e, "key", "value", "after");
                out.add(new Value[] {nv[0], nv[1]});
                e = (ObjectReference) nv[2];
            }
            return out;
        }
        ArrayReference table = (ArrayReference) get(map, "table")[0];
        if (table == null) return out;
        int len = table.length();
        for (int from = 0; from < len && out.size() < maxElems; from += CHUNK) {
            for (Value slot : table.getValues(from, Math.min(CHUNK, len - from))) {
                ObjectReference node = (ObjectReference) slot;
                while (node != null && out.size() < maxElems) {
                    Value[] nv = get(node, "key", "value", "next");
                    out.add(new Value[] {nv[0], nv[1]});
                    node = (ObjectReference) nv[2];
                }
                if (out.size() >= maxElems) break;
            }
        }
        return out;
    }

    /** Elements e0/e1 of ImmutableCollections List12/Set12; e1 may be the EMPTY sentinel. */
    private List<Value> immutablePair(ObjectReference o) {
        Value[] f = get(o, "e0", "e1");
        List<Value> items = new ArrayList<>();
        items.add(f[0]);
        if (f[1] instanceof ObjectReference r) {
            if (!r.equals(emptySentinel(o))) items.add(r);
        } else if (f[1] != null) {
            items.add(f[1]);
        }
        return items;
    }

    private ObjectReference emptySentinel(ObjectReference holder) {
        if (!sentinelResolved) {
            sentinelResolved = true;
            for (ReferenceType outer : holder.virtualMachine().classesByName("java.util.ImmutableCollections")) {
                Field empty = outer.fieldByName("EMPTY");
                if (empty != null && outer.getValue(empty) instanceof ObjectReference r) emptySentinel = r;
            }
        }
        return emptySentinel;
    }

    /** Reads the named fields in one JDWP round trip; field lookups are cached per type. */
    private Value[] get(ObjectReference o, String... names) {
        ReferenceType rt = o.referenceType();
        java.util.Map<String, Field> byName = fieldsByName.computeIfAbsent(rt, t -> {
            java.util.Map<String, Field> m = new java.util.HashMap<>();
            for (Field f : t.allFields()) m.putIfAbsent(f.name(), f);
            return m;
        });
        List<Field> fs = new ArrayList<>(names.length);
        for (String n : names) {
            Field f = byName.get(n);
            if (f == null) throw new MissingField(rt.name() + " has no field " + n);
            fs.add(f);
        }
        java.util.Map<Field, Value> vals = o.getValues(fs);
        Value[] out = new Value[names.length];
        for (int k = 0; k < out.length; k++) out[k] = vals.get(fs.get(k));
        return out;
    }

    private static int asInt(Value v) {
        if (v instanceof IntegerValue i) return i.value();
        throw new MissingField("expected int field");
    }
}
