package org.kohsuke.file_leak_detector;

import org.kohsuke.asm3.Label;
import org.kohsuke.asm3.MethodAdapter;
import org.kohsuke.asm3.MethodVisitor;
import org.kohsuke.asm3.Opcodes;
import org.kohsuke.asm3.Type;
import org.kohsuke.asm3.commons.LocalVariablesSorter;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detector.transform.CodeGenerator;
import org.kohsuke.file_leak_detector.transform.MethodAppender;
import org.kohsuke.file_leak_detector.transform.TransformerImpl;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.channels.SocketChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;

import static org.kohsuke.asm3.Opcodes.*;

/**
 * Java agent that instruments JDK classes to keep track of where file descriptors are opened.
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("Since15")
public class Main {
    public static void agentmain(String agentArguments, Instrumentation instrumentation) throws Exception {
        premain(agentArguments,instrumentation);
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) throws Exception {
        if(agentArguments!=null) {
            for (String t : agentArguments.split(",")) {
                if(t.equals("help")) {
                    usageAndQuit();
                } else
                if(t.startsWith("threshold=")) {
                    Listener.THRESHOLD = Integer.parseInt(t.substring("threshold=".length()));
                } else
                if(t.equals("trace")) {
                    Listener.TRACE = System.err;
                } else
                if(t.equals("strong")) {
                    Listener.makeStrong();
                } else
                if(t.startsWith("trace=")) {
                    Listener.TRACE = new PrintStream(new FileOutputStream(agentArguments.substring(6)));
                } else
                if(t.startsWith("error=")) {
                    Listener.ERROR = new PrintStream(new FileOutputStream(agentArguments.substring(6)));
                } else {
                    System.err.println("Unknown option: "+t);
                    usageAndQuit();
                }
            }
        }

        System.err.println("File leak detector installed");
        Listener.AGENT_INSTALLED = true;
        instrumentation.addTransformer(new TransformerImpl(createSpec()),true);
        
        instrumentation.retransformClasses(
                FileInputStream.class,
                FileOutputStream.class,
                RandomAccessFile.class,
                ZipFile.class);

        // still haven't fully figured out how to intercept NIO, especially with close, so commenting out
//                Socket.class,
//                SocketChannel.class,
//                AbstractInterruptibleChannel.class,
//                ServerSocket.class);
    }

    private static void usageAndQuit() {
        System.err.println("File leak detector arguments (to specify multiple values, separate them by ',':");
        System.err.println("  help        - show the help screen.");
        System.err.println("  trace       - log every open/close operation to stderr.");
        System.err.println("  trace=FILE  - log every open/close operation to the given file.");
        System.err.println("  error=FILE  - if 'too many open files' error is detected, send the dump here.");
        System.err.println("                by default it goes to stderr.");
        System.err.println("  threshold=N - instead of waiting until 'too many open files', dump once");
        System.err.println("                we have N descriptors open.");
        System.err.println("  strong      - Don't let GC auto-close leaking file descriptors");
        System.exit(-1);
    }

    static List<ClassTransformSpec> createSpec() {
        return Arrays.asList(
            newSpec(FileOutputStream.class, "(Ljava/io/File;Z)V"),
            newSpec(FileInputStream.class, "(Ljava/io/File;)V"),
            newSpec(RandomAccessFile.class, "(Ljava/io/File;Ljava/lang/String;)V"),
            newSpec(ZipFile.class, "(Ljava/io/File;I)V"),
            new ClassTransformSpec(ServerSocket.class,
                    new OpenSocketInterceptor("bind", "(Ljava/net/SocketAddress;I)V"),
                    new CloseInterceptor()
            ),
            new ClassTransformSpec(Socket.class,
                    new OpenSocketInterceptor("connect", "(Ljava/net/SocketAddress;I)V"),
                    new OpenSocketInterceptor("postAccept", "()V"),
                    new CloseInterceptor()
            ),
            new ClassTransformSpec(SocketChannel.class,
                    new OpenSocketInterceptor("<init>", "(Ljava/nio/channels/spi/SelectorProvider;)V"),
                    new CloseInterceptor()
            ),
            new ClassTransformSpec(AbstractInterruptibleChannel.class,
                    new CloseInterceptor()
            )
        );
    }

    /**
     * Creates {@link ClassTransformSpec} that intercepts
     * a constructor and the close method.
     */
    private static ClassTransformSpec newSpec(final Class c, String constructorDesc) {
        final String binName = c.getName().replace('.', '/');
        return new ClassTransformSpec(binName,
            new ConstructorOpenInterceptor(constructorDesc, binName),
            new CloseInterceptor()
        );
    }

    /**
     * Intercepts the {@code void close()} method and calls {@link Listener#close(Object)}
     */
    private static class CloseInterceptor extends MethodAppender {
        public CloseInterceptor() {
            super("close", "()V");
        }

        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class,"close",
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }

    private static class OpenSocketInterceptor extends MethodAppender {
        public OpenSocketInterceptor(String name, String desc) {
            super(name,desc);
        }

        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class,"openSocket",
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }

    /**
     * Intercepts the this.open(...) call in the constructor.
     */
    private static class ConstructorOpenInterceptor extends MethodAppender {
        /**
         * Binary name of the class being transformed.
         */
        private final String binName;

        public ConstructorOpenInterceptor(String constructorDesc, String binName) {
            super("<init>", constructorDesc);
            this.binName = binName;
        }

        // this causes VerifyError (run with -Xverify:all to confirm this on Mustang, or else the rt.jar classes won't be verified)
        @Override
        public MethodVisitor newAdapter(MethodVisitor base, int access, String name, String desc, String signature, String[] exceptions) {
            final MethodVisitor b = super.newAdapter(base, access, name, desc, signature, exceptions);
            final LocalVariablesSorter lvs = new LocalVariablesSorter(access,desc, b);
            return new MethodAdapter(lvs) {
                // surround the open/openAppend calls with try/catch block
                // to intercept "Too many open files" exception
                @Override
                public void visitMethodInsn(int opcode, String owner, String name, String desc) {
                    if(owner.equals(binName)
                    && name.startsWith("open")) {
                        CodeGenerator g = new CodeGenerator(mv);
                        Label s = new Label(); // start of the try block
                        Label e = new Label();  // end of the try block
                        Label h = new Label();  // handler entry point
                        Label tail = new Label();   // where the execution continue

                        g.visitTryCatchBlock(s, e, h, "java/io/FileNotFoundException");
                        g.visitLabel(s);
                        super.visitMethodInsn(opcode, owner, name, desc);
                        g._goto(tail);

                        g.visitLabel(e);
                        g.visitLabel(h);
                        // [RESULT]
                        // catch(FileNotFoundException ex) {
                        //    boolean b = ex.getMessage().contains("Too many open files");
                        int ex = lvs.newLocal(Type.getType(FileNotFoundException.class));
                        g.dup();
                        b.visitVarInsn(ASTORE, ex);
                        g.invokeVirtual("java/io/FileNotFoundException","getMessage","()Ljava/lang/String;");
                        g.ldc("Too many open files");
                        g.invokeVirtual("java/lang/String","contains","(Ljava/lang/CharSequence;)Z");

                        // too many open files detected
                        //    if (b) { Listener.outOfDescriptors() }
                        Label rethrow = new Label();
                        g.ifFalse(rethrow);

                        g.invokeAppStatic(Listener.class,"outOfDescriptors",
                                new Class[0], new int[0]);

                        // rethrow the FileNotFoundException
                        g.visitLabel(rethrow);
                        b.visitVarInsn(ALOAD, ex);
                        g.athrow();

                        // normal execution continues here
                        g.visitLabel(tail);
                    } else
                        // no processing
                        super.visitMethodInsn(opcode, owner, name, desc);
                }
            };
        }

        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class,"open",
                    new Class[]{Object.class, File.class},
                    new int[]{0,1});
        }
    }
}
