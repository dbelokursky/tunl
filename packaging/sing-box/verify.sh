#!/bin/bash
# Checks a built core's REALITY hello the way an Xray 26.9 server reads it.
#
# Starts the core from the given archive with a REALITY outbound pointed at
# verify/ (which plays the server, holding the private key), sends one
# request through it, and exits with verify's answer: 0 when the hello
# carries an X25519MLKEM768 key share and names client version 26.3.27.
#
# Usage: verify.sh sing-box-<version>-linux-amd64.tar.gz
set -euo pipefail

archive=$1
here=$(cd "$(dirname "$0")" && pwd)
work=$(mktemp -d)
tar -xzf "$archive" -C "$work"
core=$(find "$work" -name sing-box -type f | head -1)
"$core" version

keys=$("$core" generate reality-keypair)
private=$(awk '/PrivateKey/ {print $2}' <<<"$keys")
public=$(awk '/PublicKey/ {print $2}' <<<"$keys")

cat > "$work/config.json" <<EOF
{
  "log": {"level": "warn"},
  "inbounds": [{"type": "mixed", "tag": "in", "listen": "127.0.0.1", "listen_port": 18080}],
  "outbounds": [{
    "type": "vless", "tag": "reality", "server": "127.0.0.1", "server_port": 18443,
    "uuid": "11111111-2222-3333-4444-555555555555", "flow": "xtls-rprx-vision",
    "tls": {
      "enabled": true, "server_name": "www.microsoft.com",
      "utls": {"enabled": true, "fingerprint": "chrome"},
      "reality": {"enabled": true, "public_key": "$public", "short_id": "0123abcd"}
    }
  }]
}
EOF

(cd "$here/verify" && go build -o "$work/verify" .)
"$work/verify" -listen 127.0.0.1:18443 -private-key "$private" &
verifier=$!
"$core" run -c "$work/config.json" &
core_pid=$!
trap 'kill "$core_pid" 2>/dev/null || true' EXIT

sleep 2
# The verifier closes the connection once it has the hello, so the request
# itself fails; it is only there to make the core dial.
curl -s -m 5 -x http://127.0.0.1:18080 https://example.com -o /dev/null || true
wait "$verifier"
