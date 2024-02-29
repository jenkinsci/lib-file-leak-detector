package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.Selector;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.Record;
import org.kohsuke.file_leak_detector.Listener.SelectorRecord;

/**
 * Make sure to run this test with injected file-leak-detector as otherwise
 * tests will fail.
 */
public class SelectorDemo {
    private StringWriter output;

    @BeforeClass
    public static void setupClass() {
        assertTrue(
                "This test can only run with an injected Java agent for file-leak-detector",
                Listener.isAgentInstalled());
    }

    @Before
    public void setup() {
        output = new StringWriter();
        Listener.TRACE = new PrintWriter(output);
    }

    @Test
    public void openCloseSelector() throws IOException {
        Selector selector = Selector.open();
        assertNotNull("No selector record found", findSelectorRecord(selector));

        selector.close();
        assertNull("No selector record found", findSelectorRecord(selector));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened selector"));
        assertTrue(traceOutput.contains("Closed selector"));
    }

    private static SelectorRecord findSelectorRecord(Selector selector) {
        for (Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof SelectorRecord) {
                SelectorRecord selectorRecord = (SelectorRecord) record;
                if (selectorRecord.selector == selector) {
                    return selectorRecord;
                }
            }
        }
        return null;
    }
}
