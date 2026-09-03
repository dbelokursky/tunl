# Security policy

Tunl is a VPN client. It stores server credentials, runs a privileged helper
for TUN mode, changes the operating system's proxy settings and installs its
own updates. Reports about any of that are welcome and taken seriously.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private
vulnerability reporting for this repository:

**Security → Report a vulnerability**, or
<https://github.com/dbelokursky/tunl/security/advisories/new>.

Include the Tunl version (Settings → About), the OS, and how to reproduce. You
will get an acknowledgement within seven days. Once a fix is released, the
advisory is published with credit to the reporter unless you prefer otherwise.

## Supported versions

Only the newest release on the
[Releases](https://github.com/dbelokursky/tunl/releases) page receives fixes.
Older releases are removed from the page as new ones are published.

## In scope

- Credential storage: the Keychain / DPAPI / Secret Service sealing and the
  fallback files in the data directory.
- Privilege boundaries: the macOS sudoers rule and privileged core, the
  Windows UAC launch scripts, the Linux pkexec / setcap path.
- System-proxy handling and its crash-recovery guard.
- The in-app updater: download, SHA-256 and Ed25519 verification, staging and
  the platform relay that replaces the installed files.
- The loopback MCP server: token handling, mutation gating, the audit log.
- Subscription fetching and share-link parsing.

## Out of scope

- sing-box itself and the protocols it implements: report those upstream at
  <https://github.com/SagerNet/sing-box/security>.
- The servers you connect to, or the services reached through them.
