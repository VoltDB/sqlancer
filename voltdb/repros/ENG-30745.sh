#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30745
-- summary: @ReloadSslContext (voltadmin ssl reload) crashes the node when the new keystore cannot be loaded, instead of answering "reload failed" and keeping the current TLS context
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). One node with TLS on its client, admin
# and internal ports, with a self-signed certificate (keytool). The keystore file is replaced by 3,000 random bytes, as a
# keystore half written or replaced by mistake would be, and tls/ReloadRepro.java calls @ReloadSslContext(0). Exit 0 if
# the node then logs FATAL "TLS/SSL setup failed" and goes down (the bug), 1 if it stays up (answering STATUS 2, reload
# failed, as the procedure intends).
set -u
here=$(cd "$(dirname "$0")" && pwd)
V=$VOLTDB_HOME
C=$WORKDIR/sslreload
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
<ssl enabled="true" external="true" internal="true">
    <keystore path="$T/keystore.jks" password="sqlancer"/>
    <truststore path="$T/truststore.jks" password="sqlancer"/>
</ssl></deployment>
X
"$V/bin/voltdb" init --force ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} -D "$C/n0" -C "$C/d.xml" > "$C/init.log" 2>&1
(nohup "$V/bin/voltdb" start -D "$C/n0" -H localhost:$((B + 2)) --client=$B --admin=$((B + 1)) --internal=$((B + 2)) \
    --zookeeper=$((B + 3)) --replication=$((B + 4)) --metrics=$((B + 5)) > "$C/start.log" 2>&1 & echo $! > "$C/n0.pid")
cleanup() { kill "$(cat "$C/n0.pid")" 2>/dev/null; sleep 3; kill -9 "$(cat "$C/n0.pid")" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 120); do grep -q "Server completed initialization" "$C/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$C/start.log" || { echo "the node did not start"; tail -20 "$C/start.log"; exit 2; }
cp="$V/voltdb/*:$V/lib/*"
javac -cp "$cp" -d "$C/classes" "$here/tls/ReloadRepro.java" || exit 2
sleep 1
head -c 3000 /dev/urandom > "$T/keystore.jks.new" && mv "$T/keystore.jks.new" "$T/keystore.jks"
java -cp "$cp:$C/classes" ReloadRepro localhost:$B "$T/truststore.jks" 2>&1 | grep -v SLF4J
sleep 5
log=$C/n0/voltdbroot/log/volt.log
if grep -q "FATAL.*TLS/SSL setup failed" "$log"; then
    grep "FATAL" "$log" | sed -E 's/^(.{23}).*FATAL/\1 FATAL/' | cut -c1-200
    echo "the node logged FATAL and $(lsof -nP -iTCP:$B -sTCP:LISTEN > /dev/null 2>&1 && echo 'still listens' || echo 'no longer listens') (the bug)"
    exit 0
fi
echo "the node stayed up"
exit 1
