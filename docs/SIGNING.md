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
- **Windows:** a code-signing certificate. Two things changed since the
  Windows steps below were written:
  - Since June 2023 a code-signing key must be generated and kept in hardware
    (a token or a cloud HSM), so a CA no longer issues a `.pfx` file. The
    `.pfx` secrets below fit only a certificate issued before then; signing
    in CI now goes through a signing service instead, such as Azure Artifact
    Signing (for individuals, available in the US and Canada only), a CA's
    cloud HSM, or SignPath Foundation, which signs open-source projects free
    of charge.
  - Since 2024 an **EV** certificate no longer skips SmartScreen: EV and OV
    signatures both earn reputation from downloads, and SmartScreen warns
    until they have.

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

The workflow requires each OS-signing secret bundle to be complete or absent.
When configured, it decodes the `.pfx`, runs `signtool sign` on the MSI produced
by `package-windows.ps1`, and verifies the result before upload. The RFC-3161
timestamp keeps the signature valid after the certificate expires:

```powershell
signtool sign /f cert.pfx /p $env:WINDOWS_CERTIFICATE_PASSWORD `
  /fd sha256 /tr http://timestamp.digicert.com /td sha256 dist\tunl_*.msi
```

Note: SmartScreen still shows "Windows protected your PC" for a newly signed
build until the signature builds download reputation. That holds for **EV**
certificates too since 2024. What a signature does change at once is Smart App
Control on Windows 11, which runs a validly signed app and blocks an unsigned
one.

### Signing through SignPath Foundation

SignPath Foundation signs open-source projects for free with a certificate
it holds in its own HSM. The project needs an OSI license (Apache-2.0), no
proprietary components, a code signing policy page, and automated builds
from source. The policy page is [CODE-SIGNING-POLICY.md](CODE-SIGNING-POLICY.md),
which the README links.

**The application is the maintainer's to make.** It opens an account and
accepts SignPath's terms. Apply at <https://signpath.org/apply> with the
repository and the policy page. Once approved, SignPath sends the
organization and project slugs and an API token. Then:

1. Store the token as the `SIGNPATH_API_TOKEN` secret, and the slugs as
   repository variables.
2. Allow SignPath's GitHub action alongside GitHub's own, pinned by commit
   SHA (*Settings → Actions → General*, see
   [REPOSITORY-SETTINGS.md](REPOSITORY-SETTINGS.md)).
3. In `release-windows`, hand the unsigned MSI to SignPath after the install
   smoke test and before the checksum is recorded. Pass the step the token,
   and gate it on the secret's presence, like the signing steps above. The
   checksum, the updater signature and the upload then cover the signed file.
4. Add the attribution line from the policy page to the release notes.

The `WINDOWS_CERTIFICATE_*` steps stay for a certificate held some other
way; the two are alternatives.

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

Release jobs only upload into the tag's one draft release, which a single job
creates. The workflow makes that draft public, by its id, after checking it is
the tag's only release and the four installers, their updater `.sig` files, and
the package manager metadata match the expected manifest. A signing or upload
failure therefore cannot expose a partial release to users.

## Release signing key (for the in-app updater)

Separate from the two certificates above, and solving a different problem. OS
code signing tells the *user's operating system* that the app is not malware.
The release key tells the *running app* that an installer it downloaded by
itself came from us — the check in
`src/main/java/com/vlessclient/service/ReleaseSignature.java`, which the
updater runs before staging anything.

It matters because the SHA-256 the updater compares against arrives in the same
GitHub API response as the download URL: anyone able to alter one alters the
other. A signature made with a key that never appears in the release output
cannot be produced that way.

**What it does not cover:** with the private key stored in GitHub Actions
secrets, an attacker who takes over the repository can run the signing workflow
too. It stops a swapped release asset and a tampered API response, not a
compromised account. Signing offline with a key that never touches CI closes
that as well, and requires no code change — only the workflow step goes away.

**Status: active.** The key pair exists, `RELEASE_SIGNING_KEY` is set, and
`ReleaseSignature.PUBLIC_KEY` carries the public half — so every release from
now on **must** be signed. The `sign-release` job fails the release rather than
skipping when the secret is missing, because an unsigned release is one no
current build can update to.

### One-time setup

Kept for a rotation or a new maintainer; already done for the current key.

Generate the key pair (keep the private key off this repo and out of shell
history — `~/.ssh`-adjacent, encrypted, backed up somewhere you can reach after
losing the machine):

```bash
openssl genpkey -algorithm ED25519 -out tunl-release.key
```

Add the private key as a repository secret named `RELEASE_SIGNING_KEY`, base64
encoded (the `sign-release` job in `release.yml` decodes it):

```bash
base64 -i tunl-release.key | tr -d '\n' | gh secret set RELEASE_SIGNING_KEY
```

Then take the public half and paste it into the `PUBLIC_KEY` constant in
`ReleaseSignature.java`:

```bash
openssl pkey -in tunl-release.key -pubout -outform DER | base64 | tr -d '\n'
```

**Both halves must land in the same release.** An empty constant is what keeps
verification off; filling it in makes a valid signature mandatory for every
future update, so the build that first carries a key must also be the first
release the workflow signs with it. Users on older builds update to that
release through the unverified path once — there is no way around that, and it
is the last time.

### Losing the key

Rotating it is a release like any other: generate a new pair, replace the
secret and the constant. Users only ever verify against the key compiled into
*their* build, so a rotation reaches them the same way any other change does —
through one update signed by the old key.

### Verifying a signature by hand

The public key, the same bytes as `ReleaseSignature.PUBLIC_KEY`:

```
-----BEGIN PUBLIC KEY-----
MCowBQYDK2VwAyEAvICg0uIqKv0NMWhmhSMDoAkcybN1k3ageF1itsRZSCQ=
-----END PUBLIC KEY-----
```

Save it as `tunl-release.pub.pem`, then check the signature current builds
require, over the version, the file name and the digest (`printf`, not
`echo`: there is no trailing newline):

```bash
V=1.23.0; F=tunl_${V}.dmg
printf 'tunl-release-v1\n%s\n%s\nsha256:%s' "$V" "$F" \
  "$(shasum -a 256 "$F" | awk '{print $1}')" > manifest
base64 -d < "$F.manifest.sig" > manifest.sig.bin
openssl pkeyutl -verify -rawin -pubin -inkey tunl-release.pub.pem \
  -in manifest -sigfile manifest.sig.bin
```

`Signature Verified Successfully` means the file is the one the release
workflow signed for that version. The older `.sig` is checked the same way
over `sha256:<hex>` alone.

Releases built since the upload job attests them can also be checked against
their build provenance, which names the workflow run and commit that built
the file (needs the GitHub CLI):

```bash
gh attestation verify tunl_1.23.0.dmg --repo dbelokursky/tunl
```

### What a release signature covers

Every installer gets two detached signatures:

- `<asset>.sig` — over the digest alone, as `sha256:<hex>`. Builds released
  before this existed verify that one, so it keeps being published and their
  updates keep working.
- `<asset>.manifest.sig` — over the version, the asset name and the digest
  together, with no trailing newline:

  ```
  tunl-release-v1
  1.19.1
  tunl_1.19.1.dmg
  sha256:<hex>
  ```

Current builds require the second one. A signature over the digest alone
authorises bytes without saying which release they are, so anyone able to
publish a release without holding the key could put an old, still-signed
installer under a new tag and move installs backwards.
