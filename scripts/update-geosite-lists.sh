#!/bin/sh
# Brings src/main/resources/geosite-lists.txt up to date with the lists
# SagerNet/sing-geosite publishes on its rule-set branch, or, with --check,
# only says whether it is: exit 0 when the names agree, 1 with the names
# added and removed upstream when they do not.
#
# The Routing dialog warns of a Geosite list the file does not name before it
# adds the rule, and MCP refuses it to agents: sing-geosite adds lists every
# week, so a release built from a stale file warns of lists that exist. Run
# before tagging a release (docs/RELEASE-CHECKLIST.md).
#
# Usage: scripts/update-geosite-lists.sh [--check]
set -eu

check=false
case "${1:-}" in
    --check) check=true ;;
    "") ;;
    *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

root=$(cd "$(dirname "$0")/.." && pwd)
index="$root/src/main/resources/geosite-lists.txt"
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT INT TERM

# The tree alone, without the files' contents: the names are all it needs.
git clone -q --depth 1 --filter=blob:none --no-checkout --branch rule-set \
    https://github.com/SagerNet/sing-geosite "$work/sing-geosite"
commit=$(git -C "$work/sing-geosite" log -1 --format=%H)
date=$(git -C "$work/sing-geosite" log -1 --format=%cs)
# In the tree's order, which the file keeps; sorted alike only to compare.
git -C "$work/sing-geosite" ls-tree --name-only HEAD \
    | sed -n 's/^geosite-\(.*\)\.srs$/\1/p' > "$work/tree"
if [ ! -s "$work/tree" ]; then
    echo "no geosite-*.srs on the rule-set branch at $commit" >&2
    exit 1
fi
LC_ALL=C sort "$work/tree" > "$work/live"
grep -v '^#' "$index" | LC_ALL=C sort > "$work/bundled"

if [ "$check" = true ]; then
    if cmp -s "$work/bundled" "$work/live"; then
        echo "geosite-lists.txt names the $(wc -l < "$work/live" | tr -d ' ') lists of $commit ($date)"
        exit 0
    fi
    echo "geosite-lists.txt differs from the rule-set branch at $commit ($date):"
    LC_ALL=C comm -13 "$work/bundled" "$work/live" | sed 's/^/  published: /'
    LC_ALL=C comm -23 "$work/bundled" "$work/live" | sed 's/^/  removed:   /'
    echo "Run $0 without --check and commit the file."
    exit 1
fi

{
    cat <<EOF
# The lists SagerNet/sing-geosite published on its rule-set branch when this
# version was built, one name a line: a Geosite rule downloads
# https://raw.githubusercontent.com/SagerNet/sing-geosite/rule-set/
# geosite-<name>.srs, and the core does not start without its rule-sets, so a
# rule naming a list that does not exist stops every server from connecting.
# The Routing dialog warns of any other name before it adds the rule, and MCP
# refuses it to agents. A stored rule is kept whatever its name: a list newer
# than this file is not missing.
#
# From the rule-set branch at $commit
# ($date). To bring it up to date: scripts/update-geosite-lists.sh
EOF
    cat "$work/tree"
} > "$work/index"
mv "$work/index" "$index"
echo "geosite-lists.txt: $(wc -l < "$work/live" | tr -d ' ') lists of $commit ($date)"
