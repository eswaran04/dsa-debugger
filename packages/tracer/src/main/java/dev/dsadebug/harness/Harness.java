package dev.dsadebug.harness;

import dev.dsadebug.json.Json;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Debuggee entry point: loads Solution, converts the named args and invokes the entry method.
 * Exit codes: 0 success, 1 user exception, 3 harness/arg error.
 */
public final class Harness {
    private Harness() {}

    public static void main(String[] argv) throws Exception {
        if (argv.length != 1) {
            System.err.println("usage: Harness <invoke.json>");
            System.exit(3);
        }
        Map<?, ?> spec = (Map<?, ?>) Json.parse(Files.readString(Path.of(argv[0])));
        String methodName = (String) spec.get("method");
        List<String> paramNames = new ArrayList<>();
        for (Object o : (List<?>) spec.get("paramNames")) paramNames.add((String) o);
        Map<?, ?> rawArgs = (Map<?, ?>) spec.get("args");

        Class<?> cls = Class.forName("Solution");
        Method target = null;
        for (Method m : cls.getDeclaredMethods()) {
            if (!m.getName().equals(methodName) || m.getParameterCount() != paramNames.size()) continue;
            List<String> names = new ArrayList<>();
            for (Parameter p : m.getParameters()) names.add(p.getName());
            if (names.equals(paramNames)) {
                target = m;
                break;
            }
        }
        if (target == null) argError("no method " + methodName + " with parameters " + paramNames + " in Solution");

        Parameter[] params = target.getParameters();
        Object[] values = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            Object raw = rawArgs == null ? null : rawArgs.get(params[i].getName());
            if (!(raw instanceof String)) argError("missing argument " + params[i].getName());
            try {
                values[i] = ArgConverter.convert((String) raw, params[i].getParameterizedType());
            } catch (IllegalArgumentException e) {
                argError("argument " + params[i].getName() + ": " + e.getMessage());
            }
        }

        Object instance;
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            instance = ctor.newInstance();
        } catch (NoSuchMethodException e) {
            argError("Solution has no no-arg constructor");
            return;
        }
        target.setAccessible(true);
        try {
            target.invoke(instance, values);
        } catch (InvocationTargetException e) {
            System.out.flush();
            e.getCause().printStackTrace();
            System.exit(1);
        }
        // exit explicitly so non-daemon threads started by user code cannot keep the VM alive
        System.out.flush();
        System.exit(0);
    }

    private static void argError(String msg) {
        System.err.println("HARNESS_ARG_ERROR " + msg);
        System.exit(3);
    }
}
