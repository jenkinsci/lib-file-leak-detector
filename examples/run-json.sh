#!/bin/bash
# Run LeakyApp with the file-leak-detector agent in JSON dump mode.
# After 10 seconds, sends SIGTERM to trigger the dumpatshutdown hook.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AGENT_JAR="$PROJECT_DIR/target/file-leak-detector-1.20-SNAPSHOT-jar-with-dependencies.jar"

if [ ! -f "$AGENT_JAR" ]; then
    echo "Agent jar not found. Run ./build.sh first."
    exit 1
fi

if [ ! -f "$SCRIPT_DIR/LeakyApp.class" ]; then
    echo "LeakyApp.class not found. Run ./build.sh first."
    exit 1
fi

echo "Running LeakyApp with file-leak-detector agent (JSON mode)..."
echo "Agent options: json,dumpatshutdown,dumpinterval=1"
echo "Will send SIGTERM after 10 seconds."
echo "---"

java \
    -javaagent:"$AGENT_JAR=json,dumpatshutdown,dumpinterval=1" \
    -cp "$SCRIPT_DIR" \
    LeakyApp &

JAVA_PID=$!

sleep 10
echo "--- Sending SIGTERM to LeakyApp (pid $JAVA_PID) ---"
kill "$JAVA_PID"
wait "$JAVA_PID" 2>/dev/null || true
