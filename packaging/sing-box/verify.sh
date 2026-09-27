#!/bin/bash
# Checks a built core's REALITY hello the way an Xray 26.9 server reads it.
#
# Starts the core from the given archive with a REALITY outbound pointed at
# verify/ (which plays the server, holding the private key), sends one
# request through it, and takes verify's answer: 0 when the hello carries an
# X25519MLKEM768 key share ahead of X25519, names client version 26.3.27,
# lists each key share's group and offers browser ALPN. Chrome's fingerprint
# is checked in one process. "randomized" is checked in twelve: the core
# draws that hello once per process, and the draw that left the hybrid share
# out used to come up in about half of them.
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

(cd "$here/verify" && go build -o "$work/verify" .)

core_pid=
trap 'if [ -n "$core_pid" ]; then kill "$core_pid" 2>/dev/null || true; fi' EXIT

# Starts one core process with the given uTLS fingerprint and returns
# verify's answer on the first hello it sends.
check() {
  local fingerprint=$1
  cat > "$work/config.json" <<EOF
{
  "log": {"level": "warn"},
  "inbounds": [{"type": "mixed", "tag": "in", "listen": "127.0.0.1", "listen_port": 18080}],
  "outbounds": [{
    "type": "vless", "tag": "reality", "server": "127.0.0.1", "server_port": 18443,
    "uuid": "11111111-2222-3333-4444-555555555555", "flow": "xtls-rprx-vision",
    "tls": {
      "enabled": true, "server_name": "www.microsoft.com",
      "utls": {"enabled": true, "fingerprint": "$fingerprint"},
      "reality": {"enabled": true, "public_key": "$public", "short_id": "0123abcd"}
    }
  }]
}
EOF
  "$work/verify" -listen 127.0.0.1:18443 -private-key "$private" &
  local verifier=$!
  "$core" run -c "$work/config.json" &
  core_pid=$!

  sleep 2
  # The verifier closes the connection once it has the hello, so the request
  # itself fails; it is only there to make the core dial.
  curl -s -m 5 -x http://127.0.0.1:18080 https://example.com -o /dev/null || true
  local status=0
  wait "$verifier" || status=$?
  kill "$core_pid" 2>/dev/null || true
  wait "$core_pid" 2>/dev/null || true
  core_pid=
  return "$status"
}

echo "== chrome"
check chrome
for run in $(seq 1 12); do
  echo "== randomized, core process $run of 12"
  check randomized
done
