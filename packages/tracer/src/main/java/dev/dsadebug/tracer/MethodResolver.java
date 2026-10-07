package dev.dsadebug.tracer;

import dev.dsadebug.tracer.SourceCompiler.MethodSig;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Picks the entry method of Solution from the requested name and/or the named arguments. */
public final class MethodResolver {
    private MethodResolver() {}

    public static final class ResolveException extends RuntimeException {
        public ResolveException(String msg) {
            super(msg);
        }
    }

    public static MethodSig resolve(List<MethodSig> methods, String requested, List<String> argNames) {
        Set<String> wanted = new HashSet<>(argNames);
        if (requested != null) {
            for (MethodSig m : methods) {
                if (m.name().equals(requested)) {
                    if (new HashSet<>(m.paramNames()).equals(wanted)) return m;
                    throw new ResolveException("method " + requested + " takes (" + params(m)
                            + ") but the given args are " + argNames);
                }
            }
            throw new ResolveException("no method named " + requested + " in Solution. " + available(methods, argNames));
        }
        MethodSig best = null;
        for (MethodSig m : methods) {
            if (m.isPrivate() || !new HashSet<>(m.paramNames()).equals(wanted)) continue;
            if (best == null || (m.isPublic() && !best.isPublic())) best = m;
        }
        if (best != null) return best;
        throw new ResolveException("no method of Solution matches the args " + argNames + ". "
                + available(methods, argNames));
    }

    private static String available(List<MethodSig> methods, List<String> argNames) {
        List<String> sigs = new ArrayList<>();
        for (MethodSig m : methods) {
            if (!m.isPrivate()) sigs.add(m.name() + "(" + params(m) + ")");
        }
        return "Available methods: " + sigs + "; given arg names: " + argNames;
    }

    private static String params(MethodSig m) {
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < m.paramNames().size(); i++) parts.add(m.paramTypes().get(i) + " " + m.paramNames().get(i));
        return String.join(", ", parts);
    }
}
