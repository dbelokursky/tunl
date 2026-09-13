package com.vlessclient.platform;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemoryLayout.PathElement;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * DPAPI user-scope protection through {@code crypt32.dll}, called in-process
 * with the Foreign Function &amp; Memory API. It replaces a PowerShell process
 * per value: a cold {@code powershell} start costs hundreds of milliseconds,
 * and the app unseals every stored credential while it starts.
 *
 * <p>The arguments are the ones .NET's {@code ProtectedData} passes for
 * {@code DataProtectionScope.CurrentUser} (an empty entropy blob and
 * {@code CRYPTPROTECT_UI_FORBIDDEN}), so a blob written by either opens with
 * the other. The bindings resolve on first use rather than at class load,
 * which keeps the class inert on macOS and Linux.</p>
 */
final class WindowsDpapi implements WindowsDpapiSecretSealer.Dpapi {

    private static final Logger log = LoggerFactory.getLogger(WindowsDpapi.class);

    @Override
    public Optional<byte[]> protect(byte[] plaintext) {
        return transform(true, plaintext);
    }

    @Override
    public Optional<byte[]> unprotect(byte[] blob) {
        return transform(false, blob);
    }

    private static Optional<byte[]> transform(boolean protect, byte[] input) {
        String function = protect ? "CryptProtectData" : "CryptUnprotectData";
        try (Arena arena = Arena.ofConfined()) {
            // At least one byte: an empty allocation may sit at NULL, and a
            // NULL pbData is not worth testing DPAPI's tolerance for.
            MemorySegment bytes = arena.allocate(Math.max(1, input.length));
            try {
                MemorySegment.copy(input, 0, bytes, ValueLayout.JAVA_BYTE, 0, input.length);
                MemorySegment in = Native.blob(arena, bytes, input.length);
                MemorySegment noEntropy = Native.blob(arena, MemorySegment.NULL, 0);
                MemorySegment out = Native.blob(arena, MemorySegment.NULL, 0);
                MemorySegment callState = arena.allocate(Native.CALL_STATE);
                MethodHandle handle = protect ? Native.PROTECT : Native.UNPROTECT;
                if (Native.call(handle, callState, in, noEntropy, out) == 0) {
                    int error = callState.get(ValueLayout.JAVA_INT, Native.LAST_ERROR);
                    log.warn("{} failed with Windows error 0x{}", function,
                            Integer.toHexString(error));
                    return Optional.empty();
                }
                return Optional.of(Native.takeOutput(out));
            } finally {
                bytes.fill((byte) 0);
            }
        } catch (RuntimeException | LinkageError e) {
            log.warn("{} is unavailable: {}", function, e.toString());
            return Optional.empty();
        }
    }

    /** The crypt32 and kernel32 bindings, resolved the first time a value is transformed. */
    private static final class Native {

        /** {@code DATA_BLOB}: a DWORD byte count, padding, then a pointer to the bytes. */
        static final StructLayout DATA_BLOB = MemoryLayout.structLayout(
                ValueLayout.JAVA_INT.withName("cbData"),
                MemoryLayout.paddingLayout(4),
                ValueLayout.ADDRESS.withName("pbData"));
        static final long CB_DATA = DATA_BLOB.byteOffset(PathElement.groupElement("cbData"));
        static final long PB_DATA = DATA_BLOB.byteOffset(PathElement.groupElement("pbData"));

        static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
        static final long LAST_ERROR =
                CALL_STATE.byteOffset(PathElement.groupElement("GetLastError"));

        /** Never prompt: nobody is there to answer while the app loads its config. */
        static final int CRYPTPROTECT_UI_FORBIDDEN = 0x1;

        static final MethodHandle PROTECT;
        static final MethodHandle UNPROTECT;
        static final MethodHandle LOCAL_FREE;

        static {
            Linker linker = Linker.nativeLinker();
            SymbolLookup crypt32 = SymbolLookup.libraryLookup("crypt32", Arena.global());
            // BOOL (DATA_BLOB *in, LPCWSTR or LPWSTR *description, DATA_BLOB *entropy,
            //       PVOID reserved, CRYPTPROTECT_PROMPTSTRUCT *prompt, DWORD flags,
            //       DATA_BLOB *out): the same shape for both directions.
            FunctionDescriptor signature = FunctionDescriptor.of(ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                    ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                    ValueLayout.ADDRESS);
            Linker.Option lastError = Linker.Option.captureCallState("GetLastError");
            PROTECT = linker.downcallHandle(
                    crypt32.findOrThrow("CryptProtectData"), signature, lastError);
            UNPROTECT = linker.downcallHandle(
                    crypt32.findOrThrow("CryptUnprotectData"), signature, lastError);
            LOCAL_FREE = linker.downcallHandle(
                    SymbolLookup.libraryLookup("kernel32", Arena.global())
                            .findOrThrow("LocalFree"),
                    FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        }

        private Native() {
        }

        static MemorySegment blob(Arena arena, MemorySegment data, int length) {
            MemorySegment blob = arena.allocate(DATA_BLOB);
            blob.set(ValueLayout.JAVA_INT, CB_DATA, length);
            blob.set(ValueLayout.ADDRESS, PB_DATA, data);
            return blob;
        }

        static int call(MethodHandle function, MemorySegment callState, MemorySegment in,
                        MemorySegment entropy, MemorySegment out) {
            try {
                return (int) function.invokeExact(callState, in, MemorySegment.NULL, entropy,
                        MemorySegment.NULL, MemorySegment.NULL, CRYPTPROTECT_UI_FORBIDDEN, out);
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        }

        /** Copies DPAPI's output to the heap, then wipes and frees DPAPI's buffer. */
        static byte[] takeOutput(MemorySegment out) {
            long length = Integer.toUnsignedLong(out.get(ValueLayout.JAVA_INT, CB_DATA));
            MemorySegment data = out.get(ValueLayout.ADDRESS, PB_DATA).reinterpret(length);
            try {
                return data.toArray(ValueLayout.JAVA_BYTE);
            } finally {
                data.fill((byte) 0);
                try {
                    LOCAL_FREE.invokeExact(data);
                } catch (Throwable t) {
                    log.debug("LocalFree failed", t);
                }
            }
        }
    }
}
