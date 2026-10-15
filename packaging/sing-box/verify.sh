#!/bin/bash
# Checks a built core's REALITY hello the way an Xray 26.9 server reads it.
#
# Starts the core from the given archive with a REALITY outbound pointed at
# verify/ (which plays the server, holding the private key), sends one
# request through it, and takes verify's answer: 0 when the hello carries an
# X25519MLKEM768 key share ahead of X25519, names client version 26.3.27,
# lists each key share's group and offers browser ALPN.
#
# It checks every fingerprint Tunl sends a REALITY server, each in a core
# process of its own: the fingerprints a link may name, as
# CoreSettings.fingerprintToSend hands them to the core. CoreSettingsTest
# fails when the list below and CoreSettings part. "randomized" is checked in
# twelve processes: the core draws that hello once per process, and the draw
# that left the hybrid share out used to come up in about half of them.
#
# Every hello is checked, and the ones that fail are named at the end. Exits
# 0 when all of them pass and 1 when one fails. A core that sends no hello
# stops the run with 2: the hello was not judged, so neither a pass nor a
# failure would mean anything.
#
# Usage: verify.sh sing-box-<version>-<os>-<arch>.tar.gz
set -euo pipefail

# What Tunl sends a REALITY server, chrome's aliases included: the core maps
# them to Chrome's hello today, and the check holds it to that.
fingerprints=(chrome chrome_psk chrome_psk_shuffle chrome_padding_psk_shuffle chrome_pq
  chrome_pq_psk firefox safari randomized)

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

failed=()
for fingerprint in "${fingerprints[@]}"; do
  processes=1
  if [ "$fingerprint" = randomized ]; then
    processes=12
  fi
  for run in $(seq 1 "$processes"); do
    label=$fingerprint
    if [ "$processes" -gt 1 ]; then
      label="$fingerprint, core process $run of $processes"
    fi
    echo "== $label"
    status=0
    check "$fingerprint" || status=$?
    case $status in
      0) ;;
      1) failed+=("$label") ;;
      *)
        echo "verify: no hello to judge for $label (exit $status)" >&2
        exit 2
        ;;
    esac
  done
done

if [ "${#failed[@]}" -gt 0 ]; then
  echo "verify: these hellos fail:" >&2
  printf '  %s\n' "${failed[@]}" >&2
  exit 1
fi
echo "verify: every hello passes"
