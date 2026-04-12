package io.github.vlaaad.ghostty.perf;

import io.github.vlaaad.ghostty.Frame;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.perf.TerminalFixtures.ViewportSize;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Threads(1)
@Fork(value = 1, jvmArgsAppend = "--enable-native-access=ALL-UNNAMED")
public class FrameBench {
    @Benchmark
    public Frame frameFirstFull(FirstFullState state) {
        return state.session.frame();
    }

    @Benchmark
    public Frame frameFullViewUpdate(DirtyViewportState state) {
        return state.session.frame();
    }

    @Benchmark
    public Frame frameCleanCached(CleanFrameState state) {
        return state.session.frame();
    }

    @State(Scope.Thread)
    public static class FirstFullState {
        @Param({"80x24", "120x40", "200x60"})
        public String viewport;

        TerminalSession session;
        ViewportSize size;
        byte[] clearPage;

        @Setup(Level.Trial)
        public void setUpViewport() {
            size = ViewportSize.parse(viewport);
            clearPage = TerminalFixtures.page(size, 0, true);
        }

        @Setup(Level.Invocation)
        public void openSession() {
            session = TerminalFixtures.openSession(size);
            session.write(clearPage);
        }

        @TearDown(Level.Invocation)
        public void closeSession() throws Exception {
            session.close();
        }
    }

    @State(Scope.Thread)
    public static class DirtyViewportState {
        @Param({"80x24", "120x40", "200x60"})
        public String viewport;

        TerminalSession session;
        ViewportSize size;
        byte[] clearPage;
        byte[] pageA;
        byte[] pageB;
        boolean first = true;

        @Setup(Level.Trial)
        public void setUpSession() {
            size = ViewportSize.parse(viewport);
            clearPage = TerminalFixtures.page(size, 0, true);
            pageA = TerminalFixtures.page(size, 1, false);
            pageB = TerminalFixtures.page(size, 2, false);
            session = TerminalFixtures.openSession(size);
            session.write(clearPage);
            session.frame();
        }

        @Setup(Level.Invocation)
        public void dirtyViewport() {
            session.write(first ? pageA : pageB);
            first = !first;
        }

        @TearDown(Level.Trial)
        public void closeSession() throws Exception {
            session.close();
        }
    }

    @State(Scope.Thread)
    public static class CleanFrameState {
        @Param({"80x24", "120x40", "200x60"})
        public String viewport;

        TerminalSession session;
        ViewportSize size;
        byte[] clearPage;

        @Setup(Level.Trial)
        public void setUpSession() {
            size = ViewportSize.parse(viewport);
            clearPage = TerminalFixtures.page(size, 0, true);
            session = TerminalFixtures.openSession(size);
            session.write(clearPage);
            session.frame();
            session.frame();
        }

        @TearDown(Level.Trial)
        public void closeSession() throws Exception {
            session.close();
        }
    }
}
