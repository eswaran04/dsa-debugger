package dev.dsadebug.tracer;

import java.util.ArrayList;
import java.util.List;

/**
 * Mutable trace under construction. JDI values are serialized at capture time (while the debuggee
 * is suspended), so this holds only JSON fragments and plain data, never live JDI objects.
 */
final class TraceModel {
    String status = "ok";
    List<SourceCompiler.Diag> compileErrors; // set only for compile_error
    SourceCompiler.MethodSig entry;
    final List<Step> steps = new ArrayList<>();
    String resultJson; // serialized Value
    List<Var> finalArgs;
    ExceptionInfo exception;
    String error;
    String stdout = "";
    long ms;
    boolean truncated;

    /** A variable whose value is an already-serialized Value JSON object. */
    record Var(String name, String valueJson) {}

    record ExceptionInfo(String type, String message, int line) {}

    /** One recorded step; stackJson is the serialized Frame[] (top first). */
    static final class Step {
        final String event;
        final int line;
        final int depth;
        final String stackJson;
        final String method; // return steps only
        final String returnValueJson; // return steps only
        String stdout = "";

        Step(String event, int line, int depth, String stackJson, String method, String returnValueJson) {
            this.event = event;
            this.line = line;
            this.depth = depth;
            this.stackJson = stackJson;
            this.method = method;
            this.returnValueJson = returnValueJson;
        }
    }
}
