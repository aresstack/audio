package com.aresstack.audio.studio.ui;

import com.formdev.flatlaf.FlatLightLaf;
import java.awt.Color;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.UIManager;

/**
 * Translate 3d4j's rounded cards and spacious navigation to Swing.
 * Use deep navy and muted teal instead of the game's pink palette.
 */
public final class AudioStudioTheme {
    private AudioStudioTheme() {
    }

    public static void install() {
        FlatLightLaf.setup();

        Color background = new Color(0xF4F7F9);
        Color surface = new Color(0xFFFFFF);
        Color darkInk = new Color(0x17344B);
        Color mutedInk = new Color(0x526D7C);
        Color teal = new Color(0x187F83);
        Color tealLight = new Color(0xE7F3F2);
        Color stroke = new Color(0xD7E3E8);

        UIManager.put("Panel.background", background);
        UIManager.put("TabbedPane.background", background);
        UIManager.put("TabbedPane.selectedBackground", surface);
        UIManager.put("TabbedPane.underlineColor", teal);
        UIManager.put("TabbedPane.selectedForeground", darkInk);
        UIManager.put("TabbedPane.foreground", mutedInk);
        UIManager.put("TabbedPane.tabHeight", 42);
        UIManager.put("TabbedPane.tabInsets", new java.awt.Insets(8, 20, 8, 20));
        UIManager.put("TabbedPane.showTabSeparators", false);

        UIManager.put("Table.background", surface);
        UIManager.put("Table.selectionBackground", tealLight);
        UIManager.put("Table.selectionForeground", darkInk);
        UIManager.put("Table.gridColor", stroke);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);

        UIManager.put("Component.arc", 14);
        UIManager.put("Button.arc", 16);
        UIManager.put("TextComponent.arc", 12);
        UIManager.put("ScrollBar.thumbArc", 10);
        UIManager.put("Component.focusColor", tealLight);
        UIManager.put("Component.borderColor", stroke);
        UIManager.put("Button.default.background", teal);
        UIManager.put("Button.default.foreground", Color.WHITE);
        UIManager.put("Button.hoverBackground", tealLight);
        UIManager.put("Button.focusedBorderColor", teal);
        UIManager.put("Label.foreground", darkInk);
        UIManager.put("TextField.background", surface);
        UIManager.put("TextArea.background", surface);
        UIManager.put("ComboBox.background", surface);
        UIManager.put("ScrollPane.background", background);
        UIManager.put("ProgressBar.foreground", teal);
        UIManager.put("Separator.foreground", stroke);
        UIManager.put("TitledBorder.titleColor", darkInk);
        UIManager.put("defaultFont", new Font("Dialog", Font.PLAIN, 13));
    }

    public static javax.swing.border.Border cardBorder() {
        return BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0xD7E3E8), 1, true),
                BorderFactory.createEmptyBorder(14, 16, 14, 16));
    }
}
