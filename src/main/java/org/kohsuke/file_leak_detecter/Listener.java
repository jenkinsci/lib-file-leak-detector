package org.kohsuke.file_leak_detecter;

import java.io.File;

/**
 * @author Kohsuke Kawaguchi
 */
public class Listener {
    public static void open(File f) {
        System.out.println(f+" opened");
    }
}
