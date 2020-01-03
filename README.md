A Java agent which detects file handle leaks.
 
See http://file-leak-detector.kohsuke.org/ for usage description

# Development

## Implementation details

This project uses the JVM support for instrumenting Java classes during startup.

It adds code to various places where files or sockets are opened and closed to 
print out which file handles have not been closed correctly.

## How to build

    mvn package

The resulting package will be at `target/file-leak-detector-1.<version>-SNAPSHOT-jar-with-dependencies.jar` 

## How to run integration tests

    mvn verify

This will run tests in the `org.kohsuke.file_leak_detector.instrumented` package which are 
executed with instrumentation via the Java agent being active.

