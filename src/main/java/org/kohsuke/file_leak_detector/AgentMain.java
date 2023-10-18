package org.kohsuke.file_leak_detector;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.RandomAccessFile;
import java.lang.instrument.Instrumentation;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketImpl;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.channels.spi.AbstractInterruptibleChannel;
import java.nio.channels.spi.AbstractSelectableChannel;
import java.nio.channels.spi.AbstractSelector;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detector.transform.CodeGenerator;
import org.kohsuke.file_leak_detector.transform.MethodAppender;
import org.kohsuke.file_leak_detector.transform.TransformerImpl;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.LocalVariablesSorter;

/**
 * Java agent that instruments JDK classes to keep track of where file descriptors are opened.
 * @author Kohsuke Kawaguchi
 */
public class AgentMain {
    public static void agentmain(String agentArguments, Instrumentation instrumentation) throws Exception {
        premain(agentArguments,instrumentation);
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) throws Exception {
        int serverPort = -1;

        if (agentArguments != null) {
            // used by Main to prevent the termination of target JVM
            boolean quit = true;

            for (String t : agentArguments.split(",")) {
                if (t.equals("noexit")) {
                    quit = false;
                } else if (t.equals("help")) {
                    usage();
                    if (quit) {
                        System.exit(-1);
                    } else {
                        return;
                    }
                } else if (t.startsWith("threshold=")) {
                    Listener.THRESHOLD = Integer.parseInt(t.substring(t.indexOf('=') + 1));
                } else if (t.equals("trace")) {
                    Listener.TRACE = new PrintWriter(new OutputStreamWriter(System.err, Charset.defaultCharset()));
                } else if (t.equals("strong")) {
                    Listener.makeStrong();
                } else if (t.startsWith("http=")) {
                    serverPort = Integer.parseInt(t.substring(t.indexOf('=') + 1));
                } else if (t.startsWith("trace=")) {
                    Listener.TRACE = new PrintWriter(new OutputStreamWriter(new FileOutputStream(t.substring(6)), StandardCharsets.UTF_8));
                } else if (t.startsWith("error=")) {
                    Listener.ERROR = new PrintWriter(new OutputStreamWriter(new FileOutputStream(t.substring(6)), StandardCharsets.UTF_8));
                } else if (t.startsWith("listener=")) {
                    ActivityListener.LIST.add((ActivityListener) AgentMain.class.getClassLoader().loadClass(t.substring(9)).getDeclaredConstructor().newInstance());
                } else if (t.equals("dumpatshutdown")) {
                    Runtime.getRuntime().addShutdownHook(new Thread("File handles dumping shutdown hook") {
                        @Override
                        public void run() {
                            Listener.dump(System.err);
                        }
                    });
                } else if (t.startsWith("excludes=")) {
                    try (BufferedReader reader = Files.newBufferedReader(Paths.get(t.substring(9)), StandardCharsets.UTF_8)) {
                        while (true) {
                            String line = reader.readLine();
                            if (line == null) {
                                break;
                            }

                            String str = line.trim();
                            // add the entries from the excludes-file, but filter out empty ones and comments
                            if (!str.isEmpty() && !str.startsWith("#")) {
                                Listener.EXCLUDES.add(str);
                            }
                        }
                    }
                } else {
                    System.err.println("Unknown option: " + t);
                    usage();
                    if (quit) {
                        System.exit(-1);
                    }
                    throw new CmdLineException("Unknown option: " + t);
                }
            }
        }

        Listener.EXCLUDES.add("sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run");
        System.err.println("File leak detector installed");

        // Make sure the ActivityListener is loaded to prevent recursive death in instrumentation
        ActivityListener.LIST.size();

        Listener.AGENT_INSTALLED = true;
        instrumentation.addTransformer(new TransformerImpl(createSpec()), true);

        List<Class<?>> classes = new ArrayList<>();
        Collections.addAll(
                classes,
                FileInputStream.class,
                FileOutputStream.class,
                RandomAccessFile.class,
                ZipFile.class,
                AbstractSelectableChannel.class,
                AbstractInterruptibleChannel.class,
                FileChannel.class,
                AbstractSelector.class,
                Files.class);

        addIfFound(classes, "sun.nio.ch.SocketChannelImpl");
        addIfFound(classes, "sun.nio.ch.FileChannelImpl");
        addIfFound(classes, "java.net.AbstractPlainSocketImpl");
        addIfFound(classes, "java.net.PlainSocketImpl");
        addIfFound(classes, "sun.nio.fs.UnixDirectoryStream");
        addIfFound(classes, "sun.nio.fs.UnixSecureDirectoryStream");
        addIfFound(classes, "sun.nio.fs.WindowsDirectoryStream");

        instrumentation.retransformClasses(classes.toArray(new Class[0]));


//                Socket.class,
//                SocketChannel.class,
//                AbstractInterruptibleChannel.class,
//                ServerSocket.class);

        if (serverPort >= 0) {
            runHttpServer(serverPort);
        }
    }

    private static void addIfFound(List<Class<?>> classes, String className) {
        try {
            classes.add(Class.forName(className));
        } catch (ClassNotFoundException e) {
            // ignored here
        }
    }

    private static void runHttpServer(int port) throws IOException {
        @SuppressWarnings("resource")
        final ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("localhost", port));
        System.err.println("Serving file leak stats on http://localhost:" + ss.getLocalPort() + "/ for stats");
        final ExecutorService es = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r);
            t.setDaemon(true);
            return t;
        });
        es.submit(() -> {
            while (true) {
                final Socket s = ss.accept();
                es.submit(() -> {
                    try {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        // Read the request line (and ignore it)
                        in.readLine();

                        PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
                        w.print("HTTP/1.0 200 OK\r\nContent-Type: text/plain;charset=UTF-8\r\n\r\n");
                        Listener.dump(w);
                    } finally {
                        s.close();
                    }
                    return null;
                });
            }
        });
    }

    private static void usage() {
        System.err.println("File leak detector arguments (to specify multiple values, separate them by ',':");
        printOptions();
    }

    static void printOptions() {
        System.err.println("  help           - Show the help screen.");
        System.err.println("  noexit         - Don't exit after showing the help screen.");
        System.err.println("  trace          - Log every open/close operation to stderr.");
        System.err.println("  trace=FILE     - Log every open/close operation to the given file.");
        System.err.println("  error=FILE     - If 'too many open files' error is detected, send the dump here.");
        System.err.println("                   By default it goes to stderr.");
        System.err.println("  threshold=N    - Instead of waiting until 'too many open files', dump once");
        System.err.println("                   we have N descriptors open.");
        System.err.println("  http=PORT      - Run a mini HTTP server that you can access to get stats on demand.");
        System.err.println("                   Specify 0 to choose random available port, -1 to disable, which is default.");
        System.err.println("  strong         - Don't let GC auto-close leaking file descriptors.");
        System.err.println("  listener=S     - Specify the fully qualified name of ActivityListener class to activate from beginning.");
        System.err.println("  dumpatshutdown - Dump open file handles at shutdown.");
        System.err.println("  excludes=FILE  - Ignore files opened directly/indirectly in specific methods.");
        System.err.println("                   File lists 'some.pkg.ClassName.methodName' patterns.");
    }

    static List<ClassTransformSpec> createSpec() {
        List<ClassTransformSpec> spec = new ArrayList<>();
        Collections.addAll(
            spec,
            newSpec(FileOutputStream.class, "(Ljava/io/File;Z)V"),
            newSpec(FileInputStream.class, "(Ljava/io/File;)V"),
            newSpec(RandomAccessFile.class, "(Ljava/io/File;Ljava/lang/String;)V"),
            newSpec(ZipFile.class, "(Ljava/io/File;I)V"),

            /*
             * Detect the files opened via FileChannel.open(...) calls
             */
            new ClassTransformSpec(FileChannel.class,
                    new ReturnFromStaticMethodInterceptor("open",
                            "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/FileChannel;",
                            4,
                            "openPath",
                            Object.class,
                            Path.class)),
            /*
             * Detect instances opened via static methods in class java.nio.file.Files
             */
            new ClassTransformSpec(
                    Files.class,
                    // SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
                    new ReturnFromStaticMethodInterceptor(
                            "newByteChannel",
                            "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/SeekableByteChannel;",
                            4,
                            "openPath",
                            Object.class,
                            Path.class),
                    // DirectoryStream<Path> newDirectoryStream(Path dir)
                    new ReturnFromStaticMethodInterceptor(
                            "newDirectoryStream",
                            "(Ljava/nio/file/Path;)Ljava/nio/file/DirectoryStream;",
                            2,
                            "openPath",
                            Object.class,
                            Path.class),
                    // DirectoryStream<Path> newDirectoryStream(Path dir, String glob)
                    new ReturnFromStaticMethodInterceptor(
                            "newDirectoryStream",
                            "(Ljava/nio/file/Path;Ljava/lang/String;)Ljava/nio/file/DirectoryStream;",
                            6,
                            "openPath",
                            Object.class,
                            Path.class),
                    // DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
                    new ReturnFromStaticMethodInterceptor(
                            "newDirectoryStream",
                            "(Ljava/nio/file/Path;Ljava/nio/file/DirectoryStream$Filter;)Ljava/nio/file/DirectoryStream;",
                            3,
                            "openPath",
                            Object.class,
                            Path.class)
                    ),
            /*
             * Detect new Pipes
             */
            new ClassTransformSpec(AbstractSelectableChannel.class,
                    new ConstructorInterceptor("(Ljava/nio/channels/spi/SelectorProvider;)V", "openPipe")),
            /*
             * AbstractInterruptibleChannel is used by FileChannel and Pipes
             */
            new ClassTransformSpec(AbstractInterruptibleChannel.class,
                    new CloseInterceptor("close"))
        );
        /*
         * We need to see closing of DirectoryStream instances,
         * however they are OS-specific, so we need to list them via String-name
         */
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Collections.addAll(
                    spec,
                    new ClassTransformSpec(
                            "sun/nio/fs/UnixDirectoryStream", new CloseInterceptor("close")),
                    new ClassTransformSpec(
                            "sun/nio/fs/UnixSecureDirectoryStream", new CloseInterceptor("close")));
        } else {
            Collections.addAll(
                    spec,
                    new ClassTransformSpec(
                            "sun/nio/fs/WindowsDirectoryStream", new CloseInterceptor("close")));
        }
        /*
         * Detect selectors, which may open native pipes and anonymous inodes for event polling.
         */
        spec.add(new ClassTransformSpec(
                AbstractSelector.class,
                new ConstructorInterceptor("(Ljava/nio/channels/spi/SelectorProvider;)V", "openSelector"),
                new CloseInterceptor("close")));
        /*
         * java.net.Socket/ServerSocket uses SocketImpl, and this is where FileDescriptors are actually managed.
         *
         * SocketInputStream/SocketOutputStream does not maintain a separate FileDescriptor. They just all piggy back on
         * the same SocketImpl instance.
         */
        if (Runtime.version().feature() < 19) {
            spec.add(new ClassTransformSpec(
                    "java/net/PlainSocketImpl",
                    // this is where a new file descriptor is allocated.
                    // it'll occupy a socket even before it gets connected
                    new OpenSocketInterceptor("create", "(Z)V"),

                    // When a socket is accepted, it goes to "accept(SocketImpl s)"
                    // where 's' is the new socket and 'this' is the server socket
                    new AcceptInterceptor("accept", "(Ljava/net/SocketImpl;)V"),

                    // file descriptor actually get closed in socketClose()
                    // socketPreClose() appears to do something similar, but if you read the source code
                    // of the native socketClose0() method, then you see that it actually doesn't close
                    // a file descriptor.
                    new CloseInterceptor("socketClose")));
            // Later versions of the JDK abstracted out the parts of PlainSocketImpl above into a super class
            spec.add(new ClassTransformSpec(
                    "java/net/AbstractPlainSocketImpl",
                    // this is where a new file descriptor is allocated.
                    // it'll occupy a socket even before it gets connected
                    new OpenSocketInterceptor("create", "(Z)V"),

                    // When a socket is accepted, it goes to "accept(SocketImpl s)"
                    // where 's' is the new socket and 'this' is the server socket
                    new AcceptInterceptor("accept", "(Ljava/net/SocketImpl;)V"),

                    // file descriptor actually get closed in socketClose()
                    // socketPreClose() appears to do something similar, but if you read the source code
                    // of the native socketClose0() method, then you see that it actually doesn't close
                    // a file descriptor.
                    new CloseInterceptor("socketClose")));
        }
        spec.add(new ClassTransformSpec(
                "sun/nio/ch/SocketChannelImpl",
                new OpenSocketInterceptor(
                        "<init>",
                        "(Ljava/nio/channels/spi/SelectorProvider;Ljava/io/FileDescriptor;Ljava/net/InetSocketAddress;)V"),
                new OpenSocketInterceptor("<init>", "(Ljava/nio/channels/spi/SelectorProvider;)V"),
                new CloseInterceptor("kill")));
        spec.add(new ClassTransformSpec(
                "sun/nio/ch/FileChannelImpl",
                new ReturnFromStaticMethodInterceptor(
                        "open",
                        "(Ljava/io/FileDescriptor;Ljava/lang/String;ZZZLjava/io/Closeable;)Ljava/nio/channels/FileChannel;",
                        4,
                        "openFileString",
                        Object.class,
                        FileDescriptor.class,
                        String.class
                )
        ));
        return spec;
    }

    /**
     * Creates {@link ClassTransformSpec} that intercepts
     * a constructor and the close method.
     */
    private static ClassTransformSpec newSpec(final Class<?> c, String constructorDesc) {
        final String binName = c.getName().replace('.', '/');
        return new ClassTransformSpec(binName,
            new ConstructorOpenInterceptor(constructorDesc, binName),
            new CloseInterceptor("close")
        );
    }

    /**
     * Intercepts a void method used to close a handle and calls {@link Listener#close(Object)} in the end.
     */
    private static class CloseInterceptor extends MethodAppender {
        public CloseInterceptor(String methodName) {
            super(methodName, "()V");
        }

        @Override
        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class,"close",
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }

    /**
     * Intercepts a constructor invocation and calls the given method on {@link Listener} at the end of the constructor.
     */
    private static class ConstructorInterceptor extends MethodAppender {
        private final String listenerMethod;

        public ConstructorInterceptor(String constructorDesc, String listenerMethod) {
            super("<init>", constructorDesc);
            this.listenerMethod = listenerMethod;
        }

        @Override
        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class, listenerMethod,
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }

    private static class OpenSocketInterceptor extends MethodAppender {
        public OpenSocketInterceptor(String name, String desc) {
            super(name, desc);
        }

        @Override
        public MethodVisitor newAdapter(MethodVisitor base, int access, String name, String desc, String signature, String[] exceptions) {
            final MethodVisitor b = super.newAdapter(base, access, name, desc, signature, exceptions);
            return new OpenInterceptionAdapter(b,access,desc) {
                @Override
                protected boolean toIntercept(String owner, String name) {
                    return name.equals("socketCreate");
                }
            };
        }

        @Override
        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class,"openSocket",
                    new Class[]{Object.class},
                    new int[]{0});
        }
    }

    /**
     * Used to intercept {@link java.net.PlainSocketImpl#accept(SocketImpl)}
     */
    @SuppressWarnings("JavadocReference")
    private static class AcceptInterceptor extends MethodAppender {
        public AcceptInterceptor(String name, String desc) {
            super(name, desc);
        }

        @Override
        public MethodVisitor newAdapter(MethodVisitor base, int access, String name, String desc, String signature, String[] exceptions) {
            final MethodVisitor b = super.newAdapter(base, access, name, desc, signature, exceptions);
            return new OpenInterceptionAdapter(b,access,desc) {
                @Override
                protected boolean toIntercept(String owner, String name) {
                    return name.equals("socketAccept");
                }
            };
        }

        @Override
        protected void append(CodeGenerator g) {
            // the 's' parameter is the new socket that will own the socket
            g.invokeAppStatic(Listener.class,"openSocket",
                    new Class[]{Object.class},
                    new int[]{1});
        }
    }

    /**
     * Rewrites a method that includes a call to a native method that actually opens a file descriptor
     * (therefore it can throw "too many open files" exception.)
     *
     * surround the call with try/catch, and if "too many open files" exception is thrown
     * call {@link Listener#outOfDescriptors()}.
     */
    private abstract static class OpenInterceptionAdapter extends MethodVisitor {
        private final LocalVariablesSorter lvs;
        private final MethodVisitor base;

        private OpenInterceptionAdapter(MethodVisitor base, int access, String desc) {
            super(Opcodes.ASM9);
            lvs = new LocalVariablesSorter(access, desc, base);
            mv = lvs;
            this.base = base;
        }

        /**
         * Decide if this is the method that needs interception.
         */
        protected abstract boolean toIntercept(String owner, String name);

        protected Class<? extends Exception> getExpectedException() {
            return IOException.class;
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
            if(toIntercept(owner,name)) {
                Type exceptionType = Type.getType(getExpectedException());

                CodeGenerator g = new CodeGenerator(mv);
                Label s = new Label(); // start of the try block
                Label e = new Label();  // end of the try block
                Label h = new Label();  // handler entry point
                Label tail = new Label();   // where the execution continue

                g.visitTryCatchBlock(s, e, h, exceptionType.getInternalName());
                g.visitLabel(s);
                super.visitMethodInsn(opcode, owner, name, desc, itf);
                g._goto(tail);

                g.visitLabel(e);
                g.visitLabel(h);
                // [RESULT]
                // catch(E ex) {
                //    boolean b = ex.getMessage().contains("Too many open files");
                int ex = lvs.newLocal(exceptionType);
                g.dup();
                base.visitVarInsn(Opcodes.ASTORE, ex);
                g.invokeVirtual(exceptionType.getInternalName(),"getMessage","()Ljava/lang/String;");
                g.ldc("Too many open files");
                g.invokeVirtual("java/lang/String", "contains", "(Ljava/lang/CharSequence;)Z");

                // too many open files detected
                //    if (b) { Listener.outOfDescriptors() }
                Label rethrow = new Label();
                g.ifFalse(rethrow);

                g.invokeAppStatic(Listener.class,"outOfDescriptors",
                        new Class[0], new int[0]);

                // rethrow the FileNotFoundException
                g.visitLabel(rethrow);
                base.visitVarInsn(Opcodes.ALOAD, ex);
                g.athrow();

                // normal execution continues here
                g.visitLabel(tail);
            } else {
                // no processing
                super.visitMethodInsn(opcode, owner, name, desc, itf);
            }
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

        @Override
        public MethodVisitor newAdapter(MethodVisitor base, int access, String name, String desc, String signature, String[] exceptions) {
            final MethodVisitor b = super.newAdapter(base, access, name, desc, signature, exceptions);
            return new OpenInterceptionAdapter(b,access,desc) {
                @Override
                protected boolean toIntercept(String owner, String name) {
                    return owner.equals(binName) && name.startsWith("open");
                }

                @Override
                protected Class<? extends Exception> getExpectedException() {
                    return FileNotFoundException.class;
                }
            };
        }

        @Override
        protected void append(CodeGenerator g) {
            g.invokeAppStatic(Listener.class,"open",
                    new Class[]{Object.class, File.class},
                    new int[]{0,1});
        }
    }

    private static class ReturnFromStaticMethodInterceptor extends MethodAppender {
        private final String listenerMethod;
        private final Class<?>[] listenerMethodArgs;
        private final int returnLocalVarIndex;
        public ReturnFromStaticMethodInterceptor(String methodName, String methodDesc, int returnLocalVarIndex,
                String listenerMethod, Class<?> ... listenerMethodArgs) {
            super(methodName, methodDesc);
            this.returnLocalVarIndex = returnLocalVarIndex;
            this.listenerMethod = listenerMethod;
            if (listenerMethodArgs.length == 0) {
                this.listenerMethodArgs = new Class[] { Object.class };
            } else {
                this.listenerMethodArgs = listenerMethodArgs;
            }
        }

        @Override
        protected void append(CodeGenerator g) {
            int[] index = new int[listenerMethodArgs.length];
            // first parameter is from the additional local variable, that holds
            // the return value of the intercepted method
            index[0] = returnLocalVarIndex;
            // remaining parameters
            for (int i = 1; i < index.length; i++) {
                index[i] = i - 1;
            }

            Label start = new Label();
            Label end = new Label();
            g.visitLocalVariable("result", "java/lang/Object", null, start, end, returnLocalVarIndex);
            g.visitLabel(start);

            // return value is currently on top of the stack
            // result = {return value}
            g.astore(returnLocalVarIndex);

            g.invokeAppStatic(Listener.class, listenerMethod, listenerMethodArgs, index);

            g.visitLabel(end);

            // restore the stack so that the ARETURN has something to return
            g.aload(returnLocalVarIndex);
        }
    }
}
