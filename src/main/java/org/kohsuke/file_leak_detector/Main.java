package org.kohsuke.file_leak_detector;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;

/**
 * Entry point for externally attaching agent into another local process.
 *
 * @author Kohsuke Kawaguchi
 */
public class Main {
    @Argument(index = 0, metaVar = "PID", usage = "Process ID to activate file leak detector", required = true)
    public String pid;

    @Argument(index = 1, metaVar = "OPTSTR", usage = "Packed option string of the form key1[=value1],key2[=value2],...")
    public String options;

    public static void main(String[] args) {
        Main main = new Main();
        CmdLineParser p = new CmdLineParser(main);
        try {
            p.parseArgument(args);
            main.run();
        } catch (CmdLineException e) {
            System.err.println(e.getMessage());
            fail(p);
        } catch (InvocationTargetException e) {
            /* The main cause of the exception in the agent is lost outside of the loadAgent method.
            The instrumentation framework remove it in the AgentInitializationException object. We only can print
            an error to let the parent invoker know something went wrong
             */
            e.getCause().printStackTrace();
            fail(p);
        } catch (Exception e) {
            e.printStackTrace();
            fail(p);
        }
    }

    private static void fail(CmdLineParser p) {
        System.err.println("java -jar file-leak-detector.jar PID [OPTSTR]");
        p.printUsage(System.err);
        System.err.println("\nOptions:");
        AgentMain.printOptions();
        System.exit(1);
    }

    public void run() throws IOException, ReflectiveOperationException {
        Class<?> api = loadAttachApi();

        System.out.println("Connecting to " + pid);
        Object vm = api.getMethod("attach", String.class).invoke(null, pid);

        try {
            File agentJar = whichJar(getClass());
            System.out.println("Activating file leak detector at " + agentJar);
            // load a specified agent onto the JVM
            // pass the hidden option to prevent this from killing the target JVM if the options were wrong
            api.getMethod("loadAgent", String.class, String.class)
                    .invoke(vm, agentJar.getPath(), options == null ? "noexit" : "noexit," + options);
        } finally {
            api.getMethod("detach").invoke(vm);
        }
    }

    /**
     * Loads the {@code VirtualMachine} class as the entry point to the attach API.
     */
    private Class<?> loadAttachApi() throws MalformedURLException {
        File toolsJar = locateToolsJar();

        ClassLoader cl = wrapIntoClassLoader(toolsJar);
        try {
            return cl.loadClass("com.sun.tools.attach.VirtualMachine");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                    "Unable to find tools.jar at " + toolsJar + " --- you need to run this tool with a JDK", e);
        }
    }

    /**
     * Figures out how to load tools.jar into a classloader.
     *
     * The attachment API relies on JNI, so if we have other processes in the JVM that tries to use the attach API
     * (like JavaMelody), it'll cause a failure. So we try to load tools.jar into the application classloadr
     * so that later attempts to load tools.jar will see it.
     */
    protected ClassLoader wrapIntoClassLoader(File toolsJar) throws MalformedURLException {
        URL jar = toolsJar.toURI().toURL();

        ClassLoader base = getClass().getClassLoader();
        if (!toolsJar.exists()) {
            return base;
        }
        if (base instanceof URLClassLoader) {
            try {
                Method addURL = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                addURL.setAccessible(true);
                addURL.invoke(base, jar);
                return base;
            } catch (Exception e) {
                // if that fails, load into a separate classloader
                LOGGER.log(Level.WARNING, "Failed to load tools.jar into appclassloader", e);
            }
        }

        return new URLClassLoader(new URL[] {jar}, base);
    }

    /**
     * Locates the {@code tools.jar} file. Note that on Mac there's no such file but the class is still loadable.
     */
    private File locateToolsJar() {
        File home = new File(System.getProperty("java.home"));
        return new File(home, "../lib/tools.jar");
    }

    /**
     * Finds the jar file from a reference to class within.
     */
    private File whichJar(Class<?> c) {
        try {
            ProtectionDomain pd = c.getProtectionDomain();
            CodeSource cs = pd.getCodeSource();
            URL url = cs.getLocation();
            URI uri = url.toURI();
            return new File(uri);
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Unable to figure out the file of the jar", ex);
        }
    }

    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
}
