package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.net.URL;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ExceptionUtils;
import org.kohsuke.file_leak_detector.Listener;

/**
 * Make sure to run this test with injected file-leak-detector as otherwise
 * tests will fail.
 *
 * @author Kohsuke Kawaguchi
 */
public class SocketTest {
    static {
        // disable keepAlive for sun.net.www.http.HttpClient
        // as otherwise some connections are kept open for re-use
        // https://docs.oracle.com/javase/7/docs/technotes/guides/net/http-keepalive.html
        System.setProperty("http.keepAlive", "false");
    }

    @BeforeAll
    public static void beforeAll() {
        try {
            SocketImpl.class.getDeclaredField("socket");
        } catch (NoSuchFieldException e) {
            // Java 17+ changed the implementation of Sockets and
            // so the current approach does not work there anymore
            // for now we gracefully handle this and do keep file-leak-detector
            // useful for other types of file-handle-leaks
            Assumptions.abort("Cannot run SocketTest with Java 17 or newer, had: " + ExceptionUtils.readStackTrace(e));
        }
    }

    @AfterEach
    public void tearDown() {
        List<Listener.Record> files = new ArrayList<>(Listener.getCurrentOpenFiles());

        // exclude some false-positives when running tests via Maven
        files.removeIf(record ->
                record.toString().contains("/dev/random") || record.toString().contains("/dev/urandom"));

        assertEquals(0, files.size(), "Should not have any open files now, but had: " + files);
    }

    @Test
    public void testSocketChannelLeakDetection() throws IOException, InterruptedException {

        final ExecutorService es = Executors.newCachedThreadPool();

        final ServerSocketChannel serverSocket = ServerSocketChannel.open();
        serverSocket.bind(new InetSocketAddress("", 0));

        final Set<SocketChannel> sockets = Collections.synchronizedSet(new HashSet<>());
        es.execute(() -> {
            try {
                sockets.add(serverSocket.accept());
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        });
        SocketChannel socketChannel = SocketChannel.open(
                new InetSocketAddress("", serverSocket.socket().getLocalPort()));

        while (sockets.size() < 1) {
            //noinspection BusyWait
            Thread.sleep(500);
        }

        assertEquals(1, sockets.size());

        assumeTrue(hasSocketFields(), "Socket is not supported on newer Java version yet");

        assertEquals(2, getSocketChannels());

        socketChannel.close();
        for (SocketChannel ch : sockets) {
            ch.close();
        }

        assertEquals(0, getSocketChannels());
        es.shutdownNow();
    }

    private boolean hasSocketFields() {
        try {
            SocketImpl.class.getDeclaredField("socket");
            SocketImpl.class.getDeclaredField("serverSocket");
            return true;
        } catch (NoSuchFieldException e) {
            System.out.println("Could not find field: " + e);
            return false;
        }
    }

    @Test
    public void testSocketLeakDetection() throws IOException, InterruptedException {
        assertEquals(0, getSockets());

        final ExecutorService es = Executors.newCachedThreadPool();

        final ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("localhost", 0));

        final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
        es.execute(() -> {
            try {
                sockets.add(ss.accept());
            } catch (IOException ioe) {
                throw new UncheckedIOException(ioe);
            }
        });

        Socket s = new Socket("localhost", ss.getLocalPort());

        while (sockets.size() < 1) {
            //noinspection BusyWait
            Thread.sleep(500);
        }

        assertEquals(1, sockets.size());

        assumeTrue(hasSocketFields(), "Socket is not supported on newer Java version yet");

        assertEquals(2, getSockets());

        s.close();
        ss.close();
        for (Socket ch : sockets) {
            ch.close();
        }
        es.shutdownNow();

        assertEquals(0, getSockets());
    }

    private int getSocketChannels() {
        int socketChannels = 0;
        for (Listener.Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof Listener.SocketChannelRecord) {
                socketChannels++;
            }
        }
        return socketChannels;
    }

    private int getSockets() {
        int sockets = 0;
        for (Listener.Record record : Listener.getCurrentOpenFiles()) {
            if (record instanceof Listener.SocketRecord) {
                sockets++;
            }
        }
        return sockets;
    }

    @Test
    public void testHttpUrlConnection() throws IOException, ClassNotFoundException {
        assertEquals(0, getSockets(), "No socket should be open before connecting");

        HttpURLConnection conn = (HttpURLConnection) new URL("https://www.google.com").openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(false);
            conn.setDoInput(true);
            conn.connect();
            assertEquals(HttpURLConnection.HTTP_OK, conn.getResponseCode());

            assertEquals(1, getSockets(), "We should have a socket open now");
        } finally {
            conn.disconnect();
        }

        assertEquals(0, getSockets(), "No socket should be open after closing the connection");
    }

    @Test
    public void testHttpUrlConnectionWithRead() throws IOException, ClassNotFoundException {
        assertEquals(0, getSockets(), "No socket should be open before connecting");

        HttpURLConnection conn = (HttpURLConnection) new URL("https://www.google.com").openConnection();
        try {
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(false);
            conn.setDoInput(true);
            conn.connect();
            assertEquals(HttpURLConnection.HTTP_OK, conn.getResponseCode());

            assertEquals(1, getSockets(), "We should have a socket open now");

            // actually read the contents, even if we are not using it to simulate a full download of the data
            try (InputStream stream = conn.getInputStream();
                    ByteArrayOutputStream memStream = new ByteArrayOutputStream(
                            conn.getContentLength() == -1 ? 40000 : conn.getContentLength())) {
                byte[] b = new byte[4096];
                int len;
                while ((len = stream.read(b)) > 0) {
                    memStream.write(b, 0, len);
                }

                memStream.flush();
            }

            assertEquals(0, getSockets(), "Socket should be closed when stream is exhausted");
        } finally {
            conn.disconnect();
        }

        assertEquals(0, getSockets(), "No socket should be open after closing the connection");
    }
}
