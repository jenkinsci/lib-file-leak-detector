package org.kohsuke.file_leak_detector.instrumented;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.FileUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.kohsuke.file_leak_detector.ActivityListener;
import org.kohsuke.file_leak_detector.Listener;
import org.kohsuke.file_leak_detector.Listener.FileRecord;
import org.kohsuke.file_leak_detector.Listener.Record;

/**
 * Make sure to run this test with injected file-leak-detector as otherwise
 * tests will fail.
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
           // sometimes java.util.zip.ZipFile$CleanableResource$FinalizableResource.finalize()
           // will kick in and will close a ZipFile, thus we ignore the corresponding objects here
           if (obj.getClass().getSimpleName().contains("URLJarFile")) {
               return;
           }

            FileDemo.this.obj = obj;
        }

        @Override
        public void fd_open(Object obj) {
            FileDemo.this.obj = obj;
        }
    };

    @BeforeClass
    public static void setup() {
        assertTrue(
                "This test expects the Java Agent to be installed via command-line options",
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
        FileUtils.writeStringToFile(tempFile, "teststring123", StandardCharsets.UTF_8);
    }

    @After
    public void cleanup() {
        try {
            Files.deleteIfExists(tempFile.toPath());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    public void openCloseFile() throws Exception {
        try (FileInputStream in = new FileInputStream(tempFile)) {
            assertNotNull(in);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(FileInputStream.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFileChannel() throws Exception {
        try (FileChannel fileChannel = FileChannel.open(tempFile.toPath(), StandardOpenOption.APPEND)) {
            assertNotNull(fileChannel);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(FileChannel.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewByteChannel() throws Exception {
        // this triggers the following method
        // FileDescriptor sun.nio.fs.UnixChannelFactory.open(...)
        try (SeekableByteChannel fileChannel =
                Files.newByteChannel(tempFile.toPath(), StandardOpenOption.APPEND)) {
            assertNotNull(fileChannel);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(SeekableByteChannel.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStream() throws Exception {
        Files.deleteIfExists(tempFile.toPath());
        Files.createDirectories(tempFile.toPath());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath())) {
            assertNotNull(stream);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(DirectoryStream.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStreamWithStarGlob() throws Exception {
        Files.deleteIfExists(tempFile.toPath());
        Files.createDirectories(tempFile.toPath());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath(), "*")) {
            assertNotNull(stream);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(DirectoryStream.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStreamWithNonStarGlob() throws Exception {
        Files.deleteIfExists(tempFile.toPath());
        Files.createDirectories(tempFile.toPath());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath(), "my*test*glob")) {
            assertNotNull(stream);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(DirectoryStream.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewDirectoryStreamWithFilter() throws Exception {
        Files.deleteIfExists(tempFile.toPath());
        Files.createDirectories(tempFile.toPath());

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tempFile.toPath(), entry -> true)) {
            assertNotNull(stream);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));
            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(DirectoryStream.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewByteChannelRead() throws Exception {
        // this triggers the following method
        // FileDescriptor sun.nio.fs.UnixChannelFactory.open(...)
        try (SeekableByteChannel fileChannel = Files.newByteChannel(tempFile.toPath(), StandardOpenOption.READ)) {
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

            final ByteBuffer buffer = ByteBuffer.allocate(5);
            fileChannel.read(buffer);
            assertEquals("tests", new String(buffer.array()));

            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(SeekableByteChannel.class));
        }

        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertContainsAdjacentLines(traceOutput, "Opened " + tempFile, "java.io.FileOutputStream.<init>(");
        assertContainsAdjacentLines(traceOutput, "Closed " + tempFile, "java.io.FileOutputStream.close(");
    }

    @Test
    public void openCloseFilesCreateTempFile() throws Exception {
        Path tempFile = Files.createTempFile("file-leak-detector", ".test");
        assertTrue(tempFile.toFile().delete());

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFileLines() throws Exception {
        try (Stream<String> stream = Files.lines(tempFile.toPath())) {
            assertNotNull(stream);
            assertNotNull("No file record for file=" + tempFile + " found", findFileRecord(tempFile));

            assertThat("Did not have the expected type of 'marker' object: " + obj,
                    obj, instanceOf(SeekableByteChannel.class));
        }
        assertNull("File record for file=" + tempFile + " not removed", findFileRecord(tempFile));

        String traceOutput = output.toString();
        assertContainsAdjacentLines(traceOutput, "Opened " + tempFile, "java.io.FileOutputStream.<init>(");
        assertContainsAdjacentLines(traceOutput, "Closed " + tempFile, "java.io.FileOutputStream.close(");
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

    private static void assertContainsAdjacentLines(String output, String thisLineContent, String nextLineContent) throws IOException {
        List<String> lines = IOUtils.readLines(IOUtils.toInputStream(output));
        int index = findIndexOf(lines, thisLineContent);
        assertTrue(index != -1);
        assertThat(lines.get(index + 1), containsString(nextLineContent));
    }

    private static int findIndexOf(List<String> lines, String target) {
        for (int i = 0; i < lines.size(); ++i) {
            if (lines.get(i).contains(target)) {
                return i;
            }
        }
        return -1;
    }
}
