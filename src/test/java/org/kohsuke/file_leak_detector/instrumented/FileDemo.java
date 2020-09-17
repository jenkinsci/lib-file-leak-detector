package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.stream.Stream;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.file_leak_detector.ActivityListener;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.FileRecord;
import org.kohsuke.file_leak_detector.Listener.Record;

/**
 *
 * @author Andreas Dangel
 */
public class FileDemo {
    private static final StringWriter output = new StringWriter();
    private File tempFile;
    private Object obj;

    private final ActivityListener listener = new ActivityListener() {
        @Override
        public void open(Object obj, File file) {
            FileDemo.this.obj = obj;
        }

        @Override
        public void openSocket(Object obj) {
            FileDemo.this.obj = obj;
        }

        @Override
        public void close(Object obj) {
            FileDemo.this.obj = obj;
        }

        @Override
        public void fd_open(Object obj) {
            FileDemo.this.obj = obj;
        }
    };

    @BeforeClass
    public static void setup() {
        assertTrue("This test expects the Java Agent to be installed via commandline options",
                Listener.isAgentInstalled());
        Listener.TRACE = new PrintWriter(output);
    }

    @Before
    public void registerListener() {
        ActivityListener.LIST.add(listener);
    }

    @After
    public void unregisterListener() {
        ActivityListener.LIST.remove(listener);
    }

    @Before
    public void prepareOutput() throws Exception {
        output.getBuffer().setLength(0);
        tempFile = File.createTempFile("file-leak-detector-FileDemo", ".tmp");
    }

    @After
    public void cleanup () {
        assertTrue(!tempFile.exists() || tempFile.delete());
    }

    @Test
    public void openCloseFile() throws Exception {
        FileInputStream in = new FileInputStream(tempFile);
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof FileInputStream);

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

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof FileChannel);

        fileChannel.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewByteChannel() throws Exception {
        // this triggers the following method
        // FileDescriptor sun.nio.fs.UnixChannelFactory.open(...)
        SeekableByteChannel fileChannel = Files.newByteChannel(tempFile.toPath(), StandardOpenOption.APPEND);
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof SeekableByteChannel);

        fileChannel.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStream() throws Exception {
        assertTrue(tempFile.delete());
        assertTrue(tempFile.mkdirs());

        DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath());
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof DirectoryStream);

        stream.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStreamWithStarGlob() throws Exception {
        assertTrue(tempFile.delete());
        assertTrue(tempFile.mkdirs());

        DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath(), "*");
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof DirectoryStream);

        stream.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStreamWithNonStarGlob() throws Exception {
        assertTrue(tempFile.delete());
        assertTrue(tempFile.mkdirs());

        DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath(), "my*test*glob");
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof DirectoryStream);

        stream.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStreamWithFilter() throws Exception {
        assertTrue(tempFile.delete());
        assertTrue(tempFile.mkdirs());

        DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath(), new DirectoryStream.Filter<Path>() {
            @Override
            public boolean accept(Path entry) {
                return true;
            }
        });
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof DirectoryStream);

        stream.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertTrue(traceOutput.contains("Opened " + tempFile));
        assertTrue(traceOutput.contains("Closed " + tempFile));
    }

    @Test
    public void openCloseFileLines() throws Exception {
        Stream<String> stream = Files.lines(tempFile.toPath());
        assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

        stream.close();
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        assertTrue("Did not have the expected type of 'marker' object: " + obj,
                obj instanceof SeekableByteChannel);

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
