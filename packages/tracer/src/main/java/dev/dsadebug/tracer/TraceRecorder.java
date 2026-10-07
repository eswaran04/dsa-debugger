package dev.dsadebug.tracer;

import com.sun.jdi.AbsentInformationException;
import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.Field;
import com.sun.jdi.IncompatibleThreadStateException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.Method;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.StringReference;
import com.sun.jdi.ThreadReference;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.IllegalConnectorArgumentsException;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.connect.VMStartException;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.StepRequest;
import dev.dsadebug.json.Json;
import dev.dsadebug.tracer.SourceCompiler.CompileResult;
import dev.dsadebug.tracer.SourceCompiler.MethodSig;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/** Compiles the user's Solution, runs it under JDI and records a line-by-line trace as Trace JSON. */
public final class TraceRecorder {
    static final String NO_JDK = "A JDK is required (javac not available) — a JRE is not enough.";
    private static final String DEBUGGEE_FLAGS = "-Xmx256m -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC "
            + "-XX:TieredStopAtLevel=1 -XX:ActiveProcessorCount=1 -Xss16m";
    private static final List<String> STEP_EXCLUDES =
            List.of("java.*", "javax.*", "jdk.*", "sun.*", "com.sun.*", "dev.dsadebug.*");
    private static final int MAX_STACK = 64;
    private static final int STDERR_CAP = 65536;

    private final Supplier<JavaCompiler> compilers;

    public TraceRecorder() {
        this(ToolProvider::getSystemJavaCompiler);
    }

    public TraceRecorder(Supplier<JavaCompiler> compilers) {
        this.compilers = compilers;
    }

    /** Returns the Trace JSON for the job. Every outcome of the user's code is a trace, not an exception. */
    public String run(Job job) {
        long start = System.nanoTime();
        TraceModel m = new TraceModel();
        JavaCompiler compiler = compilers.get();
        if (compiler == null) {
            m.status = "runtime_error";
            m.error = NO_JDK;
        } else {
            Path work = null;
            try {
                work = Files.createTempDirectory("dsatrace");
                trace(compiler, job, work, m);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } finally {
                if (work != null) deleteTree(work);
            }
        }
        m.ms = (System.nanoTime() - start) / 1_000_000;
        return TraceWriter.write(m, job.limits());
    }

    private static void trace(JavaCompiler compiler, Job job, Path work, TraceModel m) throws IOException {
        Path classes = work.resolve("classes");
        CompileResult cr = SourceCompiler.compile(compiler, job.code(), classes);
        if (!cr.ok()) {
            m.status = "compile_error";
            m.compileErrors = cr.diags();
            return;
        }
        MethodSig sig;
        try {
            sig = MethodResolver.resolve(cr.methods(), job.method(), job.args().stream().map(Job.Arg::name).toList());
        } catch (MethodResolver.ResolveException e) {
            m.status = "runtime_error";
            m.error = e.getMessage();
            return;
        }
        m.entry = sig;
        Path invoke = work.resolve("invoke.json");
        Files.writeString(invoke, invokeJson(sig, job.args()));
        new Session(job, sig, Set.copyOf(cr.userClasses()), m).run(classpath(classes), invoke);
    }

    /** The Harness input: {method, paramNames, args: {name: raw}}. */
    private static String invokeJson(MethodSig sig, List<Job.Arg> args) {
        Json.Writer w = new Json.Writer();
        w.beginObj().key("method").str(sig.name()).key("paramNames").beginArr();
        for (String p : sig.paramNames()) w.str(p);
        w.endArr().key("args").beginObj();
        for (Job.Arg a : args) w.key(a.name()).str(a.raw());
        return w.endObj().endObj().toString();
    }

    /** Debuggee classpath: the tracer's own classes (for Harness) plus the user's compiled classes. */
    private static String classpath(Path classes) {
        String env = System.getenv("TRACER_JAR");
        Path tracer;
        if (env != null && Files.isRegularFile(Path.of(env))) {
            tracer = Path.of(env).toAbsolutePath();
        } else {
            try {
                tracer = Path.of(TraceRecorder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            } catch (URISyntaxException e) {
                throw new IllegalStateException(e);
            }
        }
        return tracer + File.pathSeparator + classes.toAbsolutePath();
    }

    private static void deleteTree(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort: the OS temp dir is cleaned eventually
        }
    }

    /** One debuggee run: launches the VM, drives the JDI event loop and fills the model. */
    private static final class Session {
        private final Job job;
        private final MethodSig sig;
        private final Set<String> userClasses;
        private final TraceModel m;
        private final ValueSerializer serializer = new ValueSerializer(1000, 3); // caches are per VM
        /** fids of the live user frames, top first. */
        private final Deque<Integer> fids = new ArrayDeque<>();
        private final List<EventRequest> traceRequests = new ArrayList<>();
        private int fidCounter;
        private VirtualMachine vm;
        private EventRequestManager erm;
        private OutputPump out;
        private OutputPump err;
        private Method entryMethod;
        private int entryFrameCount;
        private boolean entered;
        private boolean done;
        /** Set by an exception: user frames may have been popped without MethodExit events. */
        private boolean unwindPending;
        private PendingException pending;

        private record PendingException(String type, String message, int line, int depth, String stackJson) {}

        Session(Job job, MethodSig sig, Set<String> userClasses, TraceModel m) {
            this.job = job;
            this.sig = sig;
            this.userClasses = userClasses;
            this.m = m;
        }

        void run(String cp, Path invoke) {
            LaunchingConnector cn = Bootstrap.virtualMachineManager().defaultConnector();
            Map<String, Connector.Argument> a = cn.defaultArguments();
            a.get("home").setValue(System.getProperty("java.home"));
            a.get("options").setValue(DEBUGGEE_FLAGS + " -cp " + quote(cp));
            a.get("main").setValue("dev.dsadebug.harness.Harness " + quote(invoke.toString()));
            try {
                vm = cn.launch(a);
            } catch (IOException | IllegalConnectorArgumentsException | VMStartException e) {
                throw new IllegalStateException("could not launch the debuggee: " + e.getMessage(), e);
            }
            Process p = vm.process();
            try {
                out = new OutputPump(p.getInputStream(), job.limits().maxStdoutBytes(), "\n[output truncated]");
                err = new OutputPump(p.getErrorStream(), STDERR_CAP, null);
                erm = vm.eventRequestManager();
                ClassPrepareRequest cpr = erm.createClassPrepareRequest();
                cpr.addClassFilter("Solution");
                enable(cpr);
                vm.resume();
                loop();
                if (!p.waitFor(5, TimeUnit.SECONDS)) throw new IllegalStateException("debuggee did not exit");
                finish(p.exitValue());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            } finally {
                if (out != null) out.close();
                if (err != null) err.close();
                if (p.isAlive()) {
                    p.destroyForcibly();
                    try {
                        p.waitFor(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private static String quote(String s) {
            return "\"" + s + "\"";
        }

        private static void enable(EventRequest r) {
            r.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
            r.enable();
        }

        /** Events at one location arrive as one set; handle entries before steps before exits. */
        private static int order(Event e) {
            if (e instanceof MethodEntryEvent) return 0;
            if (e instanceof MethodExitEvent) return 2;
            return 1;
        }

        private void loop() throws InterruptedException {
            while (true) {
                EventSet set;
                try {
                    set = vm.eventQueue().remove();
                } catch (VMDisconnectedException e) {
                    return;
                }
                List<Event> events = new ArrayList<>(set);
                events.sort(Comparator.comparingInt(Session::order));
                try {
                    for (Event e : events) {
                        if (e instanceof VMDisconnectEvent) return;
                        handle(e);
                    }
                    set.resume();
                } catch (VMDisconnectedException e) {
                    return;
                } catch (IncompatibleThreadStateException e) {
                    throw new IllegalStateException("event thread not suspended", e);
                }
            }
        }

        private void handle(Event e) throws IncompatibleThreadStateException {
            if (e instanceof ClassPrepareEvent cp) {
                onPrepare(cp);
            } else if (e instanceof BreakpointEvent bp) {
                onEntry(bp);
            } else if (!entered || done) {
                // before the entry breakpoint or after the entry method returned
            } else if (e instanceof MethodEntryEvent me) {
                if (unwindPending) {
                    reconcile(userFrames(me.thread()).size() - 1);
                    unwindPending = false;
                }
                fids.push(++fidCounter);
            } else if (e instanceof StepEvent se) {
                if (userClasses.contains(se.location().declaringType().name())) recordLine(se.thread());
            } else if (e instanceof MethodExitEvent mx) {
                onExit(mx);
            } else if (e instanceof ExceptionEvent ex) {
                onException(ex);
            }
        }

        private void onPrepare(ClassPrepareEvent cp) {
            erm.deleteEventRequest(cp.request());
            entryMethod = findEntry(cp.referenceType());
            if (entryMethod == null || entryMethod.location() == null) return; // Harness reports the mismatch
            enable(erm.createBreakpointRequest(entryMethod.location()));
        }

        private Method findEntry(ReferenceType rt) {
            for (Method mm : rt.methodsByName(sig.name())) {
                try {
                    if (mm.arguments().stream().map(LocalVariable::name).toList().equals(sig.paramNames())) return mm;
                } catch (AbsentInformationException e) {
                    if (mm.argumentTypeNames().size() == sig.paramNames().size()) return mm;
                }
            }
            return null;
        }

        private void onEntry(BreakpointEvent bp) throws IncompatibleThreadStateException {
            erm.deleteEventRequest(bp.request()); // the entry method may recurse
            ThreadReference t = bp.thread();
            entered = true;
            entryFrameCount = t.frameCount();
            reconcile(userFrames(t).size());
            if (job.record()) {
                for (String c : userClasses) {
                    var en = erm.createMethodEntryRequest();
                    en.addClassFilter(c);
                    en.addThreadFilter(t);
                    traceRequest(en);
                    var ex = erm.createMethodExitRequest();
                    ex.addClassFilter(c);
                    ex.addThreadFilter(t);
                    traceRequest(ex);
                }
                StepRequest step = erm.createStepRequest(t, StepRequest.STEP_LINE, StepRequest.STEP_INTO);
                for (String x : STEP_EXCLUDES) step.addClassExclusionFilter(x);
                traceRequest(step);
            } else {
                var ex = erm.createMethodExitRequest();
                ex.addClassFilter("Solution");
                ex.addThreadFilter(t);
                traceRequest(ex);
            }
            var exc = erm.createExceptionRequest(null, true, true);
            exc.addThreadFilter(t);
            traceRequest(exc);
            if (job.record()) recordLine(t);
        }

        private void traceRequest(EventRequest r) {
            enable(r);
            traceRequests.add(r);
        }

        private void recordLine(ThreadReference t) throws IncompatibleThreadStateException {
            List<StackFrame> uf = userFrames(t);
            reconcile(uf.size());
            addStep(new TraceModel.Step("line", uf.get(0).location().lineNumber(), uf.size(), stackJson(uf), null, null));
        }

        private void addStep(TraceModel.Step s) {
            s.stdout = out.takeNew();
            m.steps.add(s);
        }

        private void onExit(MethodExitEvent mx) throws IncompatibleThreadStateException {
            ThreadReference t = mx.thread();
            boolean isEntry = mx.method().equals(entryMethod) && t.frameCount() == entryFrameCount;
            if (job.record()) {
                List<StackFrame> uf = userFrames(t);
                reconcile(uf.size());
                addStep(new TraceModel.Step("return", mx.location().lineNumber(), uf.size(), stackJson(uf),
                        mx.method().name(), valueJson(mx.returnValue())));
                fids.poll();
            }
            if (!isEntry) return;
            m.resultJson = valueJson(mx.returnValue());
            List<Value> args = t.frame(0).getArgumentValues();
            m.finalArgs = new ArrayList<>();
            for (int i = 0; i < args.size() && i < sig.paramNames().size(); i++) {
                m.finalArgs.add(new TraceModel.Var(sig.paramNames().get(i), valueJson(args.get(i))));
            }
            erm.deleteEventRequests(traceRequests);
            done = true;
        }

        private void onException(ExceptionEvent ex) throws IncompatibleThreadStateException {
            unwindPending = true;
            List<StackFrame> uf = userFrames(ex.thread());
            if (uf.isEmpty()) return; // e.g. the InvocationTargetException wrapper in Harness
            reconcile(uf.size());
            ObjectReference exc = ex.exception();
            pending = new PendingException(exc.referenceType().name(), detailMessage(exc),
                    uf.get(0).location().lineNumber(), uf.size(), job.record() ? stackJson(uf) : null);
        }

        private static String detailMessage(ObjectReference exc) {
            for (ReferenceType t = exc.referenceType(); t instanceof ClassType ct; t = ct.superclass()) {
                Field f = ct.fieldByName("detailMessage");
                if (f != null && f.declaringType().name().equals("java.lang.Throwable")) {
                    Value v = exc.getValue(f);
                    return v instanceof StringReference s ? s.value() : "";
                }
            }
            return "";
        }

        /** Applies the debuggee's exit code once it is gone. Harness: 0 ok, 1 user exception, 3 arg error. */
        private void finish(int exit) {
            if (exit == 1 && pending != null && m.resultJson == null) {
                if (job.record()) {
                    m.steps.add(new TraceModel.Step("exception", pending.line(), pending.depth(), pending.stackJson(),
                            null, null));
                }
                m.status = "runtime_error";
                m.exception = new TraceModel.ExceptionInfo(pending.type(), pending.message(), pending.line());
            } else if (exit == 3) {
                m.status = "runtime_error";
                m.error = harnessError();
            } else if (exit != 0) {
                m.status = "runtime_error";
                m.error = "the program exited with code " + exit + firstLine(": ");
            } else if (m.resultJson == null) {
                m.status = "runtime_error";
                m.error = "the program exited before " + sig.name() + " returned";
            }
            String rest = out.takeRest();
            if (!m.steps.isEmpty()) {
                TraceModel.Step last = m.steps.get(m.steps.size() - 1);
                last.stdout += rest;
            }
            m.stdout = out.all();
            m.truncated |= out.truncated();
        }

        private String harnessError() {
            for (String line : err.all().split("\n")) {
                if (line.startsWith("HARNESS_ARG_ERROR ")) return line.substring("HARNESS_ARG_ERROR ".length()).strip();
            }
            return "the harness could not invoke " + sig.name() + firstLine(": ");
        }

        private String firstLine(String prefix) {
            for (String line : err.all().split("\n")) if (!line.isBlank()) return prefix + line.strip();
            return "";
        }

        private List<StackFrame> userFrames(ThreadReference t) throws IncompatibleThreadStateException {
            List<StackFrame> uf = new ArrayList<>();
            for (StackFrame f : t.frames()) {
                if (userClasses.contains(f.location().declaringType().name())) uf.add(f);
            }
            return uf;
        }

        /** Aligns fids with n live user frames: frames popped by exceptions never got a MethodExit. */
        private void reconcile(int n) {
            while (fids.size() > n) fids.pop();
            while (fids.size() < n) fids.push(++fidCounter);
        }

        /** Serializes the top MAX_STACK user frames (top first) with their visible locals. */
        private String stackJson(List<StackFrame> uf) {
            Json.Writer w = new Json.Writer();
            w.beginArr();
            Iterator<Integer> fid = fids.iterator();
            for (int i = 0; i < uf.size() && i < MAX_STACK; i++) {
                StackFrame f = uf.get(i);
                Location loc = f.location();
                w.beginObj()
                        .key("fid").num(fid.next())
                        .key("cls").str(loc.declaringType().name())
                        .key("method").str(loc.method().name())
                        .key("line").num(loc.lineNumber())
                        .key("locals").beginArr();
                List<LocalVariable> vars;
                try {
                    vars = f.visibleVariables();
                } catch (AbsentInformationException e) {
                    vars = List.of();
                }
                if (!vars.isEmpty()) {
                    Map<LocalVariable, Value> vals = f.getValues(vars);
                    for (LocalVariable v : vars) {
                        w.beginObj().key("name").str(v.name()).key("value");
                        serializer.write(w, vals.get(v));
                        w.endObj();
                    }
                }
                w.endArr().endObj();
            }
            return w.endArr().toString();
        }

        private String valueJson(Value v) {
            Json.Writer w = new Json.Writer();
            serializer.write(w, v);
            return w.toString();
        }
    }

    /**
     * Drains a debuggee output stream into a capped buffer. Reads never block (only available()
     * bytes are read), so the event loop can pull exactly what the debuggee wrote before a step.
     */
    private static final class OutputPump {
        private final InputStream in;
        private final int cap;
        private final byte[] marker;
        private final byte[] chunk = new byte[8192];
        private byte[] buf = new byte[1024];
        private int len;
        private int taken;
        private boolean truncated;
        private volatile boolean closed;

        OutputPump(InputStream in, int cap, String marker) {
            this.in = in;
            this.cap = cap;
            this.marker = marker == null ? new byte[0] : marker.getBytes(StandardCharsets.UTF_8);
            Thread t = new Thread(this::poll, "dsatrace-output");
            t.setDaemon(true);
            t.start();
        }

        private void poll() {
            try {
                while (!closed) {
                    if (drain() == 0) Thread.sleep(2);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private synchronized int drain() {
            int total = 0;
            try {
                int n;
                while ((n = in.available()) > 0) {
                    int r = in.read(chunk, 0, Math.min(n, chunk.length));
                    if (r <= 0) break;
                    accept(r);
                    total += r;
                }
            } catch (IOException e) {
                closed = true; // stream gone
            }
            return total;
        }

        private void accept(int r) {
            if (truncated) return;
            int keep = Math.min(r, cap - len);
            append(chunk, keep);
            if (keep < r) {
                truncated = true;
                append(marker, marker.length);
            }
        }

        private void append(byte[] b, int n) {
            if (len + n > buf.length) buf = Arrays.copyOf(buf, Math.max(buf.length * 2, len + n));
            System.arraycopy(b, 0, buf, len, n);
            len += n;
        }

        /** Output captured since the last take, ending on a whole UTF-8 character. */
        synchronized String takeNew() {
            drain();
            return take(utf8Boundary());
        }

        /** All output not yet taken. */
        synchronized String takeRest() {
            drain();
            return take(len);
        }

        private String take(int end) {
            String s = new String(buf, taken, end - taken, StandardCharsets.UTF_8);
            taken = end;
            return s;
        }

        private int utf8Boundary() {
            for (int i = len - 1; i >= Math.max(taken, len - 3); i--) {
                int b = buf[i] & 0xFF;
                if ((b & 0xC0) == 0x80) continue; // continuation byte
                int need = b < 0x80 ? 1 : (b & 0xE0) == 0xC0 ? 2 : (b & 0xF0) == 0xE0 ? 3 : (b & 0xF8) == 0xF0 ? 4 : 1;
                return i + need > len ? i : len;
            }
            return len;
        }

        synchronized String all() {
            return new String(buf, 0, len, StandardCharsets.UTF_8);
        }

        synchronized boolean truncated() {
            return truncated;
        }

        void close() {
            closed = true;
        }
    }
}
