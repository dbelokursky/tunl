package com.vlessclient.app;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Prevents UI tests from reaching any network address. */
public final class ExternalNetworkGuardExtension implements BeforeAllCallback,
        BeforeEachCallback, AfterEachCallback, AfterAllCallback {

    private static final Object LOCK = new Object();
    private static final Queue<String> VIOLATIONS = new ConcurrentLinkedQueue<>();
    private static int activeUiClasses;
    private static ProxySelector previousSelector;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!isUiTest(context.getRequiredTestClass())) {
            return;
        }

        synchronized (LOCK) {
            if (activeUiClasses++ == 0) {
                previousSelector = ProxySelector.getDefault();
                ProxySelector.setDefault(
                        new NoNetworkProxySelector(previousSelector, VIOLATIONS));
            }
        }
        assertNoViolations(context);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        if (isUiTest(context.getRequiredTestClass())) {
            assertNoViolations(context);
        }
    }

    @Override
    public void afterEach(ExtensionContext context) {
        if (isUiTest(context.getRequiredTestClass())) {
            assertNoViolations(context);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (!isUiTest(context.getRequiredTestClass())) {
            return;
        }

        AssertionError violation = null;
        try {
            assertNoViolations(context);
        } catch (AssertionError e) {
            violation = e;
        } finally {
            synchronized (LOCK) {
                if (--activeUiClasses == 0) {
                    ProxySelector.setDefault(previousSelector);
                    previousSelector = null;
                }
            }
        }
        if (violation != null) {
            throw violation;
        }
    }

    static boolean isUiTest(Class<?> testClass) {
        return testClass.getPackageName().startsWith("com.vlessclient.ui");
    }

    private static void assertNoViolations(ExtensionContext context) {
        List<String> violations = new ArrayList<>();
        String violation;
        while ((violation = VIOLATIONS.poll()) != null) {
            violations.add(violation);
        }
        if (!violations.isEmpty()) {
            throw new AssertionError("Network access is forbidden in UI tests ("
                    + context.getDisplayName() + "):\n  "
                    + String.join("\n  ", violations));
        }
    }

    public static final class NoNetworkProxySelector extends ProxySelector {
        private static final List<Proxy> DIRECT = List.of(Proxy.NO_PROXY);

        private final ProxySelector delegate;
        private final Queue<String> violations;

        NoNetworkProxySelector(ProxySelector delegate, Queue<String> violations) {
            this.delegate = delegate;
            this.violations = violations;
        }

        @Override
        public List<Proxy> select(URI uri) {
            String host = uri == null ? null : uri.getHost();
            if (host == null) {
                return selectWithDelegate(uri);
            }
            String message = String.valueOf(uri) + " from thread "
                    + Thread.currentThread().getName();
            violations.add(message);
            throw new IllegalStateException("Network access is disabled: " + uri);
        }

        private List<Proxy> selectWithDelegate(URI uri) {
            if (delegate == null) {
                return DIRECT;
            }
            List<Proxy> selected = delegate.select(uri);
            return selected == null || selected.isEmpty() ? DIRECT : selected;
        }

        @Override
        public void connectFailed(URI uri, SocketAddress address, IOException failure) {
            if (delegate != null) {
                delegate.connectFailed(uri, address, failure);
            }
        }
    }
}
