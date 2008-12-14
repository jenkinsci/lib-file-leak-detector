package org.kohsuke.file_leak_detecter.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.ClassAdapter;
import org.objectweb.asm.MethodVisitor;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.HashMap;

/**
 * @author Kohsuke Kawaguchi
 */
public class TransformerImpl implements ClassFileTransformer {

    private final Map<String,ClassTransformSpec> specs = new HashMap<String,ClassTransformSpec>();

    public TransformerImpl(ClassTransformSpec... specs) {
        for (ClassTransformSpec spec : specs)
            this.specs.put(spec.name,spec);
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {

        final ClassTransformSpec cs = specs.get(className);
        if(cs==null)
            return classfileBuffer;

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(0);
        cr.accept(new ClassAdapter(cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);

                MethodTransformSpec ms = cs.methodSpecs.get(name + desc);
                if(ms==null)    ms = cs.methodSpecs.get(name+"*");
                if(ms==null)    return base;

                return ms.newAdapter(base);
            }
        },0);

        return cw.toByteArray();
    }
}
