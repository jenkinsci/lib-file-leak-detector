package org.kohsuke.file_leak_detector;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.instrument.Instrumentation;
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
        final List<ClassTransformSpec> specs = AgentMain.createSpec();

        final Set<String> seenClasses = new HashSet<>();
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
        }).when(instrumentation).retransformClasses(any(Class[].class));

        AgentMain.premain(null, instrumentation);

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

        assertTrue(
                "Had classes in the spec which were not instrumented: " + seenClasses,
                seenClasses.isEmpty());
    }
}
