package org.kohsuke.file_leak_detector;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.zip.ZipFile;

/**
 * Allows user programs to receive callbacks for file open/close activities.
 *
 * <p>
 * Instantiate this class and put it into {@link #LIST} to start receiving callbacks.
 * Listeners must be concurrent and re-entrant safe.
 *
 * @author Michal Linhard (michal@linhard.sk)
 * @author Kohsuke Kawaguchi
 */
public abstract class ActivityListener {
    /**
     * Called when a new file is opened.
     *
     * @param obj
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, or {@link ZipFile}.
     * @param file
     *      File being opened.
     */
    public void open(Object obj, File file) {
    }

    /**
     * Called when a new socket is opened.
     *
     * @param obj
     *      {@link Socket}, {@link ServerSocket} or {@link SocketChannel}
     */
    public void openSocket(Object obj) {
    }

    /**
     * Called when a file is closed.
     *
     * This method tolerates a double-close where a close method is called on an already closed object.
     *
     * @param obj
     *      {@link FileInputStream}, {@link FileOutputStream}, {@link RandomAccessFile}, {@link Socket}, {@link ServerSocket}, or {@link ZipFile}.
     */
    public void close(Object obj) {
    }

    public void fd_open(Object obj) {

    }

    /**
     * These listeners get called.
     */
    public static final List<ActivityListener> LIST = new CopyOnWriteArrayList<>();


}
