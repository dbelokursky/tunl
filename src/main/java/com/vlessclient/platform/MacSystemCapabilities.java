package com.vlessclient.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the macOS kernel says the machine can do right now: the
 * {@code System Capabilities} of its power-management root, read in-process
 * through IOKit with the Foreign Function &amp; Memory API in a few
 * microseconds.
 *
 * <p>A full wake has the CPU, graphics, audio and the network ({@code 0xf}).
 * A dark wake, a maintenance wake included, has the CPU and the network
 * only, and so does every sleep on its way down: the kernel logs
 * {@code 0x0->0x9} when the Mac wakes in the dark, {@code 0x9->0xf} when the
 * user wakes it, and {@code 0xf->0x9} some seconds before {@code 0x9->0x0}
 * as it goes to sleep. {@code pmset -g systemstate} prints the same, from a
 * process of its own.</p>
 */
final class MacSystemCapabilities {

    private static final Logger log = LoggerFactory.getLogger(MacSystemCapabilities.class);

    /** {@code kIOPMSystemCapabilityCPU}. */
    static final long CPU = 0x1;
    /** {@code kIOPMSystemCapabilityGraphics}: the machine is in a full wake. */
    static final long GRAPHICS = 0x2;
    /** {@code kIOPMSystemCapabilityAudio}. */
    static final long AUDIO = 0x4;
    /** {@code kIOPMSystemCapabilityNetwork}. */
    static final long NETWORK = 0x8;

    private static final AtomicBoolean FAILURE_LOGGED = new AtomicBoolean();

    private MacSystemCapabilities() {
    }

    /**
     * Reads the capabilities now.
     *
     * @return the {@code kIOPMSystemCapability} bits, or empty off macOS and
     *     whenever they cannot be read
     */
    static OptionalLong read() {
        if (Platform.current() != Platform.MAC) {
            return OptionalLong.empty();
        }
        try {
            return Native.read();
        } catch (RuntimeException | LinkageError e) {
            if (FAILURE_LOGGED.compareAndSet(false, true)) {
                log.warn("Cannot read the system's power capabilities: {}", e.toString());
            }
            return OptionalLong.empty();
        }
    }

    /**
     * Whether the machine is in a full wake, by the capabilities
     * {@code source} reads.
     *
     * <p>A host that has not reported graphics once in this run cannot be
     * told apart from one in a dark wake, a virtual machine or a Mac without
     * graphics perhaps, and counts as awake, as does one whose capabilities
     * cannot be read: health checks run there as they did before. The app
     * itself starts in a full wake, where the user is.</p>
     *
     * @param source reads the capabilities, empty when it cannot
     * @return the read, which remembers whether graphics was ever seen
     */
    static BooleanSupplier fullWake(Supplier<OptionalLong> source) {
        AtomicBoolean seenGraphics = new AtomicBoolean();
        return () -> {
            OptionalLong capabilities = source.get();
            if (capabilities.isEmpty()) {
                return true;
            }
            if ((capabilities.getAsLong() & GRAPHICS) != 0) {
                seenGraphics.set(true);
                return true;
            }
            return !seenGraphics.get();
        };
    }

    /** The IOKit and CoreFoundation bindings, resolved the first time they are read. */
    private static final class Native {

        /** The UTF-8 {@code CFStringEncoding}, {@code kCFStringEncodingUTF8}. */
        private static final int UTF8 = 0x0800_0100;

        /** {@code kCFNumberSInt64Type}. */
        private static final long SINT64 = 4;

        /** {@code kIOMainPortDefault}: the default port, {@code MACH_PORT_NULL}. */
        private static final int MAIN_PORT = 0;

        private static final MethodHandle CREATE_PROPERTY;
        private static final MethodHandle GET_TYPE_ID;
        private static final MethodHandle NUMBER_TYPE_ID;
        private static final MethodHandle NUMBER_VALUE;
        private static final MethodHandle RELEASE;

        /** The power-management root; 0 when there is none to read. */
        private static final int ROOT_DOMAIN;

        /** The property's name as a CFString, kept for the run. */
        private static final MemorySegment KEY;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup ioKit = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/IOKit.framework/IOKit", Arena.global());
            SymbolLookup coreFoundation = SymbolLookup.libraryLookup(
                    "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation",
                    Arena.global());
            CREATE_PROPERTY = linker.downcallHandle(
                    ioKit.findOrThrow("IORegistryEntryCreateCFProperty"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            GET_TYPE_ID = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFGetTypeID"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            NUMBER_TYPE_ID = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFNumberGetTypeID"),
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG));
            NUMBER_VALUE = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFNumberGetValue"),
                    FunctionDescriptor.of(ValueLayout.JAVA_BYTE, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            RELEASE = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFRelease"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
            MethodHandle matching = linker.downcallHandle(
                    ioKit.findOrThrow("IOServiceMatching"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            MethodHandle matchingService = linker.downcallHandle(
                    ioKit.findOrThrow("IOServiceGetMatchingService"),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            MethodHandle createString = linker.downcallHandle(
                    coreFoundation.findOrThrow("CFStringCreateWithCString"),
                    FunctionDescriptor.of(ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            try (Arena arena = Arena.ofConfined()) {
                // IOServiceGetMatchingService takes the dictionary's reference.
                MemorySegment rootMatch = (MemorySegment) matching.invokeExact(
                        arena.allocateFrom("IOPMrootDomain"));
                ROOT_DOMAIN = rootMatch.equals(MemorySegment.NULL)
                        ? 0 : (int) matchingService.invokeExact(MAIN_PORT, rootMatch);
                KEY = (MemorySegment) createString.invokeExact(MemorySegment.NULL,
                        arena.allocateFrom("System Capabilities"), UTF8);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        private Native() {
        }

        /** Reads the property; empty unless it is there and a number. */
        static OptionalLong read() {
            if (ROOT_DOMAIN == 0 || KEY.equals(MemorySegment.NULL)) {
                return OptionalLong.empty();
            }
            MemorySegment value = MemorySegment.NULL;
            try (Arena arena = Arena.ofConfined()) {
                value = (MemorySegment) CREATE_PROPERTY.invokeExact(
                        ROOT_DOMAIN, KEY, MemorySegment.NULL, 0);
                if (value.equals(MemorySegment.NULL)) {
                    return OptionalLong.empty();
                }
                long type = (long) GET_TYPE_ID.invokeExact(value);
                if (type != (long) NUMBER_TYPE_ID.invokeExact()) {
                    return OptionalLong.empty();
                }
                MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG);
                byte converted = (byte) NUMBER_VALUE.invokeExact(value, SINT64, out);
                return converted != 0
                        ? OptionalLong.of(out.get(ValueLayout.JAVA_LONG, 0))
                        : OptionalLong.empty();
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            } finally {
                if (!value.equals(MemorySegment.NULL)) {
                    release(value);
                }
            }
        }

        private static void release(MemorySegment reference) {
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
