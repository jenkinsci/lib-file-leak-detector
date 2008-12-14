package org.kohsuke.file_leak_detecter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.PrintStream;
import java.util.Map;
import java.util.HashMap;
import java.util.Date;

/**
 * Intercepted JDK calls land here.
 * 
 * @author Kohsuke Kawaguchi
 */
public class Listener {

    private static final class Record {
        public final File file;
        public final Exception stackTrace = new Exception();
        public final String threadName;
        public final long time;

        private Record(File file) {
            this.file = file;
            // keeping a Thread would potentially leak a thread, so let's just do a name
            this.threadName = Thread.currentThread().getName();
            this.time = System.currentTimeMillis();
        }

        public void dump(String prefix, PrintStream ps) {
            ps.println(prefix+file+" by thread:"+threadName+" on "+new Date(time));
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
     * Files that are currently open.
     */
    private static final Map<Object,Record> TABLE = new HashMap<Object,Record>();

    /**
     * Trace the open/close op
     */
    public static boolean TRACE = false;

    /**
     * Tracing may cause additional files to be opened.
     * In such a case, avoid infinite recursion.
     */
    private static boolean tracing = false;

    /**
     * Called when a new file is opened.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, or {@link RandomAccessFile}.
     * @param f
     *      File being opened.
     */
    public static synchronized void open(Object _this, File f) {
        Record r = new Record(f);
        TABLE.put(_this, r);
        if(TRACE && !tracing) {
            tracing = true;
            r.dump("Opened ",System.err);
            tracing = false;
        }
    }

    /**
     * Called when a file is closed.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, or {@link RandomAccessFile}.
     */
    public static synchronized void close(Object _this) {
        Record r = TABLE.remove(_this);
        if(r!=null && TRACE && !tracing) {
            r.dump("Closed ",System.err);
            tracing = true;
            r.dump("Opened ",System.err);
            tracing = false;
        }
    }

    /**
     * Dumps all files that are currently open.
     */
    public static synchronized void dump(PrintStream ps) {
        ps.println(TABLE.size()+" files are open");
        for (Record r : TABLE.values())
            r.dump("",ps);
    }
}
