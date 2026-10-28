package com.animeshelf.app;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Dark surfaces with readable accents; values are ARGB colors and need no Android API. */
public final class ThemePalette {
    public static final String DEFAULT_ID = "blue";
    public final String id, name;
    public final int background, panel, panelLight, text, muted, accent, secondary, onAccent;

    private ThemePalette(String id, String name, int accent, int secondary) {
        this.id = id;
        this.name = name;
        this.background = 0xFF0E111B;
        this.panel = 0xFF191D2C;
        this.panelLight = 0xFF24293B;
        this.text = 0xFFF2F3FB;
        this.muted = 0xFF9EA8BE;
        this.accent = accent;
        this.secondary = secondary;
        this.onAccent = 0xFF0E111B;
    }

    private static final List<ThemePalette> ENTRIES = Collections.unmodifiableList(Arrays.asList(
        new ThemePalette("blue", "蓝色", 0xFF8DB7FF, 0xFF9DC6FF),
        new ThemePalette("purple", "紫色", 0xFFAF99FF, 0xFFC6B3FF),
        new ThemePalette("teal", "青色", 0xFF77E0D2, 0xFF9DEADF),
        new ThemePalette("green", "绿色", 0xFF8FD69D, 0xFFB4E6BD),
        new ThemePalette("orange", "橙色", 0xFFFFBB80, 0xFFFFD3AD),
        new ThemePalette("pink", "粉色", 0xFFF5A6CE, 0xFFFFD0E6)
    ));

    public static List<ThemePalette> entries() { return ENTRIES; }

    /** Old, missing or unrecognized preferences use the default blue theme. */
    public static ThemePalette get(String id) {
        String wanted = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
        for (ThemePalette palette : ENTRIES) if (palette.id.equals(wanted)) return palette;
        return ENTRIES.get(0);
    }
}
