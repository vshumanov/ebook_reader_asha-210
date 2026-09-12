#!/usr/bin/env bash
# Runs INSIDE an eclipse-temurin:8-jdk container. Builds the reader MIDlet:
#   compile (class v47) -> ProGuard -microedition preverify -> jar + jad.
# Expects the repo mounted at /src and a ProGuard dist at /pg.
set -e
SRC=/src
PG=$(ls /pg/proguard-*/lib/proguard.jar | head -1)
W=/build
rm -rf "$W"; mkdir -p "$W/api" "$W/app" "$W/pv" "$SRC/dist"

echo ">> compile API stubs (compile-time surface, spec-correct constants)"
javac -source 1.3 -target 1.3 -nowarn -d "$W/api" $(find "$SRC/stubs" -name '*.java') 2>/dev/null

echo ">> compile app to CLDC-range bytecode (v47)"
javac -source 1.3 -target 1.3 -nowarn -bootclasspath "$JAVA_HOME/jre/lib/rt.jar" \
      -cp "$W/api" -d "$W/app" $(find "$SRC/src" -name '*.java') 2>/dev/null

echo ">> preverify with ProGuard (-microedition)"
cat > "$W/pg.pro" <<PRO
-injars $W/app
-outjars $W/pv
-libraryjars $JAVA_HOME/jre/lib/rt.jar
-libraryjars $W/api
-dontshrink
-dontoptimize
-dontobfuscate
-dontnote
-dontwarn
-microedition
-keepattributes *
-keep class ** { *; }
PRO
java -jar "$PG" @"$W/pg.pro"

echo ">> package jar + jad"
jar cfm "$SRC/dist/asha-reader.jar" "$SRC/build/manifest-reader.mf" -C "$W/pv" reader
SIZE=$(wc -c < "$SRC/dist/asha-reader.jar" | tr -d ' ')
grep -v '^Manifest-Version' "$SRC/build/manifest-reader.mf" > "$SRC/dist/asha-reader.jad"
echo "MIDlet-Jar-URL: asha-reader.jar" >> "$SRC/dist/asha-reader.jad"
echo "MIDlet-Jar-Size: $SIZE" >> "$SRC/dist/asha-reader.jad"
echo ">> DONE: asha-reader.jar ($SIZE bytes) + asha-reader.jad"
echo ">> class version (want major 47) + preverify StackMap:"
javap -verbose -cp "$W/pv" reader.ReaderCanvas 2>/dev/null | grep -iE "major version|StackMap" | head -2
