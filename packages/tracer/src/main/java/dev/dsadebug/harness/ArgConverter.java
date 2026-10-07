package dev.dsadebug.harness;

import dev.dsadebug.json.Json;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

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
        if (type instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) type;
            if (pt.getRawType() == List.class) {
                if (!(v instanceof List)) throw mismatch(type, v);
                Type elem = pt.getActualTypeArguments()[0];
                List<Object> out = new ArrayList<>();
                for (Object o : (List<?>) v) out.add(fromJson(o, elem));
                return out;
            }
            throw new IllegalArgumentException("unsupported type " + type.getTypeName());
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
        if (type == int[].class) {
            List<?> l = asList(v, type);
            int[] a = new int[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = toInt(l.get(i), int.class);
            return a;
        }
        if (type == int[][].class) {
            List<?> l = asList(v, type);
            int[][] a = new int[l.size()][];
            for (int i = 0; i < a.length; i++) a[i] = (int[]) fromJson(l.get(i), int[].class);
            return a;
        }
        if (type == char[][].class) {
            List<?> l = asList(v, type);
            char[][] a = new char[l.size()][];
            for (int i = 0; i < a.length; i++) {
                List<?> row = asList(l.get(i), type);
                a[i] = new char[row.size()];
                for (int j = 0; j < a[i].length; j++) a[i][j] = (Character) fromJson(row.get(j), char.class);
            }
            return a;
        }
        if (type == String[].class) {
            List<?> l = asList(v, type);
            String[] a = new String[l.size()];
            for (int i = 0; i < a.length; i++) a[i] = (String) fromJson(l.get(i), String.class);
            return a;
        }
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
