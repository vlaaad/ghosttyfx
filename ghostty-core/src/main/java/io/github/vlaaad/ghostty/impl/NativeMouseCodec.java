package io.github.vlaaad.ghostty.impl;

import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Objects;

import io.github.vlaaad.ghostty.KeyModifiers;
import io.github.vlaaad.ghostty.ModifierSide;
import io.github.vlaaad.ghostty.MouseCodec;
import io.github.vlaaad.ghostty.MouseCodecConfig;
import io.github.vlaaad.ghostty.MouseEncodeContext;
import io.github.vlaaad.ghostty.MouseEncoderSize;
import io.github.vlaaad.ghostty.MouseEvent;

public final class NativeMouseCodec {
    private static final long INITIAL_OUTPUT_CAPACITY = 64;

    private static final int GHOSTTY_MOUSE_ENCODER_OPT_EVENT = 0;
    private static final int GHOSTTY_MOUSE_ENCODER_OPT_FORMAT = 1;
    private static final int GHOSTTY_MOUSE_ENCODER_OPT_SIZE = 2;
    private static final int GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED = 3;
    private static final int GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL = 4;

    private static final int GHOSTTY_MODS_SHIFT = 1;
    private static final int GHOSTTY_MODS_CTRL = 2;
    private static final int GHOSTTY_MODS_ALT = 4;
    private static final int GHOSTTY_MODS_SUPER = 8;
    private static final int GHOSTTY_MODS_CAPS_LOCK = 16;
    private static final int GHOSTTY_MODS_NUM_LOCK = 32;
    private static final int GHOSTTY_MODS_SHIFT_SIDE = 64;
    private static final int GHOSTTY_MODS_CTRL_SIDE = 128;
    private static final int GHOSTTY_MODS_ALT_SIDE = 256;
    private static final int GHOSTTY_MODS_SUPER_SIDE = 512;

    private static final AddressLayout C_POINTER = ValueLayout.ADDRESS;
    private static final MemoryLayout MOUSE_POSITION_LAYOUT = MemoryLayout.structLayout(
        ValueLayout.JAVA_FLOAT.withName("x"),
        ValueLayout.JAVA_FLOAT.withName("y")
    );
    private static final MemoryLayout MOUSE_ENCODER_SIZE_LAYOUT = MemoryLayout.structLayout(
        NativeRuntime.SIZE_T_LAYOUT.withName("size"),
        ValueLayout.JAVA_INT.withName("screen_width"),
        ValueLayout.JAVA_INT.withName("screen_height"),
        ValueLayout.JAVA_INT.withName("cell_width"),
        ValueLayout.JAVA_INT.withName("cell_height"),
        ValueLayout.JAVA_INT.withName("padding_top"),
        ValueLayout.JAVA_INT.withName("padding_bottom"),
        ValueLayout.JAVA_INT.withName("padding_right"),
        ValueLayout.JAVA_INT.withName("padding_left")
    );
    private final MethodHandle ghosttyMouseEncoderNew;
    private final MethodHandle ghosttyMouseEncoderFree;
    private final MethodHandle ghosttyMouseEncoderSetopt;
    private final MethodHandle ghosttyMouseEncoderReset;
    private final MethodHandle ghosttyMouseEncoderEncode;
    private final MethodHandle ghosttyMouseEventNew;
    private final MethodHandle ghosttyMouseEventFree;
    private final MethodHandle ghosttyMouseEventSetAction;
    private final MethodHandle ghosttyMouseEventSetButton;
    private final MethodHandle ghosttyMouseEventSetMods;
    private final MethodHandle ghosttyMouseEventSetPosition;

    NativeMouseCodec(SymbolLookup lookup) {
        ghosttyMouseEncoderNew = NativeRuntime.bind(lookup, "ghostty_mouse_encoder_new", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER
        ));
        ghosttyMouseEncoderFree = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_encoder_free",
            FunctionDescriptor.ofVoid(C_POINTER)
        );
        ghosttyMouseEncoderSetopt = NativeRuntime.bind(lookup, "ghostty_mouse_encoder_setopt", FunctionDescriptor.ofVoid(
            C_POINTER,
            ValueLayout.JAVA_INT,
            C_POINTER
        ));
        ghosttyMouseEncoderReset = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_encoder_reset",
            FunctionDescriptor.ofVoid(C_POINTER)
        );
        ghosttyMouseEncoderEncode = NativeRuntime.bind(lookup, "ghostty_mouse_encoder_encode", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER,
            C_POINTER,
            NativeRuntime.SIZE_T_LAYOUT,
            C_POINTER
        ));
        ghosttyMouseEventNew = NativeRuntime.bind(lookup, "ghostty_mouse_event_new", FunctionDescriptor.of(
            ValueLayout.JAVA_INT,
            C_POINTER,
            C_POINTER
        ));
        ghosttyMouseEventFree = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_event_free",
            FunctionDescriptor.ofVoid(C_POINTER)
        );
        ghosttyMouseEventSetAction = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_event_set_action",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_INT)
        );
        ghosttyMouseEventSetButton = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_event_set_button",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_INT)
        );
        ghosttyMouseEventSetMods = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_event_set_mods",
            FunctionDescriptor.ofVoid(C_POINTER, ValueLayout.JAVA_SHORT)
        );
        ghosttyMouseEventSetPosition = NativeRuntime.bind(
            lookup,
            "ghostty_mouse_event_set_position",
            FunctionDescriptor.ofVoid(C_POINTER, MOUSE_POSITION_LAYOUT)
        );
    }

    public MouseCodec mouseCodec(MouseCodecConfig config) {
        try (var arena = Arena.ofConfined()) {
            var encoderOut = arena.allocate(ValueLayout.ADDRESS);
            NativeRuntime.invokeStatus(
                ghosttyMouseEncoderNew,
                "ghostty_mouse_encoder_new",
                MemorySegment.NULL,
                encoderOut
            );
            var handleAddress = encoderOut.get(ValueLayout.ADDRESS, 0).address();
            return new NativeMouseCodecInstance(this, handleAddress);
        }
    }

    private void setMouseEncoderEnumOption(Arena arena, MemorySegment encoder, int option, int value) {
        var segment = arena.allocate(ValueLayout.JAVA_INT);
        segment.set(ValueLayout.JAVA_INT, 0, value);
        NativeRuntime.invoke(ghosttyMouseEncoderSetopt, encoder, option, segment);
    }

    private void setMouseEncoderBooleanOption(Arena arena, MemorySegment encoder, int option, boolean value) {
        var segment = arena.allocate(ValueLayout.JAVA_BOOLEAN);
        segment.set(ValueLayout.JAVA_BOOLEAN, 0, value);
        NativeRuntime.invoke(ghosttyMouseEncoderSetopt, encoder, option, segment);
    }

    private void setMouseEncoderSizeOption(Arena arena, MemorySegment encoder, MouseEncoderSize size) {
        var segment = arena.allocate(MOUSE_ENCODER_SIZE_LAYOUT);
        segment.set(
            NativeRuntime.SIZE_T_LAYOUT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("size")),
            MOUSE_ENCODER_SIZE_LAYOUT.byteSize()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("screen_width")),
            size.screenWidth()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("screen_height")),
            size.screenHeight()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cell_width")),
            size.cellWidth()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("cell_height")),
            size.cellHeight()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("padding_top")),
            size.paddingTop()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("padding_bottom")),
            size.paddingBottom()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("padding_right")),
            size.paddingRight()
        );
        segment.set(
            ValueLayout.JAVA_INT,
            MOUSE_ENCODER_SIZE_LAYOUT.byteOffset(MemoryLayout.PathElement.groupElement("padding_left")),
            size.paddingLeft()
        );
        NativeRuntime.invoke(ghosttyMouseEncoderSetopt, encoder, GHOSTTY_MOUSE_ENCODER_OPT_SIZE, segment);
    }

    private void populateMouseEvent(Arena arena, MemorySegment mouseEvent, MouseEvent event) {
        NativeRuntime.invoke(ghosttyMouseEventSetAction, mouseEvent, event.action().ordinal());
        if (event.button() != null) {
            NativeRuntime.invoke(ghosttyMouseEventSetButton, mouseEvent, event.button().ordinal());
        }
        NativeRuntime.invoke(ghosttyMouseEventSetMods, mouseEvent, toGhosttyMods(event.modifiers()));

        var position = arena.allocate(MOUSE_POSITION_LAYOUT);
        position.set(ValueLayout.JAVA_FLOAT, 0, event.position().x());
        position.set(ValueLayout.JAVA_FLOAT, Float.BYTES, event.position().y());
        NativeRuntime.invoke(ghosttyMouseEventSetPosition, mouseEvent, position);
    }

    private static short toGhosttyMods(KeyModifiers modifiers) {
        if (modifiers == null) {
            return 0;
        }

        var mods = 0;
        if (modifiers.shift()) {
            mods |= GHOSTTY_MODS_SHIFT;
            if (modifiers.shiftSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_SHIFT_SIDE;
            }
        }
        if (modifiers.ctrl()) {
            mods |= GHOSTTY_MODS_CTRL;
            if (modifiers.ctrlSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_CTRL_SIDE;
            }
        }
        if (modifiers.alt()) {
            mods |= GHOSTTY_MODS_ALT;
            if (modifiers.altSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_ALT_SIDE;
            }
        }
        if (modifiers.superKey()) {
            mods |= GHOSTTY_MODS_SUPER;
            if (modifiers.superSide() == ModifierSide.RIGHT) {
                mods |= GHOSTTY_MODS_SUPER_SIDE;
            }
        }
        if (modifiers.capsLock()) {
            mods |= GHOSTTY_MODS_CAPS_LOCK;
        }
        if (modifiers.numLock()) {
            mods |= GHOSTTY_MODS_NUM_LOCK;
        }
        return (short) mods;
    }

    private record NativeMouseEvent(NativeMouseCodec codec, MemorySegment handle) implements AutoCloseable {
        static NativeMouseEvent create(NativeMouseCodec codec, Arena arena) {
            var eventOut = arena.allocate(ValueLayout.ADDRESS);
            NativeRuntime.invokeStatus(
                codec.ghosttyMouseEventNew,
                "ghostty_mouse_event_new",
                MemorySegment.NULL,
                eventOut
            );
            return new NativeMouseEvent(codec, eventOut.get(ValueLayout.ADDRESS, 0));
        }

        @Override
        public void close() {
            NativeRuntime.invoke(codec.ghosttyMouseEventFree, handle);
        }
    }

    private static final class NativeMouseCodecInstance implements MouseCodec {
        private final NativeMouseCodec codec;
        private final long handleAddress;
        private MouseEncodeContext appliedContext;

        private NativeMouseCodecInstance(NativeMouseCodec codec, long handleAddress) {
            this.codec = codec;
            this.handleAddress = handleAddress;
            NativeRuntime.CLEANER.register(
                this,
                () -> NativeRuntime.invoke(codec.ghosttyMouseEncoderFree, MemorySegment.ofAddress(handleAddress))
            );
        }

        @Override
        public synchronized void reset() {
            NativeRuntime.invoke(codec.ghosttyMouseEncoderReset, MemorySegment.ofAddress(handleAddress));
        }

        @Override
        public synchronized byte[] encode(MouseEvent event, MouseEncodeContext context) {
            Objects.requireNonNull(event, "event");
            Objects.requireNonNull(context, "context");

            try (var arena = Arena.ofConfined();
                 var mouseEvent = NativeMouseEvent.create(codec, arena)) {
                var encoder = MemorySegment.ofAddress(handleAddress);
                configureEncoder(arena, encoder, context);
                codec.populateMouseEvent(arena, mouseEvent.handle(), event);

                var outLen = arena.allocate(NativeRuntime.SIZE_T_LAYOUT);
                var out = arena.allocate(INITIAL_OUTPUT_CAPACITY);
                try {
                    NativeRuntime.invokeStatus(
                        codec.ghosttyMouseEncoderEncode,
                        "ghostty_mouse_encoder_encode",
                        encoder,
                        mouseEvent.handle(),
                        out,
                        INITIAL_OUTPUT_CAPACITY,
                        outLen
                    );
                    var written = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
                    if (written == 0) {
                        return new byte[0];
                    }
                    return out.asSlice(0, written).toArray(ValueLayout.JAVA_BYTE);
                } catch (ResultException exception) {
                    if (exception.result != NativeRuntime.GHOSTTY_OUT_OF_SPACE) {
                        throw exception;
                    }
                }

                var required = outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0);
                if (required == 0) {
                    return new byte[0];
                }

                out = arena.allocate(required);
                NativeRuntime.invokeStatus(
                    codec.ghosttyMouseEncoderEncode,
                    "ghostty_mouse_encoder_encode",
                    encoder,
                    mouseEvent.handle(),
                    out,
                    required,
                    outLen
                );
                return out.asSlice(0, outLen.get(NativeRuntime.SIZE_T_LAYOUT, 0)).toArray(ValueLayout.JAVA_BYTE);
            }
        }

        private void configureEncoder(Arena arena, MemorySegment encoder, MouseEncodeContext context) {
            if (appliedContext == null || appliedContext.trackingMode() != context.trackingMode()) {
                codec.setMouseEncoderEnumOption(
                    arena,
                    encoder,
                    GHOSTTY_MOUSE_ENCODER_OPT_EVENT,
                    context.trackingMode().ordinal()
                );
            }
            if (appliedContext == null || appliedContext.format() != context.format()) {
                codec.setMouseEncoderEnumOption(
                    arena,
                    encoder,
                    GHOSTTY_MOUSE_ENCODER_OPT_FORMAT,
                    context.format().ordinal()
                );
            }
            if (appliedContext == null || !appliedContext.size().equals(context.size())) {
                codec.setMouseEncoderSizeOption(arena, encoder, context.size());
            }
            if (appliedContext == null || appliedContext.anyButtonPressed() != context.anyButtonPressed()) {
                codec.setMouseEncoderBooleanOption(
                    arena,
                    encoder,
                    GHOSTTY_MOUSE_ENCODER_OPT_ANY_BUTTON_PRESSED,
                    context.anyButtonPressed()
                );
            }
            if (appliedContext == null || appliedContext.trackLastCell() != context.trackLastCell()) {
                codec.setMouseEncoderBooleanOption(
                    arena,
                    encoder,
                    GHOSTTY_MOUSE_ENCODER_OPT_TRACK_LAST_CELL,
                    context.trackLastCell()
                );
            }
            appliedContext = context;
        }
    }
}
