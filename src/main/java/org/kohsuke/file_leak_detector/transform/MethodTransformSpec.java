package org.kohsuke.file_leak_detecter.transform;

import org.objectweb.asm.MethodVisitor;

/**
 * @author Kohsuke Kawaguchi
 */
public abstract class MethodTransformSpec {
    public final String name;
    public final String desc;

    public MethodTransformSpec(String name, String desc) {
        this.name = name;
        this.desc = desc;
    }

    public abstract MethodVisitor newAdapter(MethodVisitor base);
}
