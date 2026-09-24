#!/bin/sh
# Tunl's macOS TUN launcher. The one-time privileged setup installs it
# root:wheel 0755 beside the root-owned core, and the sudoers rule lets the
# user run it, and nothing else, without a password.
#
# The config comes on stdin: the caller's shell opens the file, so root never
# opens a path the user controls. It keeps what a Tunl TUN config is made of
# and drops everything a config could otherwise make root do: write a file
# anywhere (log output, cache file, rule-set path, UI directory), run a
# program (tor), read keys and certificates, serve files, change system
# settings. What is kept:
#   - top level: log, dns, inbounds, outbounds, endpoints, route and
#     experimental only;
#   - log: level, timestamp and disabled;
#   - inbounds: tun, socks, http and mixed, the local ones on 127.0.0.1, none
#     with TLS, platform or system-proxy settings;
#   - outbounds and endpoints: the protocols Tunl builds;
#   - DNS servers: network types only (no hosts files), no TLS options;
#   - rule sets: remote ones, kept in the core's cache;
#   - Clash API: the controller, on 127.0.0.1, and its secret;
#   - cache file: root's own, in a directory only root can open;
#   - any key naming a file or directory, wherever it is.
#
# Every path is fixed. Nothing comes from $0 (a symlink could name this
# script from a directory the user owns) or from the environment (sudo keeps
# the caller's HOME, and jq would load ~/.jq, which could redefine what the
# filter calls).
set -eu
umask 077

base='@BASE@'
state="$base/state"

/bin/mkdir -p "$state"
config=$(/usr/bin/mktemp "$state/config.XXXXXX")
trap '/bin/rm -f "$config"' EXIT
trap '/bin/rm -f "$config"; exit 129' HUP
trap '/bin/rm -f "$config"; exit 130' INT
trap '/bin/rm -f "$config"; exit 143' TERM

# The filter is jq, not shell: $ names are jq variables.
# shellcheck disable=SC2016
/usr/bin/env -i HOME=/var/empty /usr/bin/jq --arg cache "$state/cache.db" '
# The core matches keys ignoring case, Unicode folding included (a long s,
# U+017F, in "disabled" still sets disabled), where jq compares them
# exactly: a key the filter does not recognize could still reach a field.
# So every key must be spelled the one way the core names its fields; only
# HTTP header names are free.
def plain_keys:
  ([paths
    | select((.[-1] | type) == "string" and (length < 2 or .[-2] != "headers"))
    | .[-1]
    | select(explode | any(.[]; (. >= 97 and . <= 122) or (. >= 48 and . <= 57)
                                or . == 95 | not))]
   | unique) as $odd
  | if $odd == [] then .
    else error("keys outside a-z, 0-9 and _: \($odd | join(", "))") end;
def only($types): map(select(.type as $t | any($types[]; . == $t)));
def when_present(f): if . == null then null else f end;
def update($key; f): if has($key) then .[$key] |= f else . end;
def without_nulls: with_entries(select(.value != null));
def without_file_keys:
  walk(if type == "object"
       then del(.certificate_path, .key_path, .client_certificate_path,
                .client_key_path, .config_path, .private_key_path,
                .executable_path, .certificate_directory_path,
                .data_directory, .state_directory, .external_ui, .torrc)
       else . end);
plain_keys
| {
  log: ((.log // {}) | {level, timestamp, disabled} | without_nulls),
  dns: (.dns | when_present(update("servers"; when_present(
    only(["local", "udp", "tcp", "tls", "https", "quic", "h3", "dhcp"])
    | map(del(.tls)))))),
  inbounds: ((.inbounds // []) | only(["tun", "socks", "http", "mixed"])
    | map(del(.tls, .platform, .set_system_proxy)
      | if .type == "tun" then . else .listen = "127.0.0.1" end)),
  outbounds: ((.outbounds // []) | only(["vless", "vmess", "trojan",
    "shadowsocks", "hysteria2", "selector", "urltest", "direct", "block"])),
  endpoints: (.endpoints | when_present(only(["wireguard"]))),
  route: (.route | when_present(update("rule_set"; when_present(
    map(select(.type == "remote") | del(.path)))))),
  experimental: ({
    clash_api: (.experimental.clash_api | when_present({
      external_controller: ("127.0.0.1:"
        + ((.external_controller // "") | tostring | split(":") | last)),
      secret
    } | without_nulls)),
    cache_file: (.experimental.cache_file | when_present(
      {enabled, path: $cache} | without_nulls))
  } | without_nulls)
} | without_nulls | without_file_keys
' > "$config"

# An empty stdin makes an empty config, which the core would run as one
# with nothing in it.
[ -s "$config" ]

exec 3< "$config"
/bin/rm -f "$config"
exec /usr/bin/env -i HOME=/var/empty PATH=/usr/bin:/bin:/usr/sbin:/sbin \
    "$base/sing-box" run -D "$state" -c stdin <&3 3<&-
