package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.Pipe;
import java.nio.channels.Pipe.SinkChannel;
import java.nio.channels.Pipe.SourceChannel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.Record;
import org.kohsuke.file_leak_detector.Listener.SinkChannelRecord;
import org.kohsuke.file_leak_detector.Listener.SourceChannelRecord;

/**
 * Make sure to run this test with injected file-leak-detector as otherwise
 * tests will fail.
 *
 * @author Denis Joubert
 */
public class PipeDemo {
    private static final StringWriter output = new StringWriter();

    @BeforeAll
    public static void setup() {
        assertTrue(
                Listener.isAgentInstalled(),
                "This test can only run with an injected Java agent for file-leak-detector");
        Listener.TRACE = new PrintWriter(output);
    }

    @BeforeEach
    public void prepareOutput() {
        output.getBuffer().setLength(0);
    }

    @Test
    public void testPipe() throws IOException {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"), "TODO fails on Windows");
        final Pipe s = Pipe.open();
        assertNotNull(findSourceChannelRecord(s.source()), "No source channel record found");
        assertNotNull(findSinkChannelRecord(s.sink()), "No sink channel record found");

        s.sink().close();
        assertNull(findSinkChannelRecord(s.sink()), "Sink channel record not removed");
        s.source().close();
        assertNull(findSourceChannelRecord(s.source()), "Source channel record not removed");

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened Pipe Source Channel"));
        assertTrue(traceOutput.contains("Closed Pipe Source Channel"));
        assertTrue(traceOutput.contains("Opened Pipe Sink Channel"));
        assertTrue(traceOutput.contains("Closed Pipe Sink Channel"));
    }

    private static SourceChannelRecord findSourceChannelRecord(SourceChannel sourceChannel) {
        for (Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof SourceChannelRecord) {
                SourceChannelRecord sourceChannelRecord = (SourceChannelRecord) record;
                if (sourceChannelRecord.source == sourceChannel) {
                    return sourceChannelRecord;
                }
            }
        }
        return null;
    }

    private static SinkChannelRecord findSinkChannelRecord(SinkChannel sinkChannel) {
        for (Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof SinkChannelRecord) {
                SinkChannelRecord sinkChannelRecord = (SinkChannelRecord) record;
                if (sinkChannelRecord.sink == sinkChannel) {
                    return sinkChannelRecord;
                }
            }
        }
        return null;
    }
}
