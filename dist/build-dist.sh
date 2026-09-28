#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# rSMTv2 — Cross-platform distribution builder
# Produces a native installer for the current OS using jpackage.
#
# Prerequisites:
#   - JDK 21+ with jpackage  (java -version && jpackage --version)
#   - Maven 3.9+             (mvn --version)
#   - macOS:   Xcode CLT     (for .dmg production)
#   - Windows: WiX 3.x       (for .msi production, add to PATH)
#   - Linux:   fakeroot      (for .deb/.rpm production)
#
# Usage:
#   ./dist/build-dist.sh            # auto-detect OS, build installer
#   ./dist/build-dist.sh --type dmg # override jpackage type
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="2.0.0"
APP_NAME="rSMTv2"
MAIN_CLASS="com.rsmt.Main"
OUT_DIR="target/dist"
JAR="target/rSMTv2.jar"
ICON_DIR="dist/icons"

# ── 1. Build fat JAR ──────────────────────────────────────────────────────────
echo "▶  Building fat JAR..."
mvn -q clean package -DskipTests
echo "    JAR: $(ls -lh "$JAR" | awk '{print $5, $9}')"

mkdir -p "$OUT_DIR"

# ── 2. Detect platform & choose icon / type ───────────────────────────────────
OS="$(uname -s)"
OVERRIDE_TYPE="${1:-}"

if [[ "$OVERRIDE_TYPE" == --type* ]]; then
    PKG_TYPE="${1#--type=}"
    PKG_TYPE="${PKG_TYPE#--type }"
elif [[ "$OS" == "Darwin" ]]; then
    PKG_TYPE="dmg"
    ICON="$ICON_DIR/icon.icns"
elif [[ "$OS" == "Linux" ]]; then
    PKG_TYPE="deb"
    ICON="$ICON_DIR/icon.png"
else
    # Windows / MINGW / CYGWIN
    PKG_TYPE="msi"
    ICON="$ICON_DIR/icon.ico"
fi

# Default icon fallback
if [[ ! -f "${ICON:-}" ]]; then
    ICON="$ICON_DIR/icon.png"
fi

echo "▶  Packaging for $OS as .$PKG_TYPE with icon $ICON"

# ── 3. Run jpackage ───────────────────────────────────────────────────────────
jpackage \
    --type         "$PKG_TYPE"             \
    --input        "target"                \
    --main-jar     "rSMTv2.jar"            \
    --main-class   "$MAIN_CLASS"           \
    --name         "$APP_NAME"             \
    --app-version  "$VERSION"              \
    --description  "IBM PowerPC Reverse SMT Processor Simulator" \
    --vendor       "IBM Bob & Daneyand"    \
    --dest         "$OUT_DIR"              \
    --icon         "$ICON"                 \
    ${PKG_TYPE:+--type "$PKG_TYPE"}

# List output
echo ""
echo "✅  Build complete!"
ls -lh "$OUT_DIR"/
echo ""
echo "Installer is in: $OUT_DIR/"
