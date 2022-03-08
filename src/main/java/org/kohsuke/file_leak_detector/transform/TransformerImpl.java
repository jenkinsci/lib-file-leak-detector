package org.kohsuke.file_leak_detector.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.objectweb.asm.ClassReader.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * @author Kohsuke Kawaguchi
 */
public class TransformerImpl implements ClassFileTransformer {

    private final Map<String,ClassTransformSpec> specs = new HashMap<String,ClassTransformSpec>();

    public TransformerImpl(Collection<ClassTransformSpec> specs) {
        for (ClassTransformSpec spec : specs)
            this.specs.put(spec.name,spec);
    }

    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
        return transform(className,classfileBuffer);
    }
    
    public byte[] transform(String className, byte[] classfileBuffer) {
        final ClassTransformSpec cs = specs.get(className);
        if(cs==null)
            return classfileBuffer;

        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES|ClassWriter.COMPUTE_MAXS);
        cr.accept(new ClassVisitor(ASM9,cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);

                MethodTransformSpec ms = cs.methodSpecs.get(name + desc);
                if(ms==null)    ms = cs.methodSpecs.get(name+"*");
                if(ms==null)    return base;

                return ms.newAdapter(base,access,name,desc,signature,exceptions);
            }
        }, SKIP_FRAMES);

//        System.out.println("Transforming "+className);
        return cw.toByteArray();
    }
}
