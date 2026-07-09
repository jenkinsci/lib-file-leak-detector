package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.Selector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.Record;
import org.kohsuke.file_leak_detector.Listener.SelectorRecord;

/**
 * Make sure to run this test with injected file-leak-detector as otherwise
 * tests will fail.
 */
public class SelectorDemo {
    private StringWriter output;

    @BeforeAll
    public static void setupClass() {
        assertTrue(
                Listener.isAgentInstalled(),
                "This test can only run with an injected Java agent for file-leak-detector");
    }

    @BeforeEach
    public void setup() {
        output = new StringWriter();
        Listener.TRACE = new PrintWriter(output);
    }

    @Test
    public void openCloseSelector() throws IOException {
        Selector selector = Selector.open();
        assertNotNull(findSelectorRecord(selector), "No selector record found");

        selector.close();
        assertNull(findSelectorRecord(selector), "No selector record found");

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
