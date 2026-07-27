#!/bin/bash
# MyTix launcher: compiles src/ and runs Main against config.properties.
# Usage: ./run.sh
set -e

MYSQL_JAR="src/lib/mysql-connector-java-8.0.29.jar"
BUILD_DIR="build"

if [ ! -f "$MYSQL_JAR" ]; then
  echo "Missing $MYSQL_JAR"
  echo "Download MySQL Connector/J (mysql-connector-j) and place the .jar in src/lib/."
  exit 1
fi

mkdir -p "$BUILD_DIR"
javac -cp "$MYSQL_JAR" -d "$BUILD_DIR" $(find src -name "*.java")

java -cp "$BUILD_DIR:$MYSQL_JAR" Main config.properties
