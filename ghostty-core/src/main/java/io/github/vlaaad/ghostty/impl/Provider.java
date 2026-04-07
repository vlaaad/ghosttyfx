package io.github.vlaaad.ghostty.impl;

import io.github.vlaaad.ghostty.BuildInfo;
import io.github.vlaaad.ghostty.TypeSchema;

public interface Provider {
    String id();

    BuildInfo buildInfo();

    TypeSchema typeSchema();
}
