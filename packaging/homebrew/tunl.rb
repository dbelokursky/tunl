# Homebrew Cask for Tunl, intended for a personal tap
# (e.g. `brew tap dbelokursky/tap && brew install --cask tunl`).
#
# version and sha256 are rewritten by scripts/update-packaging.sh after the
# release DMG is built; the committed template carries a 0.0.0 version and an
# all-zero sha256 placeholder.
cask "tunl" do
  version "0.0.0"
  sha256 "0000000000000000000000000000000000000000000000000000000000000000"

  url "https://github.com/dbelokursky/tunl/releases/download/v#{version}/tunl_#{version}.dmg",
      verified: "github.com/dbelokursky/tunl/"
  name "Tunl"
  desc "Cross-platform multi-protocol proxy client wrapping sing-box with a JavaFX GUI"
  homepage "https://github.com/dbelokursky/tunl"

  livecheck do
    url :url
    strategy :github_latest
  end

  app "Tunl.app"

  # Builds are not signed/notarized with an Apple Developer certificate yet, so
  # Gatekeeper blocks the first launch. These caveats mirror the README's
  # "Open Anyway" walkthrough. Remove this stanza once the app is notarized.
  caveats <<~EOS
    Tunl is not notarized yet, so macOS Gatekeeper blocks the first
    launch. To unblock it (a one-time trip through System Settings):

      1. Launch Tunl once and click "Done" (not "Move to Trash").
      2. Open System Settings -> Privacy & Security.
      3. Under Security, click "Open Anyway" next to the "Tunl" notice.
      4. Confirm with your password or Touch ID, then click "Open".

    You must repeat this after every update until the app is signed.
  EOS

  # App data lives under ~/Library/Application Support/VlessClient (see
  # MacPlatformPaths in the source tree; the on-disk path is unchanged by the
  # rename so existing installs keep their settings).
  zap trash: [
    "~/Library/Application Support/VlessClient",
  ]
end
