package org.kohsuke.file_leak_detecter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.RandomAccessFile;
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
    public static void open(Object _this, File f) {
        System.out.println(_this+" opened "+f);
        TABLE.put(_this,new Record(f));
    }

    public static void close(Object _this) {
        System.out.println(_this+" closed");
        TABLE.remove(_this);
    }
}
