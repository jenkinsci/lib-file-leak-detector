package org.kohsuke.file_leak_detector;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.lang.reflect.Field;
import java.net.SocketAddress;
import java.net.SocketImpl;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.channels.SocketChannel;
import java.util.WeakHashMap;
import java.util.zip.ZipFile;

/**
 * Intercepted JDK calls land here.
 * 
 * @author Kohsuke Kawaguchi
 */
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
            int i=0;
            // skip until we find the Method.invoke() that called us
            for (; i<trace.length; i++)
                if(trace[i].getClassName().equals("java.lang.reflect.Method")) {
                    i++;
                    break;
                }
            // print the rest
            for (; i < trace.length; i++)
                pw.println("\tat " + trace[i]);
            pw.flush();
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

        public void dump(String prefix, PrintWriter pw) {
            pw.println(prefix + file + " by thread:" + threadName + " on " + format(time));
            super.dump(prefix,pw);
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
            return ra!=null ? ra.toString() : null;
        }

        public void dump(String prefix, PrintWriter ps) {
            // best effort at showing where it is/was listening
            String peer = this.peer;
            if (peer==null)  peer=getRemoteAddress(socket);

            ps.println(prefix+"socket to "+peer+" by thread:"+threadName+" on "+format(time));
            super.dump(prefix,ps);
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
            return la!=null ? la.toString() : null;
        }

        public void dump(String prefix, PrintWriter ps) {
            // best effort at showing where it is/was listening
            String address = this.address;
            if (address==null)  address=getLocalAddress(socket);

            ps.println(prefix+"server socket at "+address+" by thread:"+threadName+" on "+format(time));
            super.dump(prefix,ps);
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

        public void dump(String prefix, PrintWriter ps) {
            ps.println(prefix+"socket channel by thread:"+threadName+" on "+format(time));
            super.dump(prefix,ps);
        }
    }

    /**
     * Files that are currently open, keyed by the owner object (like {@link FileInputStream}.
     */
    private static Map<Object,Record> TABLE = new WeakHashMap<Object,Record>();

    /**
     * Trace the open/close op
     */
    public static PrintWriter TRACE = null;

    /**
     * Trace the "too many open files" error here
     */
    public static PrintWriter ERROR = new PrintWriter(System.err);

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
        TABLE = new LinkedHashMap<Object, Record>(TABLE);
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
        put(_this, new FileRecord(f));

        for (ActivityListener al : ActivityListener.LIST) {
            al.open(_this,f);
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
                Socket s = (Socket)SOCKETIMPL_SOCKET.get(si);
                if (s!=null) {
                    put(_this, new SocketRecord(s));
                    for (ActivityListener al : ActivityListener.LIST) {
                        al.openSocket(s);
                    }
                }
                ServerSocket ss = (ServerSocket)SOCKETIMPL_SERVER_SOCKET.get(si);
                if (ss!=null) {
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
        return new ArrayList<Record>(TABLE.values());
    }
    
    private static synchronized void put(Object _this, Record r) {
        TABLE.put(_this, r);
        if(TABLE.size()>THRESHOLD) {
            THRESHOLD=999999;
            dump(ERROR);
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
     * This method tolerates a double-close where a close method is called on an already closed object.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, {@link Socket}, {@link ServerSocket}, or {@link ZipFile}.
     */
    public static synchronized void close(Object _this) {
        Record r = TABLE.remove(_this);
        if(r!=null && TRACE!=null && !tracing) {
            tracing = true;
            r.dump("Closed ",TRACE);
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
        dump(new OutputStreamWriter(out));
    }
    public static synchronized void dump(Writer w) {
        PrintWriter pw = new PrintWriter(w);
        Record[] records = TABLE.values().toArray(new Record[0]);
        pw.println(records.length+" descriptors are open");
        int i=0;
        for (Record r : records) {
            r.dump("#"+(++i)+" ",pw);
        }
        pw.println("----");
        pw.flush();
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
    
    private static String format(long time) {
        try {
            return new Date(time).toString();
        } catch (Exception e) {
            return Long.toString(time);
        }
    }

    private static Field SOCKETIMPL_SOCKET,SOCKETIMPL_SERVER_SOCKET;
    
    static {
        try {
            SOCKETIMPL_SOCKET = SocketImpl.class.getDeclaredField("socket");
            SOCKETIMPL_SERVER_SOCKET = SocketImpl.class.getDeclaredField("serverSocket");
            SOCKETIMPL_SOCKET.setAccessible(true);
            SOCKETIMPL_SERVER_SOCKET.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new Error(e);
        }
    }
}
