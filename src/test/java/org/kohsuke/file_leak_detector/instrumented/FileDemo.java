package org.kohsuke.file_leak_detector.instrumented;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collections;
import java.util.stream.Stream;
import org.apache.commons.io.file.NoopPathVisitor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.kohsuke.file_leak_detector.ActivityListener;
import org.kohsuke.file_leak_detector.Listener;
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
        public void open(Object obj, Path file) {
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

    @BeforeAll
    public static void setup() {
        assertTrue(
                Listener.isAgentInstalled(),
                "This test expects the Java Agent to be installed via command-line options");
        Listener.TRACE = new PrintWriter(output);
    }

    @BeforeEach
    public void registerListener() {
        ActivityListener.LIST.add(listener);
    }

    @AfterEach
    public void unregisterListener() {
        ActivityListener.LIST.remove(listener);
    }

    @BeforeEach
    public void prepareOutput() throws Exception {
        output.getBuffer().setLength(0);
        Path tempPath = Files.createTempFile("file-leak-detector-FileDemo", ".tmp");
        Files.writeString(tempPath, "teststring123", StandardCharsets.UTF_8);
        tempFile = tempPath.toFile();
    }

    @AfterEach
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
            assertNotNull(
                    findPathRecord(tempFile.toPath()),
                    "No file record for file=" + tempFile + " found, having: " + Listener.getCurrentOpenFiles());
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(FileInputStream.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesBufferedWriter() throws Exception {
        try (BufferedWriter writer = Files.newBufferedWriter(tempFile.toPath())) {
            assertNotNull(writer);
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat("Did not have the expected type of 'marker' object: " + obj, obj, instanceOf(FileChannel.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesBufferedReader() throws Exception {
        try (BufferedReader reader = Files.newBufferedReader(tempFile.toPath())) {
            assertNotNull(reader);
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat("Did not have the expected type of 'marker' object: " + obj, obj, instanceOf(FileChannel.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFileChannel() throws Exception {
        try (FileChannel fileChannel = FileChannel.open(tempFile.toPath(), StandardOpenOption.APPEND)) {
            assertNotNull(fileChannel);
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat("Did not have the expected type of 'marker' object: " + obj, obj, instanceOf(FileChannel.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewByteChannel() throws Exception {
        // this triggers the following method
        // FileDescriptor sun.nio.fs.UnixChannelFactory.open(...)
        try (SeekableByteChannel fileChannel = Files.newByteChannel(tempFile.toPath(), StandardOpenOption.APPEND)) {
            assertNotNull(fileChannel);
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(SeekableByteChannel.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

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
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(DirectoryStream.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

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
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(DirectoryStream.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

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
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(DirectoryStream.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

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
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(DirectoryStream.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    @Test
    public void openCloseFilesNewByteChannelRead() throws Exception {
        // this triggers the following method
        // FileDescriptor sun.nio.fs.UnixChannelFactory.open(...)
        try (SeekableByteChannel fileChannel = Files.newByteChannel(tempFile.toPath(), StandardOpenOption.READ)) {
            assertNotNull(fileChannel);
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");

            final ByteBuffer buffer = ByteBuffer.allocate(5);
            fileChannel.read(buffer);
            assertEquals("tests", new String(buffer.array()));

            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(SeekableByteChannel.class));
        }

        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
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
            assertNotNull(findPathRecord(tempFile.toPath()), "No file record for file=" + tempFile + " found");

            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(SeekableByteChannel.class));
        }
        assertNull(findPathRecord(tempFile.toPath()), "File record for file=" + tempFile + " not removed");
        assertThat(
                "Did not have the expected type of 'marker' object: " + obj,
                obj,
                instanceOf(SeekableByteChannel.class));
        assertThat("Did not have the expected type of 'marker' object: " + obj,
				obj, instanceOf(SeekableByteChannel.class));

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + tempFile));
        assertThat(traceOutput, containsString("Closed " + tempFile));
    }

    private static Listener.PathRecord findPathRecord(Path path) {
        for (Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof Listener.PathRecord) {
                Listener.PathRecord pathRecord = (Listener.PathRecord) record;
                if (pathRecord.path == path || pathRecord.path.getFileName().equals(path.getFileName())) {
                    return pathRecord;
                }
            }
        }
        return null;
    }

    @Test
    public void testZipFile() throws IOException {
        URL url = getClass().getResource("/test.zip");
        URI uri = URI.create("jar:" + url.getProtocol() + "://" + url.getFile());
        try (FileSystem fs = FileSystems.newFileSystem(uri, Collections.emptyMap())) {
            assertNotNull(fs);
            assertNotNull(
                    findPathRecord(new File("test.zip").toPath()),
                    "No file record for file=test.zip found: " + Listener.getCurrentOpenFiles());
            assertThat(
                    "Did not have the expected type of 'marker' object: " + obj,
                    obj,
                    instanceOf(SeekableByteChannel.class));

            Files.walkFileTree(fs.getPath("."), new NoopPathVisitor());
		} catch (Exception e) {
            throw new IllegalStateException("Failed for URI: " + uri, e);
        }

        assertNull(
                findPathRecord(new File("test.zip").toPath()),
                "File record for file=test.zip not removed: " + Listener.getCurrentOpenFiles());

        String traceOutput = output.toString();
        assertThat(traceOutput, containsString("Opened " + new File(url.getFile()).getAbsolutePath()));
        assertThat(traceOutput, containsString("Closed " + new File(url.getFile()).getAbsolutePath()));
    }
}
