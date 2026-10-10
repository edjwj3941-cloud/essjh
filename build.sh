#!/usr/bin/env bash
# Builds LastSpark.jar into ./target/. Needs JDK 21+ and Maven.
set -e
cd "$(dirname "$0")"
mvn -q clean package
echo "Done: target/LastSpark.jar -> copy it into your server's plugins/ folder."
