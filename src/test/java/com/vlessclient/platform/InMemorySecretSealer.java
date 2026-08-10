package com.vlessclient.platform;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test double for {@link SecretSealer}: an in-memory map with switchable
 * availability and failure modes, shared by the persistence test suites.
 */
public final class InMemorySecretSealer implements SecretSealer {

    public static final String FAKE_TAG = SEAL_PREFIX + "fake:v1";

    private final Map<String, String> entries = new ConcurrentHashMap<>();
    private final AtomicInteger sealCalls = new AtomicInteger();
    private boolean available = true;
    private boolean failSeal;

    public Map<String, String> entries() {
        return entries;
    }

    /**
     * How many times {@link #seal} has been called. Each call is a forked
     * {@code security}/{@code secret-tool} process in production, so the count
     * is the thing worth asserting on, not just the resulting entries.
     */
    public int sealCalls() {
        return sealCalls.get();
    }

    public void resetSealCalls() {
        sealCalls.set(0);
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public void setFailSeal(boolean failSeal) {
        this.failSeal = failSeal;
    }

    @Override
    public boolean isAvailable() {
        return available;
    }

    @Override
    public String seal(String key, String plaintext) {
        sealCalls.incrementAndGet();
        if (failSeal) {
            return null;
        }
        entries.put(key, plaintext);
        return FAKE_TAG;
    }

    @Override
    public Optional<String> unseal(String key, String stored) {
        if (!FAKE_TAG.equals(stored)) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(key));
    }

    @Override
    public void delete(String key) {
        entries.remove(key);
    }
}
