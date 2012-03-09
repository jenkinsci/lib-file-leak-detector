package org.kohsuke.file_leak_detector.transform;

import org.kohsuke.asm3.MethodVisitor;

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
