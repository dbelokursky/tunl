package com.vlessclient.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Declines to self-update on Linux.
 *
 * <p>The release ships a {@code .deb}, which installs as root into
 * {@code /opt} and {@code /usr}. An application that could replace those files
 * on its own would be a privilege-escalation vector, not a feature — and a
 * package installed by {@code dpkg} that quietly changes underneath the
 * package manager leaves the system's own database lying about what is
 * installed. Upgrades belong to whatever installed the package: apt, the AUR
 * package under {@code packaging/aur}, or Homebrew.</p>
 *
 * <p>Detection still runs, so the Settings row keeps telling the user a newer
 * version exists — only the applying is theirs to do.</p>
 */
final class LinuxUpdateApplier implements UpdateApplier {

    private static final Logger log = LoggerFactory.getLogger(LinuxUpdateApplier.class);

    @Override
    public Outcome apply(PendingUpdate update) {
        log.info("Update {} is available; install it through the package manager "
                + "that installed Tunl (apt / AUR / Homebrew)", update.version());
        return Outcome.UNSUPPORTED;
    }

    @Override
    public boolean selfUpdates() {
        return false;
    }
}
