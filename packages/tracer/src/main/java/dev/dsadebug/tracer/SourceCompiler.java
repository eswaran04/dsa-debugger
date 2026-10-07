package dev.dsadebug.tracer;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePathScanner;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Compiles the user's source as Solution.java and extracts the method signatures of class Solution. */
public final class SourceCompiler {
    private SourceCompiler() {}

    public record Diag(long line, long col, String message) {}

    public record MethodSig(
            String name, boolean isPublic, boolean isPrivate, List<String> paramNames, List<String> paramTypes) {}

    public record CompileResult(boolean ok, List<Diag> diags, List<String> userClasses, List<MethodSig> methods) {}

    public static CompileResult compile(String code, Path outDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("no system Java compiler (JDK required)");
        return compile(compiler, code, outDir);
    }

    public static CompileResult compile(JavaCompiler compiler, String code, Path outDir) {
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        JavaFileObject source = new SimpleJavaFileObject(URI.create("string:///Solution.java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return code;
            }
        };
        List<String> options = Arrays.asList("-g", "-parameters", "-proc:none", "-d", outDir.toString());
        List<MethodSig> methods = new ArrayList<>();
        boolean ok;
        try {
            Files.createDirectories(outDir);
            try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null, null)) {
                JavacTask task = (JavacTask) compiler.getTask(null, fm, collector, options, null, List.of(source));
                try {
                    for (CompilationUnitTree unit : task.parse()) collectMethods(unit, methods);
                    task.analyze();
                    task.generate();
                } catch (IOException e) {
                    throw new IllegalStateException("compile failed: " + e.getMessage(), e);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("compile failed: " + e.getMessage(), e);
        }
        List<Diag> diags = new ArrayList<>();
        for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
            if (d.getKind() == Diagnostic.Kind.ERROR) {
                diags.add(new Diag(d.getLineNumber(), d.getColumnNumber(), d.getMessage(null)));
            }
        }
        ok = diags.isEmpty();
        return new CompileResult(ok, diags, listClasses(outDir), methods);
    }

    private static void collectMethods(CompilationUnitTree unit, List<MethodSig> out) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitClass(ClassTree cls, Void v) {
                boolean topLevelSolution =
                        getCurrentPath().getParentPath().getLeaf() == unit && cls.getSimpleName().contentEquals("Solution");
                if (!topLevelSolution) return null; // skip nested and other top-level classes
                for (var member : cls.getMembers()) {
                    if (!(member instanceof MethodTree)) continue;
                    MethodTree mt = (MethodTree) member;
                    if (mt.getName().contentEquals("<init>")) continue;
                    List<String> names = new ArrayList<>();
                    List<String> types = new ArrayList<>();
                    for (VariableTree p : mt.getParameters()) {
                        names.add(p.getName().toString());
                        types.add(p.getType().toString());
                    }
                    var mods = mt.getModifiers().getFlags();
                    out.add(new MethodSig(
                            mt.getName().toString(),
                            mods.contains(Modifier.PUBLIC),
                            mods.contains(Modifier.PRIVATE),
                            names,
                            types));
                }
                return null;
            }
        }.scan(unit, null);
    }

    private static List<String> listClasses(Path outDir) {
        if (!Files.isDirectory(outDir)) return Collections.emptyList();
        try (Stream<Path> s = Files.walk(outDir)) {
            List<String> names = new ArrayList<>();
            s.filter(p -> p.toString().endsWith(".class")).forEach(p -> {
                String rel = outDir.relativize(p).toString();
                names.add(rel.substring(0, rel.length() - ".class".length()).replace(java.io.File.separatorChar, '.'));
            });
            Collections.sort(names);
            return names;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
