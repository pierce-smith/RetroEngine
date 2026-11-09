#!/usr/bin/env bash
# Writes format fixtures with a released build, for TaggedFormatsTest.
#
#   src/test/fixturegen/freeze-formats.sh beta5
#
# Builds <tag> in a throwaway worktree, runs FixtureGen (scene, prefab, project) and, for tags from beta3 on,
# ExtrasGen (navmesh, .ranim) against it, and leaves the files in build/fixturegen/<tag>/. Copy them into
# src/test/resources/formats/tagged/ named <kind>-v<format version>-<tag>, add them to the lists in
# TaggedFormatsTest, and never regenerate a fixture that is already checked in.
set -euo pipefail

tag="${1:?usage: freeze-formats.sh <release tag>}"
repo="$(cd "$(dirname "$0")/../../.." && pwd)"
work="$(mktemp -d)"
out="$repo/build/fixturegen/$tag"
java_home="${JAVA_HOME:-$(/usr/libexec/java_home -v 25 2>/dev/null || true)}"
[ -n "$java_home" ] || { echo "Set JAVA_HOME to a JDK 25" >&2; exit 1; }

cleanup() { git -C "$repo" worktree remove --force "$work/src" >/dev/null 2>&1 || true; rm -rf "$work"; }
trap cleanup EXIT

git -C "$repo" worktree add --detach "$work/src" "$tag" >/dev/null
cat > "$work/cp.gradle" <<'GRADLE'
allprojects {
  afterEvaluate { p ->
    if (p.plugins.hasPlugin('java')) {
      p.tasks.register('printRuntimeCp') {
        dependsOn 'classes'
        doLast { new File(p.rootDir, 'runtime-cp.txt').text = p.sourceSets.main.runtimeClasspath.asPath }
      }
    }
  }
}
GRADLE
(cd "$work/src" && ./gradlew -q --init-script "$work/cp.gradle" printRuntimeCp)
cp="$(cat "$work/src/runtime-cp.txt")"

rm -rf "$out"
mkdir -p "$out" "$work/bin"
"$java_home/bin/javac" -d "$work/bin" -cp "$cp" "$repo/src/test/fixturegen/FixtureGen.java"
png="$repo/src/main/resources/templates/infinite_driver/Textures/Crate.png"
(cd "$out" && "$java_home/bin/java" -cp "$work/bin:$cp" FixtureGen "$work/src/build/classes/java/main" "$out" "$tag" "$png")

if [ -d "$work/src/src/main/java/com/retro/engine/navigation" ]; then
    "$java_home/bin/javac" -d "$work/bin" -cp "$cp" "$repo/src/test/fixturegen/ExtrasGen.java"
    (cd "$out" && "$java_home/bin/java" -cp "$work/bin:$cp" ExtrasGen "$out" "$tag")
fi
echo "Fixtures for $tag are in $out"
