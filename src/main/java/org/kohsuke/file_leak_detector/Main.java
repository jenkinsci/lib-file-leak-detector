package org.kohsuke.file_leak_detector;

import com.sun.tools.attach.VirtualMachine;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Entry point for externally attaching agent into another local process.
 *
 * @author Kohsuke Kawaguchi
 */
public class Main {
    @Argument(index=0,metaVar="PID",usage="Process ID to activate file leak detector",required=true)
    public String pid;
    
    @Argument(index=1,metaVar="OPTSTR",usage="Packed option string for the file leak detector")
    public String options;
    
    public static void main(String[] args) throws Exception {
        Main main = new Main();
        CmdLineParser p = new CmdLineParser(main);
        try {
            p.parseArgument(args);
            main.run();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            System.err.println("java -jar file-leak-detector.jar PID [OPTSTR]");
            p.printUsage(System.err);
            System.exit(1);
        }
    }

    public void run() throws Exception {
        Class api = loadAttachApi();

        System.out.println("Connecting to "+pid);
        Object vm = api.getMethod("attach",String.class).invoke(null,pid);

        try {
            File agentJar = whichJar(getClass());
            System.out.println("Activating file leak detector at "+agentJar);
            // load a specified agent onto the JVM
            api.getMethod("loadAgent",String.class,String.class).invoke(vm, agentJar.getPath(), options);
        } finally {
            api.getMethod("detach").invoke(vm);
        }
    }

    /**
     * Loads the {@link VirtualMachine} class as the entry point to the attach API.
     */
    private Class loadAttachApi() throws MalformedURLException, ClassNotFoundException {
        File toolsJar = locateToolsJar();
        URLClassLoader cl = new URLClassLoader(new URL[]{toolsJar.toURI().toURL()},getClass().getClassLoader());
        try {
            return cl.loadClass("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Unable to find tools.jar at "+toolsJar+" --- you need to run this tool with a JDK",e);
        }
    }

    /**
     * Locates the {@code tools.jar} file. Note that on Mac there's no such file but the class is still loadable.
     */
    private File locateToolsJar() {
        File home = new File(System.getProperty("java.home"));
        return new File(home,"../lib/tools.jar");
    }

    /**
     * Finds the jar file from a reference to class within.
     */
    private File whichJar(Class c) {
        String url = c.getClassLoader().getResource(c.getName().replace('.', '/') + ".class").toExternalForm();
        if (url.startsWith("jar:file:")) {
            url = url.substring(0,url.lastIndexOf('!'));
            url = url.substring(9);
            return new File(url);
        }
        throw new IllegalStateException("Unable to figure out the file of the jar: "+url);
    }
}
