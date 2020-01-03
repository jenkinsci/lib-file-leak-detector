package org.kohsuke.file_leak_detector;

import org.junit.Test;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        Mockito.doAnswer(new Answer<Object>() {
            @Override
            public Object answer(InvocationOnMock invocationOnMock) throws Throwable {
                for (Object obj : invocationOnMock.getArguments()) {
                    Class<?> clazz = (Class<?>) obj;
                    String name = clazz.getName().replace(".", "/");
                    assertTrue("Tried to transform a class wihch is not contained in the specs: " + name + " (" + clazz + "), having remaining classes: " + seenClasses,
                            seenClasses.remove(name));
                }
                return null;
            }
        }).when(instrumentation).retransformClasses((Class<?>) any());

        AgentMain.premain(null, instrumentation);

        verify(instrumentation, times(1)).addTransformer((ClassFileTransformer) any(), anyBoolean());
        verify(instrumentation, times(1)).retransformClasses((Class<?>) any());

        // the following two are not available in all JVMs
        seenClasses.remove("sun/nio/ch/SocketChannelImpl");
        seenClasses.remove("java/net/AbstractPlainSocketImpl");

        assertTrue("Had classes in the spec which were not instrumented: " + seenClasses,
                seenClasses.isEmpty());
    }
}
