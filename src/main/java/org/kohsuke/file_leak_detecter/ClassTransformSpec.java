package org.kohsuke.file_leak_detecter;

import java.util.Map;
import java.util.HashMap;

/**
 * @author Kohsuke Kawaguchi
 */
public final class ClassTransformSpec {
    public final String name;
    /*package*/ Map<String,MethodTransformSpec> methodSpecs = new HashMap<String,MethodTransformSpec>();

    public ClassTransformSpec(String name, MethodTransformSpec... methodSpecs) {
        this.name = name;
        for (MethodTransformSpec s : methodSpecs)
            this.methodSpecs.put(s.name+s.desc,s);
    }
}
