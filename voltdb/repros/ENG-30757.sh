#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30757
-- summary: the JDBC driver cannot connect to a cluster that requires client certificates (<ssl clientauthrequired="true">): it has no keystore property, and its TLS setup ignores javax.net.ssl.keyStore
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node with TLS on its client, admin
# and internal ports and clientauthrequired="true", and a self-signed certificate (keytool) in its keystore and
# truststore; a client presenting that certificate is trusted. tls/MutualAuthRepro.java connects with Client2 and the
# keystore (the control), then with the JDBC driver (ssl=true, truststore, truststorepassword), then with the JDBC
# driver and javax.net.ssl.keyStore set. Exit 0 if Client2 connects and neither JDBC connection does (the bug), 1 if a
# JDBC connection works, 2 on a setup error.
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/eng30757
B=$PORT_BASE
rm -rf "$C"; mkdir -p "$C/classes" "$C/tls"
T=$C/tls
keytool -genkeypair -alias voltdb -keyalg RSA -keysize 2048 -validity 30 -dname "CN=localhost" \
    -ext "SAN=dns:localhost,ip:127.0.0.1" -keystore "$T/keystore.jks" -storetype JKS -storepass sqlancer \
    -keypass sqlancer > "$T/keytool.log" 2>&1 || exit 2
keytool -exportcert -alias voltdb -keystore "$T/keystore.jks" -storepass sqlancer -file "$T/voltdb.cer" >> "$T/keytool.log" 2>&1
keytool -importcert -noprompt -alias voltdb -file "$T/voltdb.cer" -keystore "$T/truststore.jks" -storetype JKS \
    -storepass sqlancer >> "$T/keytool.log" 2>&1 || exit 2
cat > "$C/d.xml" <<X
<?xml version="1.0"?>
<deployment><cluster sitesperhost="2" kfactor="0"/><httpd enabled="false"/>
<ssl enabled="true" external="true" internal="true" clientauthrequired="true">
    <keystore path="$T/keystore.jks" password="sqlancer"/>
    <truststore path="$T/truststore.jks" password="sqlancer"/>
</ssl></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n0" -C "$C/d.xml" > "$C/init.log" 2>&1 || exit 2
(nohup "$V/bin/voltdb" start -D "$C/n0" -H localhost:$((B + 2)) --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > "$C/start.log" 2>&1 & echo $! > "$C/n0.pid")
cleanup() { kill "$(cat "$C/n0.pid")" 2>/dev/null; sleep 3; kill -9 "$(cat "$C/n0.pid")" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$C/start.log" || { echo "the node did not start"; tail -20 "$C/start.log"; exit 2; }
cp="$V/voltdb/*:$V/lib/*"
javac -cp "$cp" -d "$C/classes" "$here/tls/MutualAuthRepro.java" || exit 2
out=$(java -cp "$cp:$C/classes" MutualAuthRepro localhost:$B "$T/truststore.jks" "$T/keystore.jks" 2>&1 | grep -v SLF4J)
echo "$out"
grep -h "Rejected new connection" "$C/n0/voltdbroot/log/volt.log" | head -2 | sed -E 's/^(.{23}).*(Rejected)/\1 \2/' | cut -c1-200
echo "$out" | grep -q "^client2: ok" || { echo "Client2 with the keystore did not connect: the setup is wrong"; exit 2; }
if echo "$out" | grep "^jdbc" | grep -q ": ok$"; then
    echo "a JDBC connection worked"
    exit 1
fi
echo "Client2 with a keystore connects; the JDBC driver cannot (the bug)"
exit 0
