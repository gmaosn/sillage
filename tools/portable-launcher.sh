#!/bin/sh
# Starts @GAME@ with the system's Java (25 or later): JAVA_HOME if set, else the java on the PATH, else where
# FreeBSD and NetBSD install theirs.
here=$(dirname "$(readlink -f "$0" 2>/dev/null || echo "$0")")
java=
for j in "${JAVA_HOME:+$JAVA_HOME/bin/java}" "$(command -v java 2>/dev/null)" /usr/local/openjdk2[5-9]*/bin/java /usr/pkg/java/openjdk2[5-9]*/bin/java; do
	[ -n "$j" ] && [ -x "$j" ] || continue
	v=$("$j" -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = \([0-9]*\).*/\1/p')
	[ "${v:-0}" -ge 25 ] && { java=$j; break; }
done
[ -n "$java" ] || { echo "@GAME@ needs Java 25 or later (FreeBSD: pkg install openjdk25-jre-headless ; NetBSD: pkgin install openjdk25)" >&2; exit 1; }
exec "$java" --enable-native-access=ALL-UNNAMED -cp "$here/lib/@GAME@.jar:$here/lib/kotlin-stdlib.jar" @MAIN@ "$@"
