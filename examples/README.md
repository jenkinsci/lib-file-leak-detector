# LeakyApp Example

Demonstrates the file-leak-detector Java agent by running a multi-threaded app that intentionally leaks file descriptors.

The app has three threads:
- **file-writer** - opens temp files in `/tmp` and writes random data (never closes them)
- **devnull-writer** - opens `/dev/null` and writes data (never closes)
- **url-reader** - reads from `example.com` / `example.org` (never closes connections)

The agent is configured with `dumpatshutdown` and `dumpinterval=1` (dumps every 1 second).

## Build

```bash
./build.sh
```

This compiles `LeakyApp.java` and builds the agent jar (via Maven) if needed.

## Run

```bash
./run.sh
```

The app runs for 10 seconds. Watch stderr for periodic dumps of open file handles with stack traces showing where they were opened.

### JSON mode

```bash
./run-json.sh
```

Same as above but uses the `json` agent option. Each dump is a single JSON line, suitable for log aggregation (e.g., CloudWatch, ELK). Stack traces appear as arrays of strings.
