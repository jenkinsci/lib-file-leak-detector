package org.kohsuke.file_leak_detecter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;

/**
 * Intercepted JDK calls land here.
 * 
 * @author Kohsuke Kawaguchi
 */
public class Listener {
    public static void open(FileOutputStream fos, File f) {
        System.out.println(fos+" opened "+f);
    }

    public static void close(FileOutputStream fos) {
        System.out.println(fos+" closed");
    }

    public static void open(FileInputStream fos, File f) {
        System.out.println(fos+" opened "+f);
    }

    public static void close(FileInputStream fos) {
        System.out.println(fos+" closed");
    }
}
