#!/bin/sh
# This source delivery does not bundle a binary Gradle wrapper.
# Use an installed Gradle 8.9, then run `gradle wrapper --gradle-version 8.9`
# to create the standard wrapper, or import this project in Android Studio.
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
if [ -f "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
    if [ -n "${JAVA_HOME:-}" ]; then
        JAVA_CMD="$JAVA_HOME/bin/java"
    else
        JAVA_CMD=java
    fi
    exec "$JAVA_CMD" -classpath "$PROJECT_DIR/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
fi
if command -v gradle >/dev/null 2>&1; then
    exec gradle -p "$PROJECT_DIR" "$@"
fi
printf '%s\n' 'Gradle 8.9 is required. Install Gradle 8.9 or open this project in Android Studio.' >&2
exit 1
