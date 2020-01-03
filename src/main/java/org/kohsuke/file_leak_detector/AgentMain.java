package org.kohsuke.file_leak_detector;

import static org.kohsuke.asm6.Opcodes.ALOAD;
import static org.kohsuke.asm6.Opcodes.ASM5;
import static org.kohsuke.asm6.Opcodes.ASTORE;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.zip.ZipFile;

import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.asm6.Label;
import org.kohsuke.asm6.MethodVisitor;
import org.kohsuke.asm6.Type;
import org.kohsuke.asm6.commons.LocalVariablesSorter;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detector.transform.CodeGenerator;
import org.kohsuke.file_leak_detector.transform.MethodAppender;
import org.kohsuke.file_leak_detector.transform.TransformerImpl;

/**
 * Java agent that instruments JDK classes to keep track of where file descriptors are opened.
 * @author Kohsuke Kawaguchi
 */
@SuppressWarnings("Since15")
public class AgentMain {
    public static void agentmain(String agentArguments, Instrumentation instrumentation) throws Exception {
        premain(agentArguments,instrumentation);
    }

    public static void premain(String agentArguments, Instrumentation instrumentation) throws Exception {
        int serverPort = -1;

        if(agentArguments!=null) {
            // used by Main to prevent the termination of target JVM
            boolean quit = true;

            for (String t : agentArguments.split(",")) {
                if(t.equals("noexit")) {
                    quit = false;
                } else
                if(t.equals("help")) {
                    usage();
                    if (quit)   System.exit(-1);
                    else        return;
                } else
                if(t.startsWith("threshold=")) {
                    Listener.THRESHOLD = Integer.parseInt(t.substring(t.indexOf('=')+1));
                } else
                if(t.equals("trace")) {
                    Listener.TRACE = new PrintWriter(System.err);
                } else
                if(t.equals("strong")) {
                    Listener.makeStrong();
                } else
                if(t.startsWith("http=")) {
                    serverPort = Integer.parseInt(t.substring(t.indexOf('=')+1));
                } else
                if(t.startsWith("trace=")) {
                    Listener.TRACE = new PrintWriter(new FileOutputStream(t.substring(6)));
                } else
                if(t.startsWith("error=")) {
                    Listener.ERROR = new PrintWriter(new FileOutputStream(t.substring(6)));
                } else
                if(t.startsWith("listener=")) {
                    ActivityListener.LIST.add((ActivityListener) AgentMain.class.getClassLoader().loadClass(t.substring(9)).newInstance());
                } else
                if(t.equals("dumpatshutdown")) {
                    Runtime.getRuntime().addShutdownHook(new Thread("File handles dumping shutdown hook") {
                        @Override
                        public void run() {
                            Listener.dump(System.err);
                        }
                    });
                } else
                if(t.startsWith("excludes=")) {
                    BufferedReader reader = new BufferedReader(new FileReader(t.substring(9)));
                    try {
	                    while (true) {
	                    	String line = reader.readLine();
	                    	if(line == null) {
	                    		break;
	                    	}

	                    	String str = line.trim();
	                        // add the entries from the excludes-file, but filter out empty ones and comments
	                    	if(!str.isEmpty() && !str.startsWith("#")) {
	                    		Listener.EXCLUDES.add(str);
	                    	}
	                    }
                    } finally {
                    	reader.close();
                    }
                } else {
                    System.err.println("Unknown option: "+t);
                    usage();
                    if (quit)       System.exit(-1);
                    throw new CmdLineException("Unknown option: "+t);
                }
            }
        }

        Listener.EXCLUDES.add("sun.nio.ch.PipeImpl$Initializer$LoopbackConnector.run");
        System.err.println("File leak detector installed");

        // Make sure the ActivityListener is loaded to prevent recursive death in instrumentation
        ActivityListener.LIST.size();

        Listener.AGENT_INSTALLED = true;
        instrumentation.addTransformer(new TransformerImpl(createSpec()),true);

        List<Class> classes = Arrays.asList(new Class[] {
                FileInputStream.class,
                FileOutputStream.class,
                RandomAccessFile.class,
                Class.forName("java.net.PlainSocketImpl"),
                ZipFile.class,
                AbstractSelectableChannel.class,
                AbstractInterruptibleChannel.class,
                FileChannel.class,
                AbstractSelector.class,
                Files.class});

        addIfFound(classes, "sun/nio/ch/SocketChannelImpl");
        addIfFound(classes, "java/net/AbstractPlainSocketImpl");

        instrumentation.retransformClasses(classes.toArray(new Class[0]));


//                Socket.class,
//                SocketChannel.class,
//                AbstractInterruptibleChannel.class,
//                ServerSocket.class);

        if (serverPort>=0)
            runHttpServer(serverPort);
    }

    private static void addIfFound(List<Class> classes, String className) {
        try {
            classes.add(Class.forName(className));
        } catch(ClassNotFoundException e) {
            // ignored here
        }
    }

    private static void runHttpServer(int port) throws IOException {
        final ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress("localhost", port));
        System.err.println("Serving file leak stats on http://localhost:"+ss.getLocalPort()+"/ for stats");
        final ExecutorService es = Executors.newCachedThreadPool(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r);
                t.setDaemon(true);
                return t;
            }
        });
        es.submit(new Callable<Object>() {
            public Object call() throws Exception {
                while (true) {
                    final Socket s = ss.accept();
                    es.submit(new Callable<Void>() {
                        public Void call() throws Exception {
                            try {
                                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream()));
                                // Read the request line (and ignore it)
                                in.readLine();

                                PrintWriter w = new PrintWriter(new OutputStreamWriter(s.getOutputStream(),"UTF-8"));
                                w.print("HTTP/1.0 200 OK\r\nContent-Type: text/plain;charset=UTF-8\r\n\r\n");
                                Listener.dump(w);
                            } finally {
                                s.close();
                            }
                            return null;
                        }
                    });
                }
            }
        });
    }

    private static void usage() {
        System.err.println("File leak detector arguments (to specify multiple values, separate them by ',':");
        printOptions();
    }

    static void printOptions() {
        System.err.println("  help          - show the help screen.");
        System.err.println("  trace         - log every open/close operation to stderr.");
        System.err.println("  trace=FILE    - log every open/close operation to the given file.");
        System.err.println("  error=FILE    - if 'too many open files' error is detected, send the dump here.");
        System.err.println("                  by default it goes to stderr.");
        System.err.println("  threshold=N   - instead of waiting until 'too many open files', dump once");
        System.err.println("                  we have N descriptors open.");
        System.err.println("  http=PORT     - Run a mini HTTP server that you can access to get stats on demand");
        System.err.println("                  Specify 0 to choose random available port, -1 to disable, which is default.");
        System.err.println("  strong        - Don't let GC auto-close leaking file descriptors");
        System.err.println("  listener=S    - Specify the fully qualified name of ActivityListener class to activate from beginning");
        System.err.println("  dumpatshutdown- Dump open file handles at shutdown");
        System.err.println("  excludes=FILE - Ignore files opened directly/indirectly in specific methods.");
        System.err.println("                  File lists 'some.pkg.ClassName.methodName' patterns.");
    }

    static List<ClassTransformSpec> createSpec() {
        return Arrays.asList(
            newSpec(FileOutputStream.class, "(Ljava/io/File;Z)V"),
            newSpec(FileInputStream.class, "(Ljava/io/File;)V"),
            newSpec(RandomAccessFile.class, "(Ljava/io/File;Ljava/lang/String;)V"),
            newSpec(ZipFile.class, "(Ljava/io/File;I)V"),

            /*
             * Detect the files opened via FileChannel.open(...) calls
             */
            new ClassTransformSpec(FileChannel.class,
                    new ReturnFromStaticMethodInterceptor("open",
                            "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/FileChannel;", 4, "open_filechannel", FileChannel.class, Path.class)),
            /*
            SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs)
             */
            new ClassTransformSpec(Files.class,
                    new ReturnFromStaticMethodInterceptor("newByteChannel",
                            "(Ljava/nio/file/Path;Ljava/util/Set;[Ljava/nio/file/attribute/FileAttribute;)Ljava/nio/channels/SeekableByteChannel;", 4, "open_filechannel", SeekableByteChannel.class, Path.class)),
            /*
             * Detect new Pipes
             */
            new ClassTransformSpec(AbstractSelectableChannel.class,
                    new ConstructorInterceptor("(Ljava/nio/channels/spi/SelectorProvider;)V", "openPipe")),
            /*
             * AbstractInterruptibleChannel is used by FileChannel and Pipes
             */
            new ClassTransformSpec(AbstractInterruptibleChannel.class,
                    new CloseInterceptor("close")),

            /**
             * Detect selectors, which may open native pipes and anonymous inodes for event polling.
             */
            new ClassTransformSpec(AbstractSelector.class,
                    new ConstructorInterceptor("(Ljava/nio/channels/spi/SelectorProvider;)V", "openSelector"),
                    new CloseInterceptor("close")),

            /*
                java.net.Socket/ServerSocket uses SocketImpl, and this is where FileDescriptors
                are actually managed.

                SocketInputStream/SocketOutputStream does not maintain a separate FileDescriptor.
                They just all piggy back on the same SocketImpl instance.
             */
            new ClassTransformSpec("java/net/PlainSocketImpl",
                    // this is where a new file descriptor is allocated.
                    // it'll occupy a socket even before it gets connected
                    new OpenSocketInterceptor("create", "(Z)V"),

                    // When a socket is accepted, it goes to "accept(SocketImpl s)"
                    // where 's' is the new socket and 'this' is the server socket
                    new AcceptInterceptor("accept","(Ljava/net/SocketImpl;)V"),

                    // file descriptor actually get closed in socketClose()
                    // socketPreClose() appears to do something similar, but if you read the source code
                    // of the native socketClose0() method, then you see that it actually doesn't close
                    // a file descriptor.
                    new CloseInterceptor("socketClose")
            ),
            // Later versions of the JDK abstracted out the parts of PlainSocketImpl above into a super class
            new ClassTransformSpec("java/net/AbstractPlainSocketImpl",
                new OpenSocketInterceptor("create", "(Z)V"),
                new AcceptInterceptor("accept","(Ljava/net/SocketImpl;)V"),
                new CloseInterceptor("socketClose")
            ),
            new ClassTransformSpec("sun/nio/ch/SocketChannelImpl",
                    new OpenSocketInterceptor("<init>", "(Ljava/nio/channels/spi/SelectorProvider;Ljava/io/FileDescriptor;Ljava/net/InetSocketAddress;)V"),
                    new OpenSocketInterceptor("<init>", "(Ljava/nio/channels/spi/SelectorProvider;)V"),
                    new CloseInterceptor("kill")
            )
        );
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
            super(name,desc);
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
            super(name,desc);
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
    private static abstract class OpenInterceptionAdapter extends MethodVisitor {
        private final LocalVariablesSorter lvs;
        private final MethodVisitor base;
        private OpenInterceptionAdapter(MethodVisitor base, int access, String desc) {
            super(ASM5);
            lvs = new LocalVariablesSorter(access,desc, base);
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
                base.visitVarInsn(ASTORE, ex);
                g.invokeVirtual(exceptionType.getInternalName(),"getMessage","()Ljava/lang/String;");
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
                base.visitVarInsn(ALOAD, ex);
                g.athrow();

                // normal execution continues here
                g.visitLabel(tail);
            } else
                // no processing
                super.visitMethodInsn(opcode, owner, name, desc, itf);
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
