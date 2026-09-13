package com.vlessclient.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the macOS appearance in-process, through CoreFoundation's preferences
 * API called with the Foreign Function &amp; Memory API. It answers what
 * {@code defaults read -g AppleInterfaceStyle} answers, in microseconds rather
 * than a ~10 ms process, which matters to the theme watcher: it asks every few
 * seconds for as long as the app runs.
 *
 * <p>A read goes through {@code cfprefsd}, so it sees a change another process
 * made (System Settings switching between Light and Dark) without a restart.
 * The bindings resolve on first use; off macOS nothing is loaded and every
 * answer is "not set".</p>
 */
public final class MacAppearance {

    private static final Logger log = LoggerFactory.getLogger(MacAppearance.class);

    private MacAppearance() {
    }

    /**
     * Whether macOS is in Dark mode right now.
     *
     * @return true when the global {@code AppleInterfaceStyle} default is
     *     {@code Dark}; false in Light mode, on other platforms, or when the
     *     default cannot be read
     */
    public static boolean isDark() {
        return Platform.current() == Platform.MAC
                && readGlobalString("AppleInterfaceStyle")
                        .filter("Dark"::equalsIgnoreCase)
                        .isPresent();
    }

    /** A string from the global domain, as {@code defaults read -g <key>} prints it. */
    static Optional<String> readGlobalString(String key) {
        return read(null, key);
    }

    /** A string from {@code domain}, as {@code defaults read <domain> <key>} prints it. */
    static Optional<String> readString(String domain, String key) {
        return read(Objects.requireNonNull(domain, "domain"), key);
    }

    private static Optional<String> read(String domain, String key) {
        try {
            return Native.copyString(domain, key);
        } catch (RuntimeException | LinkageError e) {
            log.debug("Could not read the {} preference: {}", key, e.toString());
            return Optional.empty();
        }
    }

    /** The CoreFoundation bindings, resolved the first time a preference is read. */
    private static final class Native {

        /** The UTF-8 {@code CFStringEncoding}, {@code kCFStringEncodingUTF8}. */
        private static final int UTF8 = 0x0800_0100;

        /** Room for any value this class reads; a longer one reads as not set. */
        private static final int VALUE_BYTES = 256;

        private static final MethodHandle CREATE_STRING;
        private static final MethodHandle COPY_APP_VALUE;
        private static final MethodHandle GET_TYPE_ID;
        private static final MethodHandle STRING_TYPE_ID;
        private static final MethodHandle GET_C_STRING;
        private static final MethodHandle RELEASE;
        private static final MemorySegment ANY_APPLICATION;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup coreFoundation = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                    Arena.global());
            CREATE_STRING = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFStringCreateWithCString"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            COPY_APP_VALUE = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFPreferencesCopyAppValue"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            GET_TYPE_ID = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFGetTypeID"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            STRING_TYPE_ID = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFStringGetTypeID"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            GET_C_STRING = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFStringGetCString"),
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
            RELEASE = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFRelease"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            // A CFStringRef global: the symbol is the address of the pointer.
            ANY_APPLICATION = coreFoundation.findOrThrow("kCFPreferencesAnyApplication")
                    .reinterpret(ValueLayout.ADDRESS.byteSize())
                    .get(ValueLayout.ADDRESS, 0);
        }

        private Native() {
        }

        /** Reads a preference with CFPreferencesCopyAppValue; empty unless it is a string. */
        static Optional<String> copyString(String domain, String key) {
            MemorySegment cfKey = MemorySegment.NULL;
            MemorySegment cfDomain = MemorySegment.NULL;
            MemorySegment value = MemorySegment.NULL;
            try (Arena arena = Arena.ofConfined()) {
                cfKey = (MemorySegment) CREATE_STRING.invokeExact(
                        MemorySegment.NULL, arena.allocateFrom(key), UTF8);
                cfDomain = domain == null
                        ? ANY_APPLICATION
                        : (MemorySegment) CREATE_STRING.invokeExact(
                                MemorySegment.NULL, arena.allocateFrom(domain), UTF8);
                if (cfKey.equals(MemorySegment.NULL) || cfDomain.equals(MemorySegment.NULL)) {
                    return Optional.empty();
                }
                value = (MemorySegment) COPY_APP_VALUE.invokeExact(cfKey, cfDomain);
                if (value.equals(MemorySegment.NULL)) {
                    return Optional.empty();
                }
                long type = (long) GET_TYPE_ID.invokeExact(value);
                if (type != (long) STRING_TYPE_ID.invokeExact()) {
                    return Optional.empty();
                }
                MemorySegment buffer = arena.allocate(VALUE_BYTES);
                byte converted = (byte) GET_C_STRING.invokeExact(
                        value, buffer, (long) VALUE_BYTES, UTF8);
                return converted != 0 ? Optional.of(buffer.getString(0)) : Optional.empty();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            } finally {
                release(value);
                if (domain != null) {
                    release(cfDomain);
                }
                release(cfKey);
            }
        }

        /** Releases an owned reference, skipping NULL: CFRelease aborts on it. */
        private static void release(MemorySegment reference) {
            if (reference.equals(MemorySegment.NULL)) {
                return;
            }
            try {
                RELEASE.invokeExact(reference);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }
    }
}
