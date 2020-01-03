package org.kohsuke.file_leak_detector.instrumented;

import static org.junit.Assert.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.Test;
import org.kohsuke.file_leak_detector.Listener;

/**
 * @author Kohsuke Kawaguchi
 */
public class SocketDemo {
    public static void main(String[] args) throws IOException {
        final ExecutorService es = Executors.newCachedThreadPool();

        final ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("localhost",0));

        es.submit(() -> {
			while (true) {
				final Socket s = ss.accept();
				es.submit(() -> {
					s.close();
//                            s.shutdownInput();
//                            s.shutdownOutput();
					return null;
				});
			}
		});

        for (int i=0; i<10; i++) {
            int dst = ss.getLocalPort();
            Socket s = new Socket("localhost",dst);
            s.close();
//            s.shutdownInput();
//            s.shutdownOutput();
        }

        System.out.println("Dumping the table");
        Listener.dump(System.out);

        System.out.println("done");
        ss.close();
        es.shutdownNow();
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
			}
			catch (IOException ioe) {
				throw new UncheckedIOException(ioe);
			}
		});
        SocketChannel socketChannel = SocketChannel.open(new InetSocketAddress("", serverSocket.socket().getLocalPort()));

        while (sockets.size() < 1) {
            Thread.sleep(500);
        }

        assertEquals(1, sockets.size());

        assertEquals(2, getSocketChannels());

        socketChannel.close();
        for (SocketChannel ch : sockets) {
            ch.close();
        }

        assertEquals(0, getSocketChannels());
        es.shutdownNow();
    }

    @Test
    public void testSocketLeakDetection() throws IOException, InterruptedException {
        final ExecutorService es = Executors.newCachedThreadPool();

        final ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("localhost",0));

        final Set<Socket> sockets = Collections.synchronizedSet(new HashSet<>());
        es.execute(() -> {
			try {
				sockets.add(ss.accept());
			}
			catch (IOException ioe) {
				throw new UncheckedIOException(ioe);
			}
		});

        Socket s = new Socket("localhost", ss.getLocalPort());

        while (sockets.size() < 1) {
            Thread.sleep(500);
        }

        assertEquals(1, sockets.size());

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
}
