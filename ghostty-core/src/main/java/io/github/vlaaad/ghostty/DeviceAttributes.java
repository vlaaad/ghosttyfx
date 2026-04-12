package io.github.vlaaad.ghostty;

import java.util.Objects;
import java.util.Set;

/// Device attributes reported through DA1/DA2/DA3 terminal queries.
///
/// @param primary primary device attributes
/// @param secondary secondary device attributes
/// @param tertiary tertiary device attributes
public record DeviceAttributes(
    DeviceAttributes.Primary primary,
    DeviceAttributes.Secondary secondary,
    DeviceAttributes.Tertiary tertiary
) {
    public DeviceAttributes {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(secondary, "secondary");
        Objects.requireNonNull(tertiary, "tertiary");
    }

    /// Primary device attributes (DA1).
    ///
    /// @param conformanceLevel terminal conformance level (Pp parameter)
    /// @param features supported DA1 feature codes (Ps parameters)
    public record Primary(
        DeviceAttributes.Primary.ConformanceLevel conformanceLevel,
        Set<DeviceAttributes.Primary.Feature> features
    ) {
        public Primary {
            Objects.requireNonNull(conformanceLevel, "conformanceLevel");
            features = Set.copyOf(features);
        }

        /// DA1 conformance level.
        public enum ConformanceLevel {
            LEVEL_1(1),
            VT132(4),
            VT102(6),
            VT131(7),
            VT125(12),
            LEVEL_2(62),
            LEVEL_3(63),
            LEVEL_4(64),
            LEVEL_5(65);

            public final int code;

            ConformanceLevel(int code) {
                this.code = code;
            }
        }

        /// DA1 feature flag.
        public enum Feature {
            COLUMNS_132(1),
            PRINTER(2),
            REGIS(3),
            SIXEL(4),
            SELECTIVE_ERASE(6),
            USER_DEFINED_KEYS(8),
            NATIONAL_REPLACEMENT(9),
            TECHNICAL_CHARACTERS(15),
            LOCATOR(16),
            TERMINAL_STATE(17),
            WINDOWING(18),
            HORIZONTAL_SCROLLING(21),
            ANSI_COLOR(22),
            RECTANGULAR_EDITING(28),
            ANSI_TEXT_LOCATOR(29),
            CLIPBOARD(52);

            public final int code;

            Feature(int code) {
                this.code = code;
            }
        }
    }

    /// Secondary device attributes (DA2).
    ///
    /// @param deviceType terminal type identifier (Pp)
    /// @param firmwareVersion firmware or patch version number (Pv)
    /// @param romCartridge ROM cartridge registration number (Pc)
    public record Secondary(
        DeviceAttributes.Secondary.DeviceType deviceType,
        int firmwareVersion,
        int romCartridge
    ) {
        public Secondary {
            Objects.requireNonNull(deviceType, "deviceType");
            checkU16("firmwareVersion", firmwareVersion);
            checkU16("romCartridge", romCartridge);
        }

        /// DA2 device type.
        public enum DeviceType {
            VT100(0),
            VT220(1),
            VT240(2),
            VT330(18),
            VT340(19),
            VT320(24),
            VT382(32),
            VT420(41),
            VT510(61),
            VT520(64),
            VT525(65);

            public final int code;

            DeviceType(int code) {
                this.code = code;
            }
        }
    }

    /// Tertiary device attributes (DA3).
    ///
    /// @param unitId terminal unit identifier encoded as eight uppercase hex digits
    public record Tertiary(
        long unitId
    ) {
        public Tertiary {
            if (unitId < 0 || unitId > 0xFFFF_FFFFL) {
                throw new IllegalArgumentException("unitId out of range: " + unitId);
            }
        }
    }

    private static void checkU16(String name, int value) {
        if (value < 0 || value > 0xFFFF) {
            throw new IllegalArgumentException(name + " out of range: " + value);
        }
    }
}
