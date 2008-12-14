package org.kohsuke.file_leak_detecter;

import org.kohsuke.file_leak_detecter.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detecter.transform.CodeGenerator;
import org.kohsuke.file_leak_detecter.transform.MethodAppender;
import org.kohsuke.file_leak_detecter.transform.TransformerImpl;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.RandomAccessFile;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;

/**
 * @author Kohsuke Kawaguchi
 */
public class Main {
    public static void premain(String agentArguments, Instrumentation instrumentation) throws UnmodifiableClassException, IOException {
        if(agentArguments!=null) {
            if(agentArguments.equals("help")) {
                System.err.println("File leak detecter arguments:");
                System.err.println("  help        - show the help screen.");
                System.err.println("  trace       - log every open/close operation to stderr.");
                System.err.println("  trace=FILE  - log every open/close operation to the given file.");
                System.err.println("  threshold=N - ");
                System.exit(-1);
            }
            if(agentArguments.equals("trace")) {
                Listener.TRACE = System.err;
            }
            if(agentArguments.startsWith("trace=")) {
                Listener.TRACE = new PrintStream(new FileOutputStream(agentArguments.substring(6)));
            }
        }

        System.err.println("File leak detecter installed");
        instrumentation.addTransformer(new TransformerImpl(
            newSpec(FileOutputStream.class,"(Ljava/io/File;Z)V"),
            newSpec(FileInputStream.class, "(Ljava/io/File;)V"),
            newSpec(RandomAccessFile.class,"(Ljava/io/File;Ljava/lang/String;)V")
        ),true);
        instrumentation.retransformClasses(
                FileInputStream.class,
                FileOutputStream.class,
                RandomAccessFile.class);

        // test code
        FileOutputStream o = new FileOutputStream("target/dummy");
        o.write("abc".getBytes());
        Listener.dump(System.out);
        o.close();

        System.out.println("after close");
        Listener.dump(System.out);
    }

    /**
     * Creates {@link ClassTransformSpec} that intercepts
     * a constructor and the close method.
     */
    private static ClassTransformSpec newSpec(final Class c, String constructorDesc) {
        return new ClassTransformSpec(c.getName().replace('.','/'),
            new MethodAppender("<init>", constructorDesc) {
                protected void append(CodeGenerator g) {
                    g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","open",
                            new Class[]{Object.class, File.class},
                            new int[]{0,1});
                }
            },
            new MethodAppender("close","()V") {
                protected void append(CodeGenerator g) {
                    g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","close",
                            new Class[]{Object.class},
                            new int[]{0});
                }
            }
        );
    }
}
