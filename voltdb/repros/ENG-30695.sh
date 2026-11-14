#!/bin/bash
: <<'HEADER'
-- ticket: ENG-30695
-- summary: A user whose masked password (plaintext false) is given in lowercase hex can never log in, and VoltDB gives no warning
HEADER
# The runner's env: VOLTDB_HOME, PORT_BASE, WORKDIR. 1 node, security on, an administrator. @SetYamlConfiguration adds
# two users with the password pw1 masked as its SHA-256 digest (plaintext false), one in uppercase hex, one in lowercase;
# both changes return status 0. Exit 0 if the uppercase one logs in and the lowercase one is refused (the bug), 1 if both
# log in, 2 on an error.
set -u
V=$VOLTDB_HOME; W=$WORKDIR/maskedcase; P=$PORT_BASE
rm -rf "$W"; mkdir -p "$W"
port() { echo $((P + $1)); }
cat >"$W/d.xml" <<'XML'
<?xml version="1.0"?>
<deployment><cluster sitesperhost="2" kfactor="0"/><httpd enabled="false"/>
<security enabled="true" provider="hash"/>
<users><user name="admin" password="admin" roles="administrator"/></users></deployment>
XML
"$V/bin/voltdb" init --force -D "$W/n0" -C "$W/d.xml" ${VOLTDB_LICENSE:+--license "$VOLTDB_LICENSE"} >"$W/init.log" 2>&1 || exit 2
(nohup "$V/bin/voltdb" start -D "$W/n0" -H "localhost:$(port 2)" --client=$(port 0) --admin=$(port 1) --internal=$(port 2) \
    --zookeeper=$(port 3) --replication=$(port 4) --metrics=$(port 5) >"$W/start.log" 2>&1 & echo $! >"$W/n0.pid")
cleanup() { kill -9 "$(cat "$W/n0.pid" 2>/dev/null)" 2>/dev/null; }
trap cleanup EXIT
for _ in $(seq 1 180); do grep -q "Server completed initialization" "$W/start.log" 2>/dev/null && break; sleep 1; done
grep -q "Server completed initialization" "$W/start.log" || exit 2
sql() { "$V/bin/sqlcmd" --port=$(port 0) "$@" 2>&1; }
lower=$(printf pw1 | shasum -a 256 | cut -c1-64)
upper=$(echo "$lower" | tr a-f A-F)
for u in upper lower; do
    h=$([ $u = upper ] && echo "$upper" || echo "$lower")
    printf "exec @SetYamlConfiguration 'deployment:\n  users:\n  - name: $u\n    password: $h\n    plaintext: false\n    roles: user\n' '';\n" \
        | sql --user=admin --password=admin | tee -a "$W/set.out" | grep -q '^ *0 *$' || { echo "adding $u failed"; cat "$W/set.out"; exit 2; }
done
login() { echo "exec @Ping;" | sql --user=$1 --password=pw1 | grep -q "Authentication rejected" && echo refused || echo "logged in"; }
up=$(login upper); lo=$(login lower)
echo "digest in uppercase: $up; in lowercase: $lo"
[ "$up" = "logged in" ] || exit 2
[ "$lo" = refused ]
