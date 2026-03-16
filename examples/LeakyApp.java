import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Random;

/**
 * Example app that deliberately leaks file descriptors to demonstrate
 * the file-leak-detector Java agent.
 *
 * Some file handles are intentionally NOT closed to simulate leaks.
 */
public class LeakyApp {

    private static final Random RANDOM = new Random();
    private static final String[] URLS = {
        "http://example.com",
        "http://www.example.org"
    };

    public static void main(String[] args) throws Exception {
        System.out.println("LeakyApp started. Send SIGTERM to trigger shutdown dump.");
        System.out.println("Watch stderr for file-leak-detector dumps every 1 second.");

        Thread fileWriter = new Thread(LeakyApp::fileWriterLoop, "file-writer");
        Thread devNullWriter = new Thread(LeakyApp::devNullWriterLoop, "devnull-writer");
        Thread urlReader = new Thread(LeakyApp::urlReaderLoop, "url-reader");

        fileWriter.setDaemon(true);
        devNullWriter.setDaemon(true);
        urlReader.setDaemon(true);

        fileWriter.start();
        devNullWriter.start();
        urlReader.start();

        // Block forever; run.sh will send SIGTERM to trigger dumpatshutdown
        Thread.currentThread().join();
    }

    private static void fileWriterLoop() {
        for (int i = 0; ; i++) {
            try {
                File f = File.createTempFile("leakyapp-", ".tmp");
                f.deleteOnExit();
                // Intentionally NOT closing this stream - this is a leak
                FileOutputStream fos = new FileOutputStream(f);
                fos.write(("data-" + RANDOM.nextInt(1000) + "\n").getBytes());
                fos.flush();
                System.out.println("[file-writer] Opened (leaked): " + f.getAbsolutePath());
                Thread.sleep(1500);
            } catch (IOException e) {
                System.err.println("[file-writer] IO error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void devNullWriterLoop() {
        for (int i = 0; ; i++) {
            try {
                // Intentionally NOT closing - leak to /dev/null
                FileOutputStream fos = new FileOutputStream("/dev/null");
                fos.write(("null-" + RANDOM.nextInt(1000) + "\n").getBytes());
                System.out.println("[devnull-writer] Opened (leaked): /dev/null #" + i);
                Thread.sleep(2000);
            } catch (IOException e) {
                System.err.println("[devnull-writer] IO error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void urlReaderLoop() {
        for (int i = 0; ; i++) {
            try {
                String urlStr = URLS[RANDOM.nextInt(URLS.length)];
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                InputStream is = conn.getInputStream();
                byte[] buf = new byte[256];
                int bytesRead = is.read(buf);
                System.out.println("[url-reader] Read " + bytesRead + " bytes from " + urlStr);
                // Intentionally NOT closing the stream or disconnecting
                Thread.sleep(3000);
            } catch (IOException e) {
                System.err.println("[url-reader] IO error: " + e.getMessage());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
