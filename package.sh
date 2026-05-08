#!/bin/bash
#
# Packages Uptime Bar as a native macOS .app bundle using jpackage (JDK 21+).
# Output: dist/UptimeBar.app
#
set -e

APP_NAME="UptimeBar"
APP_VERSION="1.0.0"
MAIN_CLASS="com.uptimebar.UptimeBar"
JAR_NAME="uptime-bar-${APP_VERSION}.jar"
DIST_DIR="dist"

echo "==> Building JAR..."
mvn clean package -q

echo "==> Cleaning previous builds..."
rm -rf "${DIST_DIR}"

echo "==> Packaging native macOS app with jpackage..."
jpackage \
    --type app-image \
    --name "${APP_NAME}" \
    --app-version "${APP_VERSION}" \
    --input target \
    --main-jar "${JAR_NAME}" \
    --main-class "${MAIN_CLASS}" \
    --dest "${DIST_DIR}" \
    --mac-package-name "${APP_NAME}" \
    --icon src/main/resources/AppIcon.icns \
    --java-options "-Dapple.awt.enableTemplateImages=true" \
    --java-options "-Dapple.awt.UIElement=true"

# Add LSUIElement to Info.plist so the app doesn't appear in the Dock
PLIST="${DIST_DIR}/${APP_NAME}.app/Contents/Info.plist"
if [ -f "${PLIST}" ]; then
    echo "==> Setting app as menu-bar-only agent (no Dock icon)..."
    /usr/libexec/PlistBuddy -c "Add :LSUIElement bool true" "${PLIST}" 2>/dev/null || \
    /usr/libexec/PlistBuddy -c "Set :LSUIElement true" "${PLIST}"
fi

echo ""
echo "✅ Done! App bundle created at:"
echo "   ${DIST_DIR}/${APP_NAME}.app"
echo ""
echo "To run:  open ${DIST_DIR}/${APP_NAME}.app"
echo "To install: cp -r ${DIST_DIR}/${APP_NAME}.app /Applications/"
