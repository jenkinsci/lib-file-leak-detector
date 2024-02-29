package org.kohsuke.file_leak_detector;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Test;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.mockito.stubbing.Answer;

public class AgentMainTest {
    @Test
    public void noDuplicateSpecs() {
        final List<ClassTransformSpec> specs = AgentMain.createSpec();

        Set<String> seenClasses = new HashSet<>();
        for (ClassTransformSpec spec : specs) {
            assertTrue("Did have duplicate spec for class " + spec.name, seenClasses.add(spec.name));
        }
    }

    @Test
    public void testPreMain() throws Exception {
        final Set<String> seenClasses = new HashSet<>();
        final Instrumentation instrumentation = prepare(seenClasses);

        AgentMain.premain(null, instrumentation);

        verifyInstrumentation(instrumentation, seenClasses);
    }

    @Test
    public void testPreMainHttpServer() throws Exception {
        final Set<String> seenClasses = new HashSet<>();
        final Instrumentation instrumentation = prepare(seenClasses);

        AgentMain.premain("http=0", instrumentation);

        verifyInstrumentation(instrumentation, seenClasses);
    }

    @Test
    public void testPreMainHttpServerInvalidPort() throws Exception {
        final Set<String> seenClasses = new HashSet<>();
        final Instrumentation instrumentation = prepare(seenClasses);

        assertThrows(IllegalArgumentException.class, () -> AgentMain.premain("http=999999999", instrumentation));

        verifyInstrumentation(instrumentation, seenClasses);
    }

    @Test
    public void testPreMainHttpServerIOException() throws Exception {
        try (final ServerSocket ss = new ServerSocket()) {
            ss.bind(new InetSocketAddress("localhost", 0));

            final Set<String> seenClasses = new HashSet<>();
            final Instrumentation instrumentation = prepare(seenClasses);

            assertThrows(IOException.class, () -> AgentMain.premain("http=" + ss.getLocalPort(), instrumentation));

            verifyInstrumentation(instrumentation, seenClasses);
        }
    }

    private static Instrumentation prepare(Set<String> seenClasses) throws UnmodifiableClassException {
        final List<ClassTransformSpec> specs = AgentMain.createSpec();

        for (ClassTransformSpec spec : specs) {
            seenClasses.add(spec.name);
        }

        Instrumentation instrumentation = mock(Instrumentation.class);
        doAnswer((Answer<Object>) invocationOnMock -> {
                    for (Object obj : invocationOnMock.getArguments()) {
                        Class<?> clazz = (Class<?>) obj;
                        String name = clazz.getName().replace(".", "/");
                        assertTrue(
                                "Tried to transform a class which is not contained in the specs: "
                                        + name
                                        + " ("
                                        + clazz
                                        + "), having remaining classes: "
                                        + seenClasses,
                                seenClasses.remove(name));
                    }
                    return null;
                })
                .when(instrumentation)
                .retransformClasses(any(Class[].class));
        return instrumentation;
    }

    private static void verifyInstrumentation(Instrumentation instrumentation, Set<String> seenClasses)
            throws UnmodifiableClassException {
        verify(instrumentation, times(1)).addTransformer(any(), anyBoolean());
        verify(instrumentation, times(1)).retransformClasses(any(Class[].class));

        // the following are not available in all JVMs
        seenClasses.remove("sun/nio/ch/SocketChannelImpl");
        seenClasses.remove("sun/nio/fs/UnixDirectoryStream");
        seenClasses.remove("sun/nio/fs/UnixSecureDirectoryStream");
        if (Runtime.version().feature() >= 19) {
            seenClasses.remove("java/net/AbstractPlainSocketImpl");
            seenClasses.remove("java/net/PlainSocketImpl");
        }
        seenClasses.remove("sun/nio/fs/UnixDirectoryStream");
        seenClasses.remove("sun/nio/fs/UnixSecureDirectoryStream");

        assertTrue("Had classes in the spec which were not instrumented: " + seenClasses, seenClasses.isEmpty());
    }
}
