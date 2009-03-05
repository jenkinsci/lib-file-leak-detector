package org.kohsuke.file_leak_detecter;

import org.kohsuke.file_leak_detecter.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detecter.transform.CodeGenerator;
import org.kohsuke.file_leak_detecter.transform.MethodAppender;
import org.kohsuke.file_leak_detecter.transform.TransformerImpl;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodAdapter;
import org.objectweb.asm.MethodVisitor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.Socket;
import java.net.ServerSocket;

/**
 * Java agent that instruments JDK classes to keep track of where file descriptors are opened.
 * @author Kohsuke Kawaguchi
 */
public class Main {
    public static void premain(String agentArguments, Instrumentation instrumentation) throws UnmodifiableClassException, IOException {
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

        System.err.println("File leak detecter installed");
        instrumentation.addTransformer(new TransformerImpl(
            newSpec(FileOutputStream.class,"(Ljava/io/File;Z)V"),
            newSpec(FileInputStream.class, "(Ljava/io/File;)V"),
            newSpec(RandomAccessFile.class,"(Ljava/io/File;Ljava/lang/String;)V"),
            new ClassTransformSpec(ServerSocket.class,
                new OpenSocketIntercepter("bind", "(Ljava/net/SocketAddress;I)V"),
                new CloseIntercepter()
            ),
            new ClassTransformSpec(Socket.class,
                new OpenSocketIntercepter("connect", "(Ljava/net/SocketAddress;I)V"),
                new OpenSocketIntercepter("postAccept", "()V"),
                new CloseIntercepter()
            )
        ),true);
        instrumentation.retransformClasses(
                FileInputStream.class,
                FileOutputStream.class,
                RandomAccessFile.class,
                Socket.class,
                ServerSocket.class);

//        // test code
//        for( int i=0; true; i++ ) {
//            FileOutputStream o = new FileOutputStream("target/dummy"+i);
//            o.write("abc".getBytes());
//        }
////        Listener.dump(System.out);
////        o.close();
//
////        System.out.println("after close");
////        Listener.dump(System.out);
    }

    private static void usageAndQuit() {
        System.err.println("File leak detecter arguments (to specify multiple values, separate them by ',':");
        System.err.println("  help        - show the help screen.");
        System.err.println("  trace       - log every open/close operation to stderr.");
        System.err.println("  trace=FILE  - log every open/close operation to the given file.");
        System.err.println("  error=FILE  - if 'too many open files' error is detected, send the dump here.");
        System.err.println("                by default it goes to stderr.");
        System.err.println("  threshold=N - instead of waiting until 'too many open files', dump once");
        System.err.println("                we have N descriptors open.");
        System.exit(-1);
    }

    /**
     * Creates {@link ClassTransformSpec} that intercepts
     * a constructor and the close method.
     */
    private static ClassTransformSpec newSpec(final Class c, String constructorDesc) {
        final String binName = c.getName().replace('.', '/');
        return new ClassTransformSpec(binName,
            new MethodAppender("<init>", constructorDesc) {
                @Override
                public MethodVisitor newAdapter(final MethodVisitor base) {
                    return new MethodAdapter(super.newAdapter(base)) {
                        // surround the open/openAppend calls with try/catch block
                        // to intercept "Too many open files" exception
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name, String desc) {
                            if(owner.equals(binName)
                            && name.startsWith("open")) {
                                CodeGenerator g = new CodeGenerator(base);
                                Label s = new Label(); // start of the try block
                                Label e = new Label();  // end of the try block
                                Label h = new Label();  // handler entry point
                                Label tail = new Label();   // where the execution continue

                                g.visitTryCatchBlock(s,e,h,"java/io/FileNotFoundException");
                                g.visitLabel(s);
                                super.visitMethodInsn(opcode, owner, name, desc);
                                g.visitLabel(e);
                                g._goto(tail);

                                g.visitLabel(h);
                                // [RESULT]
                                // catch(FileNotFoundException e) {
                                //    boolean b = e.getMessage().contains("Too many open files")
                                g.dup();
                                g.invokeVirtual("java/io/FileNotFoundException","getMessage","()Ljava/lang/String;");
                                g.ldc("Too many open files");
                                g.invokeVirtual("java/lang/String","contains","(Ljava/lang/CharSequence;)Z");

                                Label rethrow = new Label();
                                g.ifFalse(rethrow);

                                // too many open files detected
                                g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","outOfDescriptors",
                                        new Class[0], new int[0]);

                                // rethrow the FileNotFoundException
                                g.visitLabel(rethrow);
                                g.athrow();

                                // normal execution continutes here
                                g.visitLabel(tail);
                            } else
                                // no processing
                                super.visitMethodInsn(opcode, owner, name, desc);
                        }
                    };
                }

                protected void append(CodeGenerator g) {
                    g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","open",
                            new Class[]{Object.class, File.class},
                            new int[]{0,1});
                }
            },
            new CloseIntercepter()
        );
    }

    private static class CloseIntercepter extends MethodAppender {
        public CloseIntercepter() {
            super("close", "()V");
        }

        protected void append(CodeGenerator g) {
            g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","close",
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }

    private static class OpenSocketIntercepter extends MethodAppender {
        public OpenSocketIntercepter(String name, String desc) {
            super(name,desc);
        }

        protected void append(CodeGenerator g) {
            g.invokeAppStatic("org.kohsuke.file_leak_detecter.Listener","openSocket",
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }
}
