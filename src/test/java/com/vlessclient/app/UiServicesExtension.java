package com.vlessclient.app;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;

/**
 * Builds the network-free service graph before a test class and puts the
 * locator back the way that graph left it afterwards.
 *
 * <p>Two things went wrong before this existed. Eleven classes wrapped
 * {@link UiTestServices#initialize()} in a catch-all, so a graph that failed
 * to build left the class silently testing whatever the previous class had
 * registered; five classes built no graph at all and relied on one being left
 * behind. And any double a class registered stayed in the process-wide
 * locator for every class after it.</p>
 *
 * <p>The snapshot is taken right after the graph is built, before the class's
 * own {@code @BeforeAll}, so whatever the class registers on top of it — a
 * fake engine, a recording connection service, its own config store — is
 * dropped again after the class. The locator has no way to enumerate or
 * unregister what it holds, so the snapshot goes through its private map.</p>
 */
public final class UiServicesExtension implements BeforeAllCallback, AfterAllCallback {

    private static final Namespace NAMESPACE = Namespace.create(UiServicesExtension.class);
    private static final String SNAPSHOT = "snapshot";

    @Override
    public void beforeAll(ExtensionContext context) {
        UiTestServices.initialize();
        context.getStore(NAMESPACE).put(SNAPSHOT, new HashMap<>(registry()));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void afterAll(ExtensionContext context) {
        Map<Class<?>, Object> snapshot =
                context.getStore(NAMESPACE).remove(SNAPSHOT, Map.class);
        if (snapshot == null) {
            return;
        }
        Map<Class<?>, Object> registry = registry();
        registry.clear();
        registry.putAll(snapshot);
    }

    @SuppressWarnings("unchecked")
    private static Map<Class<?>, Object> registry() {
        try {
            Field services = ServiceLocator.class.getDeclaredField("services");
            services.setAccessible(true);
            return (Map<Class<?>, Object>) services.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(
                    "ServiceLocator no longer keeps its registry in a 'services' map", e);
        }
    }
}
