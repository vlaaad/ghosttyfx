package io.github.vlaaad.ghostty.impl;

import java.io.IOException;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

public final class NativeRuntime {
    static final int GHOSTTY_SUCCESS = 0;
    static final int GHOSTTY_OUT_OF_MEMORY = -1;
    static final int GHOSTTY_INVALID_VALUE = -2;
    static final int GHOSTTY_OUT_OF_SPACE = -3;
    static final int GHOSTTY_NO_VALUE = -4;

    static final ValueLayout.OfLong SIZE_T_LAYOUT = ValueLayout.JAVA_LONG;
    static final Cleaner CLEANER = Cleaner.create();

    public final NativeMetadata metadata;
    public final NativeKeyCodec nativeKeyCodec;
    public final NativeMouseCodec nativeMouseCodec;

    private NativeRuntime() {
        var osName = System.getProperty("os.name", "");
        var archName = System.getProperty("os.arch", "");

        var os = osName.toLowerCase(Locale.ROOT);
        String extension;
        if (os.contains("win")) {
            os = "windows";
            extension = ".dll";
        } else if (os.contains("mac") || os.contains("darwin")) {
            os = "macos";
            extension = ".dylib";
        } else if (os.contains("linux")) {
            os = "linux";
            extension = ".so";
        } else {
            throw new UnsupportedOperationException(
                "Native runtime is not available for os '" + osName + "' and arch '" + archName + "'"
            );
        }

        var arch = archName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "");
        arch = switch (arch) {
            case "x8664", "amd64", "x86_64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };

        var platform = os + "-" + arch;
        var libraryFileName = "libghostty-vt-" + platform + extension;
        var libraryResource = "native/" + platform + "/" + libraryFileName;

        try (var input = NativeRuntime.class.getClassLoader().getResourceAsStream(libraryResource)) {
            if (input == null) {
                throw new UnsupportedOperationException(
                    "Native runtime is not available for os '" + osName + "' and arch '" + archName
                        + "': missing '" + libraryResource + "'"
                );
            }

            var directory = Files.createTempDirectory("ghosttyfx-" + platform + "-");
            directory.toFile().deleteOnExit();

            var extracted = directory.resolve(libraryFileName);
            Files.copy(input, extracted, StandardCopyOption.REPLACE_EXISTING);
            extracted.toFile().deleteOnExit();
            System.load(extracted.toAbsolutePath().toString());
        } catch (IOException exception) {
            throw sneakyThrow(exception);
        }

        var lookup = SymbolLookup.loaderLookup();
        metadata = new NativeMetadata(lookup);
        nativeKeyCodec = new NativeKeyCodec(lookup);
        nativeMouseCodec = new NativeMouseCodec(lookup);
    }

    public static NativeRuntime instance() {
        return Holder.INSTANCE;
    }

    static MethodHandle bind(SymbolLookup lookup, String symbol, FunctionDescriptor descriptor) {
        var address = lookup.find(symbol)
            .orElseThrow(() -> new IllegalStateException("Native symbol '" + symbol + "' is unavailable"));
        return Linker.nativeLinker().downcallHandle(address, descriptor);
    }

    @SuppressWarnings("unchecked")
    static <T> T invoke(MethodHandle handle, Object... args) {
        try {
            return (T) handle.invokeWithArguments(args);
        } catch (Throwable exception) {
            throw sneakyThrow(exception);
        }
    }

    static void invokeStatus(MethodHandle handle, String errorMessage, Object... args) {
        int result = NativeRuntime.invoke(handle, args);
        if (result == GHOSTTY_SUCCESS) {
            return;
        }
        if (result == GHOSTTY_OUT_OF_MEMORY) {
            throw new ResultException(errorMessage + " failed: out of memory", result);
        }
        if (result == GHOSTTY_INVALID_VALUE) {
            throw new ResultException(errorMessage + " failed: invalid value", result);
        }
        if (result == GHOSTTY_OUT_OF_SPACE) {
            throw new ResultException(errorMessage + " failed: out of space", result);
        }
        if (result == GHOSTTY_NO_VALUE) {
            throw new ResultException(errorMessage + " failed: no value", result);
        }
        throw new ResultException(
            errorMessage + " failed with unexpected result code " + result,
            result
        );
    }

    @SuppressWarnings("unchecked")
    private static <X extends Throwable> RuntimeException sneakyThrow(Throwable exception) throws X {
        throw (X) exception;
    }

    private static final class Holder {
        private static final NativeRuntime INSTANCE = new NativeRuntime();
    }
}
