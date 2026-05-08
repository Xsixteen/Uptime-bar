#!/bin/bash
#
# Generates the macOS app icon (AppIcon.icns) from the Java IconGenerator.
# Requires: Java 21+, Maven, iconutil (built into macOS)
#
set -e

ICONSET_DIR="src/main/resources/AppIcon.iconset"
ICNS_FILE="src/main/resources/AppIcon.icns"

echo "==> Compiling icon generator..."
mvn compile -q

echo "==> Generating icon sizes..."
rm -rf "${ICONSET_DIR}"
java -cp target/classes com.uptimebar.IconGenerator "${ICONSET_DIR}"

echo "==> Converting to .icns..."
iconutil -c icns "${ICONSET_DIR}" -o "${ICNS_FILE}"
rm -rf "${ICONSET_DIR}"

echo ""
echo "✅ Icon created: ${ICNS_FILE}"
