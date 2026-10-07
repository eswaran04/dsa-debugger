package dev.dsadebug.tracer;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequest;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;

/** Test helper: launches a debuggee and suspends it at a source line. */
public final class JdiFixture implements AutoCloseable {
    private final VirtualMachine vm;
    private StackFrame frame;

    private JdiFixture(VirtualMachine vm) {
        this.vm = vm;
    }

    public StackFrame frame() {
        return frame;
    }

    /** Launches the debuggee and returns it suspended at the line; close() it when done. */
    public static JdiFixture launch(Path classesDir, String mainClass, int line) throws Exception {
        LaunchingConnector cn = Bootstrap.virtualMachineManager().defaultConnector();
        Map<String, Connector.Argument> args = cn.defaultArguments();
        args.get("main").setValue(mainClass);
        args.get("options").setValue("-cp " + classesDir.toAbsolutePath());
        VirtualMachine vm = cn.launch(args);
        drain(vm.process().getInputStream());
        drain(vm.process().getErrorStream());
        ClassPrepareRequest cpr = vm.eventRequestManager().createClassPrepareRequest();
        cpr.addClassFilter(mainClass);
        cpr.setSuspendPolicy(EventRequest.SUSPEND_ALL);
        cpr.enable();
        vm.resume();
        JdiFixture f = new JdiFixture(vm);
        while (true) {
            EventSet set = vm.eventQueue().remove();
            for (Event e : set) {
                if (e instanceof ClassPrepareEvent cp) {
                    ReferenceType rt = cp.referenceType();
                    BreakpointRequest br = vm.eventRequestManager()
                            .createBreakpointRequest(rt.locationsOfLine(line).get(0));
                    br.setSuspendPolicy(EventRequest.SUSPEND_ALL);
                    br.enable();
                    cpr.disable();
                } else if (e instanceof BreakpointEvent bp) {
                    f.frame = bp.thread().frame(0);
                    return f;
                } else if (e instanceof VMDeathEvent || e instanceof VMDisconnectEvent) {
                    throw new IllegalStateException("debuggee exited before reaching line " + line);
                }
            }
            set.resume();
        }
    }

    /** Same as launch(...).frame(); end the VM with frame.virtualMachine().exit(0). */
    public static StackFrame launchAndStopAt(Path classesDir, String mainClass, int line) throws Exception {
        return launch(classesDir, mainClass, line).frame;
    }

    private static void drain(InputStream in) {
        Thread t = new Thread(() -> {
            try {
                in.transferTo(java.io.OutputStream.nullOutputStream());
            } catch (java.io.IOException ignored) {
                // debuggee gone
            }
        });
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void close() {
        try {
            vm.exit(0);
        } catch (RuntimeException ignored) {
            // already disconnected
        }
    }
}
