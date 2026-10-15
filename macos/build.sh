#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
out="$PWD/build/macos"
app="$out/PairDrop Fork.app"
extension="$app/Contents/PlugIns/PairDropShare.appex"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources" "$extension/Contents/MacOS"
cp macos/Info.plist "$app/Contents/Info.plist"
cp macos/ShareExtension/Info.plist "$extension/Contents/Info.plist"
cp -R public "$app/Contents/Resources/Web"
cp LICENSE "$app/Contents/Resources/LICENSE.txt"
cp macos/Resources/self-test.js "$app/Contents/Resources/self-test.js"
xcrun swiftc -swift-version 5 -O -target arm64-apple-macos14.0 \
    -framework AppKit -framework WebKit -framework Network -framework UserNotifications -framework UniformTypeIdentifiers -framework CoreServices \
    macos/Sources/*.swift -o "$app/Contents/MacOS/PairDropFork"
xcrun swiftc -swift-version 5 -O -target arm64-apple-macos14.0 -parse-as-library -emit-executable \
    -module-name PairDropShare -framework AppKit -framework UniformTypeIdentifiers -Xlinker -e -Xlinker _NSExtensionMain \
    macos/ShareExtension/ShareViewController.swift -o "$extension/Contents/MacOS/PairDropShare"
iconset="$out/AppIcon.iconset"
mkdir -p "$iconset"
for size in 16 32 128 256 512; do
    sips -z "$size" "$size" public/images/android-chrome-512x512.png --out "$iconset/icon_${size}x${size}.png" >/dev/null
    double=$((size * 2))
    sips -z "$double" "$double" public/images/android-chrome-512x512.png --out "$iconset/icon_${size}x${size}@2x.png" >/dev/null
done
iconutil -c icns "$iconset" -o "$app/Contents/Resources/AppIcon.icns"
codesign --force --sign - --entitlements macos/ShareExtension/Entitlements.plist "$extension"
codesign --force --sign - "$app"
codesign --verify --deep --strict "$app"
test "$(lipo -archs "$app/Contents/MacOS/PairDropFork")" = "arm64"
test "$(lipo -archs "$extension/Contents/MacOS/PairDropShare")" = "arm64"
cp macos/INSTALL_RU.txt "$out/INSTALL_RU.txt"
echo "$app"
