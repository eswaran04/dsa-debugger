package dev.dsadebug.harness;

import dev.dsadebug.json.Json;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

/** Converts JSON text into Java values for the supported solution argument types. */
public final class ArgConverter {
    private ArgConverter() {}

    public static Object convert(String raw, Type type) {
        Object json;
        try {
            json = Json.parse(raw);
        } catch (Json.ParseException e) {
            throw new IllegalArgumentException("expected " + type.getTypeName() + " but got invalid JSON: " + raw, e);
        }
        return fromJson(json, type);
    }

    private static Object fromJson(Object v, Type type) {
        if (v == null && !(type instanceof Class<?> c && c.isPrimitive())) return null;
        if (type instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> raw
                && Collection.class.isAssignableFrom(raw)) {
            Type elem = pt.getActualTypeArguments()[0];
            Collection<Object> out = newCollection(raw, type);
            for (Object o : asList(v, type)) out.add(fromJson(o, elem));
            return out;
        }
        if (type instanceof Class<?> c && c.isArray()) {
            List<?> l = asList(v, type);
            Class<?> comp = c.getComponentType();
            Object a = Array.newInstance(comp, l.size());
            for (int i = 0; i < l.size(); i++) Array.set(a, i, fromJson(l.get(i), comp));
            return a;
        }
        if (type == int.class || type == Integer.class) return toInt(v, type);
        if (type == long.class || type == Long.class) {
            if (v instanceof Long) return v;
            throw mismatch(type, v);
        }
        if (type == double.class || type == Double.class) {
            if (v instanceof Long) return ((Long) v).doubleValue();
            if (v instanceof Double) return v;
            throw mismatch(type, v);
        }
        if (type == float.class || type == Float.class) {
            if (v instanceof Long) return ((Long) v).floatValue();
            if (v instanceof Double) return ((Double) v).floatValue();
            throw mismatch(type, v);
        }
        if (type == boolean.class || type == Boolean.class) {
            if (v instanceof Boolean) return v;
            throw mismatch(type, v);
        }
        if (type == char.class || type == Character.class) {
            if (v instanceof String && ((String) v).length() == 1) return ((String) v).charAt(0);
            throw mismatch(type, v);
        }
        if (type == String.class) {
            if (v instanceof String) return v;
            throw mismatch(type, v);
        }
        throw new IllegalArgumentException("unsupported type " + type.getTypeName());
    }

    /** A mutable instance for a declared collection type: interfaces map to the usual implementation. */
    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollection(Class<?> raw, Type type) {
        if (!raw.isInterface() && !Modifier.isAbstract(raw.getModifiers())) {
            try {
                return (Collection<Object>) raw.getConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException("unsupported type " + type.getTypeName(), e);
            }
        }
        if (SortedSet.class.isAssignableFrom(raw)) return new TreeSet<>();
        if (Set.class.isAssignableFrom(raw)) return new LinkedHashSet<>();
        if (Queue.class.isAssignableFrom(raw)) return new ArrayDeque<>();
        if (raw.isAssignableFrom(ArrayList.class)) return new ArrayList<>(); // List, Collection, Iterable
        throw new IllegalArgumentException("unsupported type " + type.getTypeName());
    }

    private static Integer toInt(Object v, Type type) {
        if (v instanceof Long) {
            long l = (Long) v;
            if (l < Integer.MIN_VALUE || l > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("expected int but got out-of-range value " + l);
            }
            return (int) l;
        }
        throw mismatch(type == Integer.class ? int.class : type, v);
    }

    private static List<?> asList(Object v, Type type) {
        if (v instanceof List) return (List<?>) v;
        throw mismatch(type, v);
    }

    private static IllegalArgumentException mismatch(Type type, Object v) {
        return new IllegalArgumentException("expected " + type.getTypeName() + " but got " + describe(v));
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        if (v instanceof List) return "array " + v;
        if (v instanceof java.util.Map) return "object";
        if (v instanceof String) return "string \"" + v + "\"";
        return v.getClass().getSimpleName().toLowerCase() + " " + v;
    }
}
