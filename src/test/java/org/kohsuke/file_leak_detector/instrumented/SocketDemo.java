package org.kohsuke.file_leak_detector.instrumented;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.kohsuke.file_leak_detector.Listener;

/**
 * Make sure to run this test with injected file-leak-detector as otherwise
 * tests will fail.
 *
 * @author Kohsuke Kawaguchi
 */
public class SocketDemo {
    public static void main(String[] args) throws IOException {
        final ExecutorService es = Executors.newCachedThreadPool();

        final ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("localhost", 0));

        es.submit(() -> {
            while (true) {
                final Socket s = ss.accept();
                es.submit(() -> {
                    s.close();
                    //                    s.shutdownInput();
                    //                    s.shutdownOutput();
                    return null;
                });
            }
        });

        for (int i = 0; i < 10; i++) {
            int dst = ss.getLocalPort();
            Socket s = new Socket("localhost", dst);
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
}
