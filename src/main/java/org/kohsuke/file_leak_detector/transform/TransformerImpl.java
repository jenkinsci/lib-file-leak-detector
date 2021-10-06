package org.kohsuke.file_leak_detector.transform;

import org.kohsuke.asm6.ClassReader;
import org.kohsuke.asm6.ClassVisitor;
import org.kohsuke.asm6.ClassWriter;
import org.kohsuke.asm6.MethodVisitor;
import org.kohsuke.file_leak_detector.AgentMain;
import org.kohsuke.file_leak_detector.Listener;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.security.ProtectionDomain;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.kohsuke.asm6.ClassReader.*;
import static org.kohsuke.asm6.Opcodes.*;

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
        //System.out.println("Loading " + className);

        ClassTransformSpec csa = specs.get(className);

        if(csa ==null) {
            /*if (className.contains("DirectoryStream")) {
                //classfileBuffer = transformInterfaces(className, classfileBuffer);
                return transformBytes(className, classfileBuffer,
                        new ClassTransformSpec(className,
                            new CloseInterceptor("close")));
            }*/

            return classfileBuffer;
        }

        return transformBytes(className, classfileBuffer, csa);
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

    /*public byte[] transformInterfaces(String className, byte[] classfileBuffer) {
        // try to look if this class implements an interface that is transformed
        try {
            Class<?> aClass = Class.forName(className.replace("/", "."));
            for (Class<?> anInterface : aClass.getInterfaces()) {
                String interfaceName = anInterface.getName().replace(".", "/");
                System.out.println("Looking at " + className + ": " + interfaceName);
                ClassTransformSpec classTransformSpecInterface = specs.get(interfaceName);
                if (classTransformSpecInterface != null) {
                    System.out.println("Found interface-spec for " + className + ": " + interfaceName);
                    return transformBytes(className, classfileBuffer, classTransformSpecInterface);
                }
            }
        } catch (ClassNotFoundException e) {
            System.out.println("Failed to load class " + className);
            e.printStackTrace();
        }

        return classfileBuffer;
    }*/

    protected byte[] transformBytes(String className, byte[] classfileBuffer, ClassTransformSpec cs) {
        ClassReader cr = new ClassReader(classfileBuffer);
        ClassWriter cw = new ClassWriter(/*ClassWriter.COMPUTE_FRAMES|*/ClassWriter.COMPUTE_MAXS);

        cr.accept(new ClassVisitor(ASM6,cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor base = super.visitMethod(access, name, desc, signature, exceptions);

                MethodTransformSpec ms = cs.methodSpecs.get(name + desc);
                if(ms==null)    ms = cs.methodSpecs.get(name+"*");
                if(ms==null)    return base;

                //System.out.println("Transforming method " + name + " of " + cs.name);
                return ms.newAdapter(base,access,name,desc,signature,exceptions);
            }
        }, SKIP_FRAMES);

        //System.out.println("Transformed "+ className);
        return cw.toByteArray();
    }
}
