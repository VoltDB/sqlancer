#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30747
-- summary: XDCR over TLS: when the DR consumer's TLS handshake fails (here the host name check), the log says only "Could not establish connection to DR producer host", never why, and the consumer retries for ever
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR (and VOLTDB_LICENSE, if any). Two one-node XDCR clusters A (id 1)
# and B (id 2), with TLS on every port, DR's included (<ssl dr="true">), and a self-signed certificate for localhost
# and 127.0.0.1 only. Each consumer connects with ssl="" (the server's truststore) and ssl-host-check="true". A DR
# producer advertises this machine's address, not localhost, so the host name check fails, as it should. Exit 0 if
# B's log, after 40 s, has the connection failures but no word of the TLS handshake, the certificate or the host name
# (the bug), 1 if it says why, 2 on a setup error (or if the producer advertises 127.0.0.1, so the check passes).
set -u
V=$VOLTDB_HOME; W=$WORKDIR/eng30747; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W/tls"
T=$W/tls
keytool -genkeypair -alias voltdb -keyalg RSA -keysize 2048 -validity 30 -dname "CN=localhost" \
    -ext "SAN=dns:localhost,ip:127.0.0.1" -keystore "$T/keystore.jks" -storetype JKS -storepass sqlancer \
    -keypass sqlancer > "$T/keytool.log" 2>&1 || exit 2
keytool -exportcert -alias voltdb -keystore "$T/keystore.jks" -storepass sqlancer -file "$T/voltdb.cer" >> "$T/keytool.log" 2>&1
keytool -importcert -noprompt -alias voltdb -file "$T/voltdb.cer" -keystore "$T/truststore.jks" -storetype JKS \
    -storepass sqlancer >> "$T/keytool.log" 2>&1 || exit 2
A=$P; B=$((P + 100))
for c in A B; do
    base=$([ $c = A ] && echo $A || echo $B); other=$([ $c = A ] && echo $B || echo $A); id=$([ $c = A ] && echo 1 || echo 2)
    cat >"$W/$c.xml" <<XML
<?xml version="1.0"?>
<deployment>
    <cluster sitesperhost="2" kfactor="0"/>
    <httpd enabled="false"/>
    <dr id="$id" role="xdcr">
        <connection source="localhost:$((other + 4))" ssl="" ssl-host-check="true"/>
    </dr>
    <ssl enabled="true" external="true" internal="true" dr="true">
        <keystore path="$T/keystore.jks" password="sqlancer"/>
        <truststore path="$T/truststore.jks" password="sqlancer"/>
    </ssl>
</deployment>
XML
    "$V/bin/voltdb" init --force -D "$W/$c" -C "$W/$c.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init$c.log" 2>&1 || exit 2
    "$V/bin/voltdb" start -D "$W/$c" -H "localhost:$((base + 2))" --client=$base --admin=$((base + 1)) --internal=$((base + 2)) \
        --zookeeper=$((base + 3)) --replication=$((base + 4)) --metrics=$((base + 5)) >"$W/start$c.log" 2>&1 &
    echo $! >"$W/$c.pid"
done
stop() { kill -9 "$(cat "$W/A.pid")" "$(cat "$W/B.pid")" 2>/dev/null; }
trap stop EXIT
for c in A B; do
    for _ in $(seq 1 120); do grep -q "Server completed initialization" "$W/$c/voltdbroot/log/volt.log" 2>/dev/null && break; sleep 1; done
    grep -q "Server completed initialization" "$W/$c/voltdbroot/log/volt.log" 2>/dev/null || { echo "cluster $c did not start"; exit 2; }
done
sleep 40
log=$W/B/voltdbroot/log/volt.log
fails=$(grep -c "Could not establish connection to DR producer host" "$log")
grep -m1 "Could not establish connection to DR producer host" "$log" | cut -c1-220
if [ "$fails" -eq 0 ]; then
    echo "B connected to A: the producer advertised an address the certificate names"
    exit 2
fi
reason=$(grep -iE "handshake|certificate|subject alternative|No name matching|SSLException|CertificateException" "$log" | head -3)
if [ -z "$reason" ]; then
    echo "$fails connection failures in 40 s, none of them saying why (the bug)"
    exit 0
fi
echo "$fails connection failures; the log says why:"
echo "$reason" | cut -c1-220
exit 1
