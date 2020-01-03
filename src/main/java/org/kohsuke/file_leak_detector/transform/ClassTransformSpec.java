package org.kohsuke.file_leak_detector.transform;

import java.util.Map;
import java.util.HashMap;

/**
 * Specifies how we transform a class.
 *
 * @author Kohsuke Kawaguchi
 */
public final class ClassTransformSpec {
    public final String name;
    /*package*/ Map<String,MethodTransformSpec> methodSpecs = new HashMap<String,MethodTransformSpec>();

    public ClassTransformSpec(Class<?> clazz, MethodTransformSpec... methodSpecs) {
        this(clazz.getName().replace('.', '/'),methodSpecs);
    }

    public ClassTransformSpec(String name, MethodTransformSpec... methodSpecs) {
        this.name = name;
        for (MethodTransformSpec s : methodSpecs)
            this.methodSpecs.put(s.name+s.desc,s);
    }
}
