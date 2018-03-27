package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.FileRecord;
import org.kohsuke.file_leak_detector.Listener.Record;

/**
 * 
 * @author Andreas Dangel
 */
public class FileDemo {
    private static StringWriter output = new StringWriter();
    private File tempFile;

    @BeforeClass
    public static void setup() {
        assertTrue(Listener.isAgentInstalled());
        Listener.TRACE = new PrintWriter(output);
    }

    @Before
    public void prepareOutput() throws Exception {
        output.getBuffer().setLength(0);
        tempFile = File.createTempFile("file-leak-detector-FileDemo", ".tmp");
    }

    @After
    public void cleanup () {
        tempFile.delete();
    }

    @Test
    public void openCloseFile() throws Exception {
        FileInputStream in = new FileInputStream(tempFile);
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        in.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFileChannel() throws Exception {
        FileChannel fileChannel = FileChannel.open(tempFile.toPath(), StandardOpenOption.APPEND);
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        fileChannel.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    private static FileRecord findFileRecord(File file) {
        for (Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof FileRecord) {
                FileRecord fileRecord = (FileRecord) record;
                if (fileRecord.file == file || fileRecord.file.getName().equals(file.getName())) {
                    return fileRecord;
                }
            }
        }
        return null;
    }
}
