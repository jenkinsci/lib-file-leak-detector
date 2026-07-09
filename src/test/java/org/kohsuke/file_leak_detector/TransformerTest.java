package org.kohsuke.file_leak_detector;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.kohsuke.file_leak_detector.transform.ClassTransformSpec;
import org.kohsuke.file_leak_detector.transform.TransformerImpl;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

/**
 * @author Kohsuke Kawaguchi
 */
public class TransformerTest {
    private static final List<ClassTransformSpec> specs = AgentMain.createSpec();

    @MethodSource("specs")
    @ParameterizedTest(name = "{index} - {0}")
    public void testInstrumentations(Class<?> c) throws Exception {
        TransformerImpl t = new TransformerImpl(specs);

        String name = c.getName().replace('.', '/');
        byte[] data;
        try (InputStream resource = getClass().getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(resource, "Could not load " + name + ".class");
            data = resource.readAllBytes();
        }
        byte[] data2 = t.transform(name, data);

        //        File classFile = new File("/tmp/" + name + ".class");
        //        classFile.getParentFile().mkdirs();
        //        FileOutputStream o = new FileOutputStream(classFile);
        //        o.write(data2);
        //        o.close();

        final String errors;
        ClassReader classReader = new ClassReader(data2);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
                OutputStreamWriter osw = new OutputStreamWriter(baos, StandardCharsets.UTF_8);
                PrintWriter pw = new PrintWriter(osw)) {
            CheckClassAdapter.verify(classReader, false, pw);
            errors = baos.toString(StandardCharsets.UTF_8);
        }
        assertTrue(errors.isEmpty(), "Verification failed for " + c + "\n" + errors);
    }

    public static List<Object[]> specs() throws Exception {
        List<Object[]> r = new ArrayList<>();
        for (ClassTransformSpec s : specs) {
            Class<?> c = TransformerTest.class.getClassLoader().loadClass(s.name.replace('/', '.'));
            r.add(new Object[] {c});
        }
        return r;
    }
}
