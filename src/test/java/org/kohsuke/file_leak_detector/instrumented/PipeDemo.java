package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.Pipe;
import java.nio.channels.Pipe.SinkChannel;
import java.nio.channels.Pipe.SourceChannel;

import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.Record;
import org.kohsuke.file_leak_detector.Listener.SinkChannelRecord;
import org.kohsuke.file_leak_detector.Listener.SourceChannelRecord;

/**
 * @author Denis Joubert
 */
public class PipeDemo {
    private static StringWriter output = new StringWriter();

    @BeforeClass
    public static void setup() {
        assertTrue(Listener.isAgentInstalled());
        Listener.TRACE = new PrintWriter(output);
    }

    @Before
    public void prepareOutput() throws Exception {
        output.getBuffer().setLength(0);
    }


    @Test
    public void testPipe() throws IOException {
        final Pipe s = Pipe.open();
        assertNotNull("No source channel record found", findSourceChannelRecord(s.source()));
        assertNotNull("No sink channel record found", findSinkChannelRecord(s.sink()));

        s.sink().close();
        assertNull("Sink channel record not removed", findSinkChannelRecord(s.sink()));
        s.source().close();
        assertNull("Source channel record not removed", findSourceChannelRecord(s.source()));

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
