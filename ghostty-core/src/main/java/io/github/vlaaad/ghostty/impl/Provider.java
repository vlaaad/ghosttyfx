package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.FocusCodec;
import io.github.vlaaad.ghostty.KeyCodec;
import io.github.vlaaad.ghostty.KeyCodecConfig;
import io.github.vlaaad.ghostty.MouseCodec;
import io.github.vlaaad.ghostty.MouseCodecConfig;
import io.github.vlaaad.ghostty.PasteCodec;
import io.github.vlaaad.ghostty.PtyWriter;
import io.github.vlaaad.ghostty.SizeReportCodec;
import io.github.vlaaad.ghostty.TerminalConfig;
import io.github.vlaaad.ghostty.TerminalEvents;
import io.github.vlaaad.ghostty.TerminalQueries;
import io.github.vlaaad.ghostty.TerminalSession;
import io.github.vlaaad.ghostty.TypeSchema;

public interface Provider {
    String id();

    TerminalSession open(TerminalConfig config, PtyWriter ptyWriter, TerminalQueries queries, TerminalEvents events);

    KeyCodec keyCodec(KeyCodecConfig config);

    MouseCodec mouseCodec(MouseCodecConfig config);

    PasteCodec pasteCodec();

    FocusCodec focusCodec();

    SizeReportCodec sizeReportCodec();

    BuildInfo buildInfo();

    TypeSchema typeSchema();
}
