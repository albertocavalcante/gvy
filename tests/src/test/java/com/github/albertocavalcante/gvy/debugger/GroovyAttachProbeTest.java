// SPDX-FileCopyrightText: Alberto Cavalcante and the gvy contributors
// SPDX-License-Identifier: Apache-2.0

package com.github.albertocavalcante.gvy.debugger;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.InvalidStackFrameException;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.request.ClassPrepareRequest;
import groovy.lang.GroovySystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroovyAttachProbeTest {
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final Pattern ADDRESS = Pattern.compile("Listening for transport dt_socket at address: (\\d+)");

    @Test
    @Timeout(45)
    void cleanupAfterTargetExitPreservesThePrimaryFailure(@TempDir Path directory) throws Exception {
        Path output = directory.resolve("target.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process target = new ProcessBuilder(java, "-Xmx32m",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0", "-version")
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> {
            try (TargetCleanup cleanup = new TargetCleanup(target)) {
                cleanup.vm = attach(awaitPort(output, target));
                target.destroyForcibly();
                assertTrue(target.waitFor(DEADLINE.toMillis(), TimeUnit.MILLISECONDS));
                throw new IllegalStateException("original failure");
            }
        });
        assertEquals("original failure", failure.getMessage());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(target.isAlive());
    }

    @Test
    @Timeout(90)
    void attachesToGroovyAndDetachesWithoutTerminatingTheTarget(@TempDir Path directory) throws Exception {
        Path source = directory.resolve("Probe.groovy");
        try (var input = getClass().getResourceAsStream("/debugger/Probe.groovy")) {
            Files.copy(java.util.Objects.requireNonNull(input), source);
        }
        var lines = Files.readAllLines(source);
        int breakpointLine = 1 + java.util.stream.IntStream.range(0, lines.size())
                .filter(index -> lines.get(index).contains("// BREAKPOINT"))
                .findFirst().orElseThrow();
        Path effect = directory.resolve("effect");
        Path release = directory.resolve("release");
        Path output = directory.resolve("target.log");
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String groovyJar = Path.of(GroovySystem.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process target = new ProcessBuilder(java, "-Xmx128m",
                "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                "-cp", groovyJar, "groovy.ui.GroovyMain", source.toString(), effect.toString(), release.toString())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try (TargetCleanup cleanup = new TargetCleanup(target)) {
            int port = awaitPort(output, target);
            VirtualMachine vm = attach(port);
            cleanup.vm = vm;
            ClassPrepareRequest prepare = vm.eventRequestManager().createClassPrepareRequest();
            prepare.addClassFilter("Probe");
            prepare.enable();
            vm.resume();

            EventSet stop = awaitBreakpoint(vm, breakpointLine);
            BreakpointEvent breakpoint = null;
            for (Event event : stop) {
                if (event instanceof BreakpointEvent found) breakpoint = found;
            }
            assertNotNull(breakpoint);
            assertEquals("Probe.groovy", breakpoint.location().sourceName());
            assertEquals(breakpointLine, breakpoint.location().lineNumber());
            assertFalse(Files.exists(effect), "the breakpoint must precede the side effect");
            StackFrame frame = breakpoint.thread().frame(0);
            assertEquals("run", frame.location().method().name());
            LocalVariable marker = frame.visibleVariableByName("marker");
            assertNotNull(marker, "Groovy must emit local-variable metadata");
            assertEquals(42, ((IntegerValue) frame.getValue(marker)).value());

            stop.resume();
            assertThrows(InvalidStackFrameException.class, () -> frame.getValue(marker));
            awaitEffect(effect, target);
            assertEquals("42", Files.readString(effect));
            vm.dispose();
            cleanup.vm = null;
            assertTrue(target.isAlive(), "detaching must leave the externally owned target alive");
            Files.createFile(release);
            assertTrue(target.waitFor(DEADLINE.toMillis(), TimeUnit.MILLISECONDS), "target did not exit");
            assertEquals(0, target.exitValue(), Files.readString(output));
        }
    }

    private static final class TargetCleanup implements AutoCloseable {
        private final Process target;
        private VirtualMachine vm;

        private TargetCleanup(Process target) {
            this.target = target;
        }

        @Override
        public void close() {
            try {
                if (vm != null) vm.dispose();
            } catch (VMDisconnectedException ignored) {
                // The target may have exited before cleanup.
            } finally {
                target.destroyForcibly();
                boolean interrupted = false;
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                try {
                    while (target.isAlive() && System.nanoTime() < deadline) {
                        try {
                            target.waitFor(100, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException exception) {
                            interrupted = true;
                        }
                    }
                    assertFalse(target.isAlive(), "target could not be reaped");
                } finally {
                    if (interrupted) Thread.currentThread().interrupt();
                }
            }
        }
    }

    private static int awaitPort(Path output, Process target) throws Exception {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline) && target.isAlive()) {
            if (Files.exists(output)) {
                Matcher match = ADDRESS.matcher(Files.readString(output));
                if (match.find()) return Integer.parseInt(match.group(1));
            }
            Thread.sleep(20);
        }
        throw new AssertionError("target did not publish a JDWP port: " + Files.readString(output));
    }

    private static VirtualMachine attach(int port) throws Exception {
        AttachingConnector connector = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(candidate -> candidate.name().equals("com.sun.jdi.SocketAttach"))
                .findFirst().orElseThrow();
        Map<String, com.sun.jdi.connect.Connector.Argument> arguments = connector.defaultArguments();
        arguments.get("hostname").setValue("127.0.0.1");
        arguments.get("port").setValue(Integer.toString(port));
        arguments.get("timeout").setValue(Long.toString(DEADLINE.toMillis()));
        return connector.attach(arguments);
    }

    private static EventSet awaitBreakpoint(VirtualMachine vm, int line) throws Exception {
        long deadline = System.nanoTime() + DEADLINE.toNanos();
        while (System.nanoTime() < deadline) {
            EventSet events = vm.eventQueue().remove(250);
            if (events == null) continue;
            boolean stopped = false;
            for (Event event : events) {
                if (event instanceof ClassPrepareEvent prepared) {
                    assertTrue(locationsForSource(prepared.referenceType(), "Wrong.groovy", line).isEmpty(),
                            "a different source filename must not bind a breakpoint");
                    var locations = locationsForSource(prepared.referenceType(), "Probe.groovy", line);
                    assertFalse(locations.isEmpty(), "fixture line must have an executable location");
                    vm.eventRequestManager().createBreakpointRequest(locations.get(0)).enable();
                } else if (event instanceof BreakpointEvent) {
                    stopped = true;
                }
            }
            if (stopped) return events;
            events.resume();
        }
        throw new AssertionError("timed out waiting for the Groovy breakpoint");
    }

    private static List<Location> locationsForSource(ReferenceType type, String filename, int line) throws Exception {
        if (!type.sourceName().equals(filename)) return List.of();
        return type.locationsOfLine(line);
    }

    private static void awaitEffect(Path path, Process target) throws Exception {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (Instant.now().isBefore(deadline) && target.isAlive()) {
            if (Files.exists(path) && Files.readString(path).equals("42")) return;
            Thread.sleep(20);
        }
        throw new AssertionError("target did not perform its side effect");
    }
}
