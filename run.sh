#!/bin/bash
# MyTix launcher: compiles src/ and runs Main against config.properties.
# Usage: ./run.sh
set -e

# First run: create your local config from the template if it isn't there yet.
if [ ! -f config.properties ]; then
  cp config.properties.example config.properties
  echo "Created config.properties from the template; edit it if your MySQL login is different."
fi

MYSQL_JAR="src/lib/mysql-connector-j-8.4.0.jar"
BUILD_DIR="build"

if [ ! -f "$MYSQL_JAR" ]; then
  echo "Missing $MYSQL_JAR"
  echo "Download MySQL Connector/J (mysql-connector-j) and place the .jar in src/lib/."
  exit 1
fi

mkdir -p "$BUILD_DIR"
javac -cp "$MYSQL_JAR" -d "$BUILD_DIR" $(find src -name "*.java")

java -cp "$BUILD_DIR:$MYSQL_JAR" Main config.properties
