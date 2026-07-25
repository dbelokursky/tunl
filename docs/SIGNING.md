# Code signing & notarization

Releases ship **unsigned** today. macOS Gatekeeper blocks the DMG on first
launch and Windows SmartScreen warns on the MSI (see the README install
sections). The packaging scripts already leave a hook for signing —
`scripts/package-dmg.sh` signs the `.app` during `jpackage` **when
`MACOS_SIGN_IDENTITY` is set**, and is a no-op otherwise. The workflow steps
that consume the secrets are **already in place** in `release.yml`, each gated
on its secret's presence — so activating signing is only a matter of adding
the secrets below; nothing in the workflow itself changes. This doc lists the
exact secret names and how to produce each.

## Prerequisites & cost

- **macOS:** an [Apple Developer Program](https://developer.apple.com/programs/)
  membership (**$99/yr**) — required both for the Developer ID certificate and
  for notarization.
- **Windows:** a code-signing certificate from a CA. An **OV** cert is cheaper
  but SmartScreen keeps warning until the signature earns reputation; an **EV**
  cert (hardware token / attestation) is trusted immediately but costs more.

## macOS — GitHub Actions secrets

| Secret | What it is |
|---|---|
| `MACOS_CERTIFICATE_P12_BASE64` | base64 of your **Developer ID Application** certificate exported as `.p12` |
| `MACOS_CERTIFICATE_PASSWORD` | the password set when exporting the `.p12` |
| `MACOS_SIGN_IDENTITY` | the identity string, e.g. `Developer ID Application: Your Name (TEAMID)` |
| `MACOS_NOTARY_KEY_BASE64` | base64 of the App Store Connect API key (`.p8`) |
| `MACOS_NOTARY_KEY_ID` | the API key's Key ID |
| `MACOS_NOTARY_ISSUER_ID` | the App Store Connect issuer UUID |

**Produce the certificate.** In Keychain Access request/install a *Developer ID
Application* certificate (Apple Developer → Certificates), then
right-click it → **Export** as `vless-signing.p12` with a password. base64 it:

```bash
base64 -i vless-signing.p12 | pbcopy   # → MACOS_CERTIFICATE_P12_BASE64
```

Read `MACOS_SIGN_IDENTITY` from the exported cert (the full string is the
Common Name):

```bash
security find-identity -v -p codesigning
```

**Produce the notary key.** App Store Connect → **Users and Access → Integrations
→ App Store Connect API** → generate a key with the *Developer* role. Download
the `.p8` **once** (it is not re-downloadable), note its **Key ID** and the
team's **Issuer ID**:

```bash
base64 -i AuthKey_XXXXXXXX.p8 | pbcopy   # → MACOS_NOTARY_KEY_BASE64
```

**What happens once the secrets exist.** The release workflow imports the
`.p12` into a temporary keychain and exports `MACOS_SIGN_IDENTITY`, so
`package-dmg.sh`'s `jpackage` signs the `.app` with the Developer ID identity.
A follow-up step submits the finished DMG to Apple's notary service with the
`.p8` key (`xcrun notarytool submit --wait`) and staples the ticket
(`xcrun stapler staple`). Without the secrets the release is unsigned, exactly
as today.

## Windows — GitHub Actions secrets

| Secret | What it is |
|---|---|
| `WINDOWS_CERTIFICATE_PFX_BASE64` | base64 of your code-signing certificate (`.pfx`) |
| `WINDOWS_CERTIFICATE_PASSWORD` | the `.pfx` password |

The workflow decodes the `.pfx` and runs `signtool sign` on the MSI produced by
`package-windows.ps1`, with an RFC-3161 timestamp so the signature outlives the
cert's validity:

```powershell
signtool sign /f cert.pfx /p $env:WINDOWS_CERTIFICATE_PASSWORD `
  /fd sha256 /tr http://timestamp.digicert.com /td sha256 dist\tunl_*.msi
```

Note: an **OV** cert is valid but SmartScreen still shows "Windows protected
your PC" until the publisher builds download reputation; an **EV** cert avoids
the warning from the first signed build.

## How to verify

macOS (against the installed app and the DMG):

```bash
codesign --verify --deep --strict --verbose=2 "/Applications/Tunl.app"
spctl -a -t open --context context:primary-signature dist/tunl_*.dmg
xcrun stapler validate dist/tunl_*.dmg
```

Windows:

```powershell
signtool verify /pa dist\tunl_*.msi
```
