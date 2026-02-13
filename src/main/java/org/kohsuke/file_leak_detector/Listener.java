package org.kohsuke.file_leak_detector;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketImpl;
import java.nio.channels.FileChannel;
import java.nio.channels.Pipe;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.DirectoryStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;

/**
 * Intercepted JDK calls land here.
 *
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("unused")
public class Listener {

    /**
     * Remembers who/where/when opened a file.
     */
    public static class Record {
        public final Exception stackTrace = new Exception();
        public final String threadName;
        public final long time;

        protected Record() {
            // keeping a Thread would potentially leak a thread, so let's just do a name
            this.threadName = Thread.currentThread().getName();
            this.time = System.currentTimeMillis();
        }

        public void dump(String prefix, PrintWriter pw) {
            StackTraceElement[] trace = stackTrace.getStackTrace();
            int i = 0;
            // skip until we find the Method.invoke() that called us
            for (; i < trace.length; i++) {
                if (trace[i].getClassName().equals("java.lang.reflect.Method")) {
                    i++;
                    break;
                }
            }
            // print the rest
            for (; i < trace.length; i++) {
                pw.println("\tat " + trace[i]);
            }
            pw.flush();
        }

        public String getType() {
            return "unknown";
        }

        public String getResource() {
            return "";
        }

        public List<String> getFilteredStackTrace() {
            StackTraceElement[] trace = stackTrace.getStackTrace();
            List<String> result = new ArrayList<>();
            int i = 0;
            for (; i < trace.length; i++) {
                if (trace[i].getClassName().equals("java.lang.reflect.Method")) {
                    i++;
                    break;
                }
            }
            for (; i < trace.length; i++) {
                result.add(trace[i].toString());
            }
            return result;
        }

        public boolean exclude() {
            if (EXCLUDES.isEmpty()) {
                return false;
            }

            StackTraceElement[] trace = stackTrace.getStackTrace();
            int i = 0;
            // skip until we find the Method.invoke() that called us
            for (; i < trace.length; i++) {
                if (trace[i].getClassName().equals("java.lang.reflect.Method")) {
                    i++;
                    break;
                }
            }

            // check the rest
            for (; i < trace.length; i++) {
                String t = trace[i].toString();
                for (String exclude : EXCLUDES) {
                    // skip empty lines
                    if (t.contains(exclude)) {
                        return true;
                    }
                }
            }

            // no matchine exclude found
            return false;
        }
    }

    /**
     * Record of opened file.
     */
    public static final class FileRecord extends Record {
        public final File file;

        private FileRecord(File file) {
            this.file = file;
        }

        @Override
        public String getType() {
            return "file";
        }

        @Override
        public String getResource() {
            return file.toString();
        }

        @Override
        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + file + " by thread:" + threadName + " on " + format(time));
            super.dump(prefix, pw);
        }

        @Override
        public String toString() {
            return "FileRecord[file=" + file + "]";
        }
    }

    public static final class PathRecord extends Record {
        public final Path path;

        private PathRecord(Path path) {
            this.path = path;
        }

        @Override
        public String getType() {
            return "path";
        }

        @Override
        public String getResource() {
            return path.toString();
        }

        @Override
        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + path + " by thread:" + threadName + " on " + format(time));
            super.dump(prefix, pw);
        }

        @Override
        public String toString() {
            return "PathRecord[file=" + path + "]";
        }
    }

    public static final class SourceChannelRecord extends Record {
        public final Pipe.SourceChannel source;

        private SourceChannelRecord(Pipe.SourceChannel source) {
            this.source = source;
        }

        @Override
        public String getType() {
            return "pipe_source";
        }

        @Override
        public String getResource() {
            return "Pipe Source Channel";
        }

        @Override
        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + "Pipe Source Channel by thread:" + threadName + " on " + format(time));
            super.dump(prefix, pw);
        }
    }

    public static final class SinkChannelRecord extends Record {
        public final Pipe.SinkChannel sink;

        private SinkChannelRecord(Pipe.SinkChannel sink) {
            this.sink = sink;
        }

        @Override
        public String getType() {
            return "pipe_sink";
        }

        @Override
        public String getResource() {
            return "Pipe Sink Channel";
        }

        @Override
        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + "Pipe Sink Channel by thread:" + threadName + " on " + format(time));
            super.dump(prefix, pw);
        }
    }

    /**
     * Record of opened socket.
     */
    public static final class SocketRecord extends Record {
        public final Socket socket;
        public final String peer;

        private SocketRecord(Socket socket) {
            this.socket = socket;
            peer = getRemoteAddress(socket);
        }

        private String getRemoteAddress(Socket socket) {
            SocketAddress ra = socket.getRemoteSocketAddress();
            return ra != null ? ra.toString() : null;
        }

        @Override
        public String getType() {
            return "socket";
        }

        @Override
        public String getResource() {
            String p = this.peer;
            if (p == null) {
                p = getRemoteAddress(socket);
            }
            return p != null ? p : "unknown";
        }

        @Override
        public void dump(String prefix, PrintWriter ps) {
            // best effort at showing where it is/was listening
            String peer = this.peer;
            if (peer == null) {
                peer = getRemoteAddress(socket);
            }

            ps.println(prefix + "socket to " + peer + " by thread:" + threadName + " on " + format(time));
            super.dump(prefix, ps);
        }

        @Override
        public String toString() {
            return "SocketRecord[socket=" + socket + ",peer=" + peer + "]";
        }
    }

    /**
     * Record of opened server socket.
     */
    public static final class ServerSocketRecord extends Record {
        public final ServerSocket socket;
        public final String address;

        private ServerSocketRecord(ServerSocket socket) {
            this.socket = socket;
            address = getLocalAddress(socket);
        }

        private String getLocalAddress(ServerSocket socket) {
            SocketAddress la = socket.getLocalSocketAddress();
            return la != null ? la.toString() : null;
        }

        @Override
        public String getType() {
            return "server_socket";
        }

        @Override
        public String getResource() {
            String a = this.address;
            if (a == null) {
                a = getLocalAddress(socket);
            }
            return a != null ? a : "unknown";
        }

        @Override
        public void dump(String prefix, PrintWriter ps) {
            // best effort at showing where it is/was listening
            String address = this.address;
            if (address == null) {
                address = getLocalAddress(socket);
            }

            ps.println(prefix + "server socket at " + address + " by thread:" + threadName + " on " + format(time));
            super.dump(prefix, ps);
        }
    }

    /**
     * Record of opened SocketChannel.
     */
    public static final class SocketChannelRecord extends Record {
        public final SocketChannel socket;

        private SocketChannelRecord(SocketChannel socket) {
            this.socket = socket;
        }

        @Override
        public String getType() {
            return "socket_channel";
        }

        @Override
        public String getResource() {
            return "socket channel";
        }

        @Override
        public void dump(String prefix, PrintWriter ps) {
            ps.println(prefix + "socket channel by thread:" + threadName + " on " + format(time));
            super.dump(prefix, ps);
        }
    }

    public static final class SelectorRecord extends Record {
        public final Selector selector;

        private SelectorRecord(Selector selector) {
            this.selector = selector;
        }

        @Override
        public String getType() {
            return "selector";
        }

        @Override
        public String getResource() {
            return "selector";
        }

        @Override
        public void dump(String prefix, PrintWriter ps) {
            ps.println(prefix + "selector by thread:" + threadName + " on " + format(time));
            super.dump(prefix, ps);
        }
    }

    /**
     * Files that are currently open, keyed by the owner object like {@link FileInputStream}.
     */
    private static Map<Object, Record> TABLE = new WeakHashMap<>();

    /**
     * Trace the open/close op
     */
    public static PrintWriter TRACE = null;

    /**
     * Trace the "too many open files" error here
     */
    public static PrintWriter ERROR = new PrintWriter(new OutputStreamWriter(System.err, Charset.defaultCharset()));

    /**
     * Allows to provide stacktrace-lines which cause the element to be excluded
     */
    public static final List<String> EXCLUDES = new ArrayList<>();

    /**
     * Tracing may cause additional files to be opened.
     * In such a case, avoid infinite recursion.
     */
    private static boolean tracing = false;

    /**
     * If the table size grows beyond this, report the table
     */
    public static int THRESHOLD = 999999;

    /**
     * When true, dump output uses single-line JSON format instead of plain text.
     */
    public static boolean JSON = false;

    /**
     * Is the agent actually transforming the class files?
     */
    /*package*/ static boolean AGENT_INSTALLED = false;

    /**
     * Returns true if the leak detector agent is running.
     */
    public static boolean isAgentInstalled() {
        return AGENT_INSTALLED;
    }

    public static synchronized void makeStrong() {
        TABLE = new LinkedHashMap<>(TABLE);
    }

    /**
     * Called when a new file is opened.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, or {@link ZipFile}.
     * @param f
     *      File being opened.
     */
    public static synchronized void open(Object _this, File f) {
        put(_this, new PathRecord(f.toPath()));

        for (ActivityListener al : ActivityListener.LIST) {
            al.open(_this, f);
        }
    }

    /**
     * Called when a new path is opened.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, or {@link ZipFile}.
     * @param p
     *      Path being opened.
     */
    public static synchronized void open(Object _this, Path p) {
        put(_this, new PathRecord(p));

        for (ActivityListener al : ActivityListener.LIST) {
            al.open(_this, p);
        }
    }

    @SuppressFBWarnings(
            value = "PATH_TRAVERSAL_IN",
            justification = "path comes from sun.nio.fs.UnixChannelFactory.newFileChannel(int, sun.nio.fs.UnixPath, "
                    + "java.lang.String, java.util.Set<? extends java.nio.file.OpenOption>, int). At this point, the path "
                    + "is not controlled by the user.")
    public static synchronized void openFileString(Object _this, FileDescriptor fileDescriptor, String path) {
        open(_this, Paths.get(path));
    }

    /**
     * Called when a pipe is opened, e.g. via SelectorProvider
     *
     * @param _this
     * 		{@link java.nio.channels.spi.SelectorProvider}
     */
    public static synchronized void openPipe(Object _this) {
        if (_this instanceof Pipe.SourceChannel) {
            put(_this, new SourceChannelRecord((Pipe.SourceChannel) _this));
            for (ActivityListener al : ActivityListener.LIST) {
                al.fd_open(_this);
            }
        }
        if (_this instanceof Pipe.SinkChannel) {
            put(_this, new SinkChannelRecord((Pipe.SinkChannel) _this));
            for (ActivityListener al : ActivityListener.LIST) {
                al.fd_open(_this);
            }
        }
    }

    public static synchronized void openFileChannel(FileChannel fileChannel, Path path) {
        open(fileChannel, path);
    }

    public static synchronized void openFileChannel(SeekableByteChannel byteChannel, Path path) {
        open(byteChannel, path);
    }

    public static synchronized void openDirectoryStream(DirectoryStream<?> directoryStream, Path path) {
        open(directoryStream, path);
    }

    public static synchronized void openSelector(Object _this) {
        if (_this instanceof Selector) {
            put(_this, new SelectorRecord((Selector) _this));
            for (ActivityListener al : ActivityListener.LIST) {
                al.fd_open(_this);
            }
        }
    }

    /**
     * Called when a socket is opened.
     */
    public static synchronized void openSocket(Object _this) {
        // intercept when
        if (_this instanceof SocketImpl) {
            try {
                // one of the following must be true
                SocketImpl si = (SocketImpl) _this;
                Socket s = (Socket) SOCKETIMPL_SOCKET.get(si);
                if (s != null) {
                    put(_this, new SocketRecord(s));
                    for (ActivityListener al : ActivityListener.LIST) {
                        al.openSocket(s);
                    }
                }
                ServerSocket ss = (ServerSocket) SOCKETIMPL_SERVER_SOCKET.get(si);
                if (ss != null) {
                    put(_this, new ServerSocketRecord(ss));
                    for (ActivityListener al : ActivityListener.LIST) {
                        al.openSocket(ss);
                    }
                }
            } catch (IllegalAccessException e) {
                throw new AssertionError(e);
            }
        }
        if (_this instanceof SocketChannel) {
            put(_this, new SocketChannelRecord((SocketChannel) _this));

            for (ActivityListener al : ActivityListener.LIST) {
                al.openSocket(_this);
            }
        }
    }

    public static synchronized List<Record> getCurrentOpenFiles() {
        return new ArrayList<>(TABLE.values());
    }

    private static synchronized void put(Object _this, Record r) {
        // handle excludes
        if (r.exclude()) {
            if (TRACE != null && !tracing) {
                tracing = true;
                r.dump("Excluded ", TRACE);
                tracing = false;
            }
            return;
        }

        TABLE.put(_this, r);
        if (TABLE.size() > THRESHOLD) {
            THRESHOLD = 999999;
            dump(ERROR);
        }
        if (TRACE != null && !tracing) {
            tracing = true;
            r.dump("Opened ", TRACE);
            tracing = false;
        }
    }

    /**
     * Called when a file is closed.
     *
     * This method tolerates a double-close where a close method is called on an already closed object.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, {@link Socket}, {@link ServerSocket}, or {@link ZipFile}.
     */
    public static synchronized void close(Object _this) {
        Record r = TABLE.remove(_this);
        if (r != null && TRACE != null && !tracing) {
            tracing = true;
            r.dump("Closed ", TRACE);
            tracing = false;
        }

        for (ActivityListener al : ActivityListener.LIST) {
            al.close(_this);
        }
    }

    /**
     * Dumps all files that are currently open.
     */
    public static synchronized void dump(OutputStream out) {
        dump(new OutputStreamWriter(out, Charset.defaultCharset()));
    }

    public static synchronized void dump(Writer w) {
        if (JSON) {
            dumpJson(w);
            return;
        }
        PrintWriter pw = new PrintWriter(w);
        Record[] records = TABLE.values().toArray(new Record[0]);

        pw.println(records.length + " descriptors are open");
        int i = 0;
        for (Record r : records) {
            r.dump("#" + (++i) + " ", pw);
        }
        pw.println("----");
        pw.flush();
    }

    public static synchronized void dumpJson(Writer w) {
        PrintWriter pw = new PrintWriter(w);
        Record[] records = TABLE.values().toArray(new Record[0]);

        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        StringBuilder sb = new StringBuilder();
        sb.append("{\"timestamp\":\"").append(sdf.format(new Date())).append("\"");
        sb.append(",\"openDescriptors\":").append(records.length);
        sb.append(",\"descriptors\":[");

        for (int i = 0; i < records.length; i++) {
            Record r = records[i];
            if (i > 0) {
                sb.append(",");
            }
            sb.append("{\"index\":").append(i + 1);
            sb.append(",\"type\":\"").append(jsonEscape(r.getType())).append("\"");
            sb.append(",\"resource\":\"").append(jsonEscape(r.getResource())).append("\"");
            sb.append(",\"thread\":\"").append(jsonEscape(r.threadName)).append("\"");
            sb.append(",\"openedAt\":\"").append(jsonEscape(format(r.time))).append("\"");
            sb.append(",\"stackTrace\":[");
            List<String> frames = r.getFilteredStackTrace();
            for (int j = 0; j < frames.size(); j++) {
                if (j > 0) {
                    sb.append(",");
                }
                sb.append("\"").append(jsonEscape(frames.get(j))).append("\"");
            }
            sb.append("]}");
        }

        sb.append("]}");
        pw.println(sb.toString());
        pw.flush();
    }

    private static String jsonEscape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        String retval = sb.toString();
        return retval;
    }

    /**
     * Called when the system has too many open files.
     */
    public static synchronized void outOfDescriptors() {
        if (ERROR != null && !tracing) {
            tracing = true;
            ERROR.println("Too many open files");
            dump(ERROR);
            tracing = false;
        }
    }

    private static String format(long time) {
        try {
            return new Date(time).toString();
        } catch (Exception e) {
            return Long.toString(time);
        }
    }

    private static final Field SOCKETIMPL_SOCKET, SOCKETIMPL_SERVER_SOCKET;

    static {
        SOCKETIMPL_SOCKET = getSocketField("socket");
        SOCKETIMPL_SERVER_SOCKET = getSocketField("serverSocket");
    }

    private static Field getSocketField(String socket) {
        try {
            Field socketimplSocket = SocketImpl.class.getDeclaredField(socket);
            socketimplSocket.setAccessible(true);

            return socketimplSocket;
        } catch (NoSuchFieldException e) {
            // Java 17+ changed the implementation of Sockets and
            // so the current approach does not work there anymore
            // for now we gracefully handle this and do keep file-leak-detector
            // useful for other types of file-handle-leaks
            System.err.println("Could not load field " + socket + " from SocketImpl: " + e);
            return null;
        }
    }
}
