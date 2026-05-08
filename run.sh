#!/bin/bash
# Run the Uptime Bar menu bar application
cd "$(dirname "$0")"
mvn -q package -DskipTests 2>/dev/null
java -jar target/uptime-bar-1.0.0.jar
