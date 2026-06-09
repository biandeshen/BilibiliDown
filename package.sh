#!/bin/bash
# Package: produce versioned INeedBiliAV_v{version}.jar
cd $(dirname $0)

# Read version
VER=$(grep -oP 'bilibili.version.*defaultValue.*v\K[\d.]+' src/nicelee/ui/Global.java)
VER="v$VER"
echo "Version: $VER"

# Clean
rm -rf _target && mkdir -p _target
cp -r src/. _target
rm -rf _target/nicelee/test

# Find sources
cd _target
find $(pwd) -name "*.java" > ../_sources.tmp
cd ..

# Extract libs
cd _target
for jar in ../libs/*.jar; do jar xf "$jar" 2>/dev/null; done
cd ..

# Compile
cd _target
javac -encoding UTF-8 @../_sources.tmp || { echo "COMPILE FAILED"; exit 1; }
find . -name "*.java" | xargs rm -f
cd ..

# Package
jar cf "INeedBiliAV_$VER.jar" -C _target .
echo "Done: INeedBiliAV_$VER.jar"

# Launcher
if [ -d src-launcher ]; then
  rm -rf _target-launcher && mkdir -p _target-launcher
  cp -r src-launcher/. _target-launcher
  cd _target-launcher
  find $(pwd) -name "*.java" > ../_sources2.tmp
  javac -encoding UTF-8 @../_sources2.tmp
  find . -name "*.java" | xargs rm -f
  cd ..
  jar cf "launch_$VER.jar" -C _target-launcher .
  echo "Done: launch_$VER.jar"
  rm -rf _target-launcher ../_sources2.tmp 2>/dev/null
fi

rm -rf _target _sources.tmp
echo "=== Package complete: INeedBiliAV_$VER.jar ==="
