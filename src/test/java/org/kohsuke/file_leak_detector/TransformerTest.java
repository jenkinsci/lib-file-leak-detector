package org.kohsuke.file_leak_detector;

import org.apache.commons.io.IOUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.kohsuke.asm6.ClassReader;
import org.kohsuke.asm6.util.CheckClassAdapter;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detector.transform.TransformerImpl;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * @author Kohsuke Kawaguchi
 */
@RunWith(Parameterized.class)
public class TransformerTest {
    private final static List<ClassTransformSpec> specs = AgentMain.createSpec();

    private final Class<?> c;

    public TransformerTest(Class<?> c) {
        this.c = c;
    }

    @Test
    public void testInstrumentations() throws Exception {
        TransformerImpl t = new TransformerImpl(specs);

        String name = c.getName().replace('.', '/');
        InputStream resource = getClass().getClassLoader().getResourceAsStream(name + ".class");
        assertNotNull("Could not load " + name + ".class", resource);
        byte[] data = IOUtils.toByteArray(resource);
        byte[] data2 = t.transform(name,data);

//        File classFile = new File("/tmp/" + name + ".class");
//        classFile.getParentFile().mkdirs();
//        FileOutputStream o = new FileOutputStream(classFile);
//        o.write(data2);
//        o.close();

        final String errors;
        ClassReader classReader = new ClassReader(data2);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            CheckClassAdapter.verify(classReader, false, new PrintWriter(baos));
            errors = new String(baos.toByteArray(), UTF_8);
        }
        assertTrue("Verification failed for " + c + "\n" + errors, errors.isEmpty());
    }

    @Parameters(name = "{index} - {0}")
    public static List<Object[]> specs() throws Exception {
        List<Object[]> r = new ArrayList<Object[]>();
        for (ClassTransformSpec s : specs) {
            Class<?> c = TransformerTest.class.getClassLoader().loadClass(s.name.replace('/', '.'));
            r.add(new Object[]{c});
        }
        return r;
    }
}
