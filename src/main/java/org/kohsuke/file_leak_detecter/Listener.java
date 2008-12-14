package org.kohsuke.file_leak_detecter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
import java.io.PrintStream;
import java.util.Map;
import java.util.HashMap;

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

        private Record(File file) {
            this.file = file;
            // keeping a Thread would potentially leak a thread, so let's just do a name
            this.threadName = Thread.currentThread().getName();
        }

        public void dump(PrintStream ps) {
            ps.println(file+" by "+threadName);
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
     * Called when a new file is opened.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, or {@link RandomAccessFile}.
     * @param f
     *      File being opened.
     */
    public static synchronized void open(Object _this, File f) {
        TABLE.put(_this,new Record(f));
    }

    /**
     * Called when a file is closed.
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, or {@link RandomAccessFile}.
     */
    public static synchronized void close(Object _this) {
        TABLE.remove(_this);
    }

    /**
     * Dumps all files that are currently open.
     */
    public static synchronized void dump(PrintStream ps) {
        ps.println(TABLE.size()+" files are open");
        for (Record r : TABLE.values())
            r.dump(ps);
    }
}
