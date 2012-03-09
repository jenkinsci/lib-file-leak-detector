package org.kohsuke.file_leak_detector;

class Bar {
    void foo() {
        bar();
        try {
            bar();
        } catch(IllegalArgumentException e) {
            bar();
        }
        bar();
    } 
    
    void bar() {}
}