package io.github.vlaaad.ghostty;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalSessionTest {
    @Test
    void writeFrameRead() {
        try (var session = Ghostty.open(
            new TerminalConfig(8, 4, 128),
            bytes -> {},
            new TerminalQueries() {},
            new TerminalEvents() {}
        )) {
            session.write("hello".getBytes(StandardCharsets.UTF_8));

            var frame = session.frame();
            assertEquals("hello", rowText(frame.rows().getFirst()));
            assertEquals(FrameDirty.FULL, frame.dirty());
            assertEquals(ScreenKind.PRIMARY, frame.activeScreen());
        }
    }

    @Test
    void bellAndTitleEventsAreDispatchedOffActorThread() throws Exception {
        var threads = Collections.synchronizedList(new ArrayList<String>());
        var bellLatch = new CountDownLatch(1);
        var titleLatch = new CountDownLatch(1);
        var stateLatch = new CountDownLatch(2);

        try (var session = Ghostty.open(
            new TerminalConfig(12, 4, 128),
            bytes -> {},
            new TerminalQueries() {},
            new TerminalEvents() {
                @Override
                public void bell(TerminalSession session) {
                    threads.add(Thread.currentThread().getName());
                    bellLatch.countDown();
                }

                @Override
                public void titleChanged(TerminalSession session, String title) {
                    threads.add(Thread.currentThread().getName());
                    assertEquals("ghosttyfx-title", title);
                    titleLatch.countDown();
                }

                @Override
                public void stateChanged(TerminalSession session) {
                    threads.add(Thread.currentThread().getName());
                    stateLatch.countDown();
                }
            }
        )) {
            session.write("\u0007".getBytes(StandardCharsets.UTF_8));
            session.write("\u001b]0;ghosttyfx-title\u0007".getBytes(StandardCharsets.UTF_8));

            assertTrue(bellLatch.await(5, TimeUnit.SECONDS));
            assertTrue(titleLatch.await(5, TimeUnit.SECONDS));
            assertTrue(stateLatch.await(5, TimeUnit.SECONDS));
            assertEquals("ghosttyfx-title", session.frame().title());
            assertTrue(threads.stream().allMatch(name -> name.contains("events")));
        }
    }

    @Test
    void queryCallbacksWriteResponsesBackToPty() {
        var written = new ArrayList<byte[]>();
        try (var session = Ghostty.open(
            new TerminalConfig(80, 24, 1024),
            bytes -> written.add(bytes),
            new TerminalQueries() {
                @Override
                public String enquiryReply() {
                    return "pong";
                }

                @Override
                public String xtversionReply() {
                    return "ghosttyfx";
                }

                @Override
                public Optional<TerminalSize> sizeReportValue() {
                    return Optional.of(new TerminalSize(80, 24, 8, 16));
                }
            },
            new TerminalEvents() {}
        )) {
            session.write("\u0005\u001b[>q\u001b[18t".getBytes(StandardCharsets.UTF_8));
        }

        var output = new String(join(written), StandardCharsets.UTF_8);
        assertTrue(output.contains("pong"));
        assertTrue(output.contains("ghosttyfx"));
        assertTrue(output.contains("[8;24;80t"));
    }

    @Test
    void cleanFrameIsCachedAndRowsAreReused() {
        try (var session = Ghostty.open(
            new TerminalConfig(6, 3, 64),
            bytes -> {},
            new TerminalQueries() {},
            new TerminalEvents() {}
        )) {
            session.write("one\r\ntwo".getBytes(StandardCharsets.UTF_8));

            var dirty = session.frame();
            assertEquals(FrameDirty.FULL, dirty.dirty());

            var clean = session.frame();
            assertEquals(FrameDirty.CLEAN, clean.dirty());
            assertFalse(clean.rows().stream().anyMatch(FrameRow::dirty));

            var cached = session.frame();
            assertSame(clean, cached);

            session.write("\r\nthree".getBytes(StandardCharsets.UTF_8));
            var partial = session.frame();
            assertEquals(FrameDirty.PARTIAL, partial.dirty());
            assertFalse(partial.rows().get(0).dirty());
            assertTrue(partial.rows().get(2).dirty());
            assertSame(clean.rows().get(0), partial.rows().get(0));
        }
    }

    private static String rowText(FrameRow row) {
        return row.text().stripTrailing();
    }

    private static byte[] join(List<byte[]> chunks) {
        var size = chunks.stream().mapToInt(bytes -> bytes.length).sum();
        var out = new byte[size];
        var offset = 0;
        for (var chunk : chunks) {
            System.arraycopy(chunk, 0, out, offset, chunk.length);
            offset += chunk.length;
        }
        return out;
    }
}
