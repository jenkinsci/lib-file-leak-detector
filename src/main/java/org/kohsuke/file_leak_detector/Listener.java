package org.kohsuke.file_leak_detector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.PrintStream;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;

/**
 * Intercepted JDK calls land here.
 * 
 * @author Kohsuke Kawaguchi
 */
public class Listener {
    /**
     * Remembers who/where/when opened a file.
     */
    private static class Record {
        public final Exception stackTrace = new Exception();
        public final String threadName;
        public final long time;

        protected Record() {
            // keeping a Thread would potentially leak a thread, so let's just do a name
            this.threadName = Thread.currentThread().getName();
            this.time = System.currentTimeMillis();
        }

        public void dump(String prefix, PrintStream ps) {
            StackTraceElement[] trace = stackTrace.getStackTrace();
            int i=0;
            // skip until we find the Method.invoke() that called us
            for (; i<trace.length; i++)
                if(trace[i].getClassName().equals("java.lang.reflect.Method")) {
                    i++;
                    break;
                }
            // print the rest
            for (; i < trace.length; i++)
                ps.println("\tat " + trace[i]);
        }
    }

    /**
     * Record of opened file.
     */
    private static final class FileRecord extends Record {
        public final File file;

        private FileRecord(File file) {
            this.file = file;
        }

        public void dump(String prefix, PrintStream ps) {
            ps.println(prefix+file+" by thread:"+threadName+" on "+new Date(time));
            super.dump(prefix,ps);
        }
    }

    /**
     * Record of opened socket.
     */
    private static final class SocketRecord extends Record {
        public final Socket socket;
        public final String peer;

        private SocketRecord(Socket socket) {
            this.socket = socket;
            peer = socket.getRemoteSocketAddress().toString();
        }

        public void dump(String prefix, PrintStream ps) {
            ps.println(prefix+"socket to "+peer+" by thread:"+threadName+" on "+new Date(time));
            super.dump(prefix,ps);
        }
    }

    /**
     * Record of opened server socket.
     */
    private static final class ServerSocketRecord extends Record {
        public final ServerSocket socket;
        public final String address;

        private ServerSocketRecord(ServerSocket socket) {
            this.socket = socket;
            address = socket.getLocalSocketAddress().toString();
        }

        public void dump(String prefix, PrintStream ps) {
            ps.println(prefix+"server socket at "+address+" by thread:"+threadName+" on "+new Date(time));
            super.dump(prefix,ps);
        }
    }

    /**
     * Record of opened SocketChannel.
     */
    private static final class SocketChannelRecord extends Record {
        public final SocketChannel socket;

        private SocketChannelRecord(SocketChannel socket) {
            this.socket = socket;
        }

        public void dump(String prefix, PrintStream ps) {
            ps.println(prefix+"socket channel by thread:"+threadName+" on "+new Date(time));
            super.dump(prefix,ps);
        }
    }

    /**
     * Files that are currently open.
     */
    private static final Map<Object,Record> TABLE = new HashMap<Object,Record>();

    /**
     * Trace the open/close op
     */
    public static PrintStream TRACE = null;

    /**
     * Trace the "too many open files" error here
     */
    public static PrintStream ERROR = System.err;

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
     * Called when a new file is opened.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, or {@link RandomAccessFile}.
     * @param f
     *      File being opened.
     */
    public static synchronized void open(Object _this, File f) {
        put(_this, new FileRecord(f));
    }

    /**
     * Called when a socket is opened.
     */
    public static synchronized void openSocket(Object _this) {
        if (_this instanceof Socket) {
            put(_this, new SocketRecord((Socket) _this));
        }
        if (_this instanceof ServerSocket) {
            put(_this, new ServerSocketRecord((ServerSocket) _this));
        }
        if (_this instanceof SocketChannel) {
            put(_this, new SocketChannelRecord((SocketChannel) _this));
        }
    }
    
    private static synchronized void put(Object _this, Record r) {
        TABLE.put(_this, r);
        if(TABLE.size()>THRESHOLD) {
            dump(ERROR);
            THRESHOLD+=10;
        }
        if(TRACE!=null && !tracing) {
            tracing = true;
            r.dump("Opened ",TRACE);
            tracing = false;
        }
    }

    /**
     * Called when a file is closed.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, {@link Socket}, or {@link ServerSocket}.
     */
    public static synchronized void close(Object _this) {
        Record r = TABLE.remove(_this);
        if(r!=null && TRACE!=null && !tracing) {
            tracing = true;
            r.dump("Closed ",TRACE);
            tracing = false;
        }
    }

    /**
     * Dumps all files that are currently open.
     */
    public static synchronized void dump(PrintStream ps) {
        ps.println(TABLE.size()+" descriptors are open");
        for (Record r : TABLE.values())
            r.dump("",ps);
    }

    /**
     * Called when the system has too many open files.
     */
    public static synchronized void outOfDescriptors() {
        if(ERROR!=null && !tracing) {
            tracing = true;
            ERROR.println("Too many open files");
            dump(ERROR);
            tracing = false;
        }
    }
}
