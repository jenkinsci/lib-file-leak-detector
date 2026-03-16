#!/bin/bash
# Build the file-leak-detector agent jar and compile the example app.
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
AGENT_JAR="$PROJECT_DIR/target/file-leak-detector-1.20-SNAPSHOT-jar-with-dependencies.jar"

# Build the agent if not already built
if [ ! -f "$AGENT_JAR" ]; then
    echo "Building file-leak-detector agent..."
    (cd "$PROJECT_DIR" && mvn -DskipTests package -q)
fi

echo "Compiling LeakyApp.java..."
javac "$SCRIPT_DIR/LeakyApp.java"
echo "Done. Run with: ./run.sh"
