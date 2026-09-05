package com.vlessclient.testing;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Starts the JavaFX toolkit before a plain (non-TestFX) test class that
 * creates nodes or posts to the FX thread. TestFX classes do not need it:
 * {@code ApplicationTest} brings the toolkit up itself.
 */
public final class FxToolkitExtension implements BeforeAllCallback {

    @Override
    public void beforeAll(ExtensionContext context) {
        FxTestSupport.startToolkit();
    }
}
