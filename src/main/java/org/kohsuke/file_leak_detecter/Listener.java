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

        private Record(File file) {
            this.file = file;
        }

        public void dump(PrintStream ps) {
            ps.println(file);
            stackTrace.printStackTrace(ps);
        }
    }

    /**
     * Files that are currently open.
     */
    private static final Map<Object,Record> TABLE = new HashMap<Object,Record>();

    /**
     *
     * @param _this
     *      {@link FileInputStream}, {@link FileOutputStream}, or {@link RandomAccessFile}.
     */
    public static synchronized void open(Object _this, File f) {
        TABLE.put(_this,new Record(f));
    }

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
