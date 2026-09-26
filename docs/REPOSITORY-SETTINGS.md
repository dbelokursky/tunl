# Repository settings

What is set on GitHub for this repository rather than in the tree, and why.
None of it shows in a checkout, and each part changes what a workflow can do.

## Actions policy

- **Only GitHub's own actions** (`actions/*`, `github/*`) may run, besides
  this account's own: *Settings → Actions → General → Allow dbelokursky, and
  select non-dbelokursky, actions and reusable workflows → Allow actions
  created by GitHub*, with nothing else ticked or listed.
- **Every action must be pinned by full commit SHA**: *Require actions to be
  pinned to a full-length commit SHA*.

Every workflow already follows both rules. What the rules add is that a
change that breaks them fails at once, instead of running third-party code
with the job's token. To add an action, take one GitHub publishes. Pin it by
the commit its release tag names, with the version as a comment, as the
other steps do:

```bash
tag=$(gh api repos/actions/<name>/releases/latest --jq .tag_name)
gh api repos/actions/<name>/git/ref/tags/$tag --jq .object.sha
```

A tag that points at an annotated tag object needs one more step,
`gh api repos/actions/<name>/git/tags/<sha> --jq .object.sha`, to reach the
commit. Dependabot keeps the pins current (`.github/dependabot.yml`).

Branches older than September 2026 (`build/java-27`, `feat/mcp-server` and
others) still use `@v4` tags and `softprops/action-gh-release`. Their
workflows no longer run under this policy, and nothing is merged from them.

The default `GITHUB_TOKEN` is read-only, and each job asks for what it
writes. Actions may still create pull requests: `bump-singbox.yml` opens one
when `BUMP_TOKEN` is missing.

## CodeQL

Default setup, weekly and on every pull request, for `actions`, `go`
(`packaging/sing-box/verify`) and `java-kotlin`. Ruby is left out: the only
Ruby file is the Homebrew cask template, which has nothing to analyze.

## Secrets

| Secret | Used by | What it is |
|---|---|---|
| `RELEASE_SIGNING_KEY` | `release.yml` (`sign-release`) | The Ed25519 key the in-app updater checks installers against; see [SIGNING.md](SIGNING.md). Required for a release. |
| `BUMP_TOKEN` | `bump-singbox.yml` | Opens the core bump's pull request so that its checks run without a click. Optional. |
| `MACOS_*`, `WINDOWS_*` | `release.yml` | OS code signing, not configured; see [SIGNING.md](SIGNING.md). |

### `BUMP_TOKEN`

A pull request opened with `GITHUB_TOKEN` gets its checks parked in "action
required" until someone approves them. A `workflow_dispatch` run on the
branch does not satisfy them either. That was verified by experiment, see
the comments in `bump-singbox.yml`. A personal access token avoids the click.
Without it the bump still works, with the one extra click.

Make it a **fine-grained** token scoped to this repository alone, so a leak
exposes one repository's contents and pull requests and nothing else:

1. *GitHub → Settings → Developer settings → Fine-grained tokens → Generate
   new token*.
2. **Repository access:** *Only select repositories* → `dbelokursky/tunl`.
3. **Permissions → Repository:**
   - *Contents*: Read and write, to push the bump branch.
   - *Pull requests*: Read and write, to open the pull request.
   - *Metadata*: Read-only, which GitHub adds on its own.
4. **Expiration:** a year at most, with a reminder to renew it.
5. Store it, replacing the classic token set in August 2026:

   ```bash
   gh secret set BUMP_TOKEN --repo dbelokursky/tunl
   ```

   Then revoke the old token under *Personal access tokens (classic)*.
