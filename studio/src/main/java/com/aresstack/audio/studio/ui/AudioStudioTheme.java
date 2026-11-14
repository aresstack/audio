package com.aresstack.audio.studio.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.border.Border;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * The look of the studio: a dark mixing desk. Modules with rounded corners sit on
 * a near-black surface, signals glow in the pink of 3d4j's menus, and what a real
 * desk shows with lamps and displays is shown with lamps and displays here.
 */
public final class AudioStudioTheme {
    /** The desk itself, behind the modules. */
    public static final Color DESK = new Color(0x121318);
    /** A module on the desk. */
    public static final Color MODULE = new Color(0x1D1F26);
    /** What is set into a module: fields, displays, wells. */
    public static final Color WELL = new Color(0x14151B);
    public static final Color RAISED = new Color(0x2A2D37);
    public static final Color STROKE = new Color(0x343846);
    public static final Color TEXT = new Color(0xECEAF2);
    public static final Color TEXT_DIM = new Color(0x9C9AAC);
    public static final Color TEXT_FAINT = new Color(0x6A6878);
    /** The signal colour: the pink of a chosen button in 3d4j. */
    public static final Color ACCENT = new Color(0xE0649F);
    public static final Color ACCENT_SOFT = new Color(0xF29BC4);
    public static final Color GREEN = new Color(0x5FE08C);
    public static final Color AMBER = new Color(0xF4BC4E);
    public static final Color RED = new Color(0xF2596D);
    public static final Color CYAN = new Color(0x5FC9F2);
    public static final Color LCD_GLASS = new Color(0x0C1411);
    public static final Color LCD_TEXT = new Color(0x86F2B6);
    public static final Color LCD_DIM = new Color(0x1E3A2C);

    /** One colour per channel of the mixer, in turn. */
    private static final Color[] CHANNELS = {
            new Color(0xE0649F), new Color(0x5FC9F2), new Color(0xF4BC4E), new Color(0x5FE08C),
            new Color(0xB48CF2), new Color(0xF28C5F), new Color(0x5FE0D2), new Color(0xF2E15F)};

    private AudioStudioTheme() {
    }

    public static Color channel(int index) {
        return CHANNELS[Math.floorMod(index, CHANNELS.length)];
    }

    public static Color withAlpha(Color color, int alpha) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha);
    }

    /** Small capitals with wide spacing, as a desk is lettered. */
    public static Font lettering(float size) {
        java.util.Map<java.awt.font.TextAttribute, Object> attributes =
                new java.util.HashMap<java.awt.font.TextAttribute, Object>();
        attributes.put(java.awt.font.TextAttribute.TRACKING, 0.12f);
        return UIManager.getFont("defaultFont").deriveFont(Font.BOLD, size).deriveFont(attributes);
    }

    public static Font display(float size) {
        return new Font(Font.MONOSPACED, Font.BOLD, Math.round(size));
    }

    public static void install() {
        FlatDarkLaf.setup();

        UIManager.put("defaultFont", new Font("Dialog", Font.PLAIN, 13));
        UIManager.put("Panel.background", DESK);
        UIManager.put("Viewport.background", MODULE);
        UIManager.put("ScrollPane.background", MODULE);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.disabledForeground", TEXT_FAINT);
        UIManager.put("ToolTip.background", RAISED);
        UIManager.put("ToolTip.foreground", TEXT);

        UIManager.put("Component.arc", 12);
        UIManager.put("Button.arc", 14);
        UIManager.put("TextComponent.arc", 10);
        UIManager.put("CheckBox.arc", 6);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.focusColor", withAlpha(ACCENT, 150));
        UIManager.put("Component.focusedBorderColor", ACCENT);
        UIManager.put("Component.borderColor", STROKE);
        UIManager.put("Component.disabledBorderColor", new Color(0x282B34));

        UIManager.put("Button.background", RAISED);
        UIManager.put("Button.foreground", TEXT);
        UIManager.put("Button.borderColor", STROKE);
        UIManager.put("Button.hoverBackground", new Color(0x353945));
        UIManager.put("Button.pressedBackground", new Color(0x40303C));
        UIManager.put("Button.focusedBorderColor", ACCENT);
        UIManager.put("Button.hoverBorderColor", ACCENT_SOFT);
        UIManager.put("Button.disabledBackground", new Color(0x1F2128));
        UIManager.put("Button.disabledText", TEXT_FAINT);
        UIManager.put("Button.default.background", ACCENT);
        UIManager.put("Button.default.foreground", Color.WHITE);
        UIManager.put("Button.default.hoverBackground", new Color(0xEA78AE));
        UIManager.put("Button.default.pressedBackground", new Color(0xC64F88));
        UIManager.put("Button.default.borderColor", ACCENT);
        UIManager.put("Button.default.focusedBorderColor", ACCENT_SOFT);
        UIManager.put("Button.default.boldText", true);
        UIManager.put("Button.margin", new Insets(6, 14, 6, 14));

        for (String field : new String[]{"TextField", "PasswordField", "TextArea", "FormattedTextField",
                "ComboBox", "Spinner", "EditorPane", "TextPane"}) {
            UIManager.put(field + ".background", WELL);
            UIManager.put(field + ".foreground", TEXT);
            UIManager.put(field + ".disabledBackground", new Color(0x1A1B21));
            UIManager.put(field + ".inactiveForeground", TEXT_FAINT);
        }
        UIManager.put("TextField.caretForeground", ACCENT_SOFT);
        UIManager.put("PasswordField.caretForeground", ACCENT_SOFT);
        UIManager.put("TextArea.caretForeground", ACCENT_SOFT);
        UIManager.put("TextComponent.selectionBackground", withAlpha(ACCENT, 140));
        UIManager.put("TextComponent.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.buttonBackground", WELL);
        UIManager.put("ComboBox.buttonArrowColor", TEXT_DIM);
        UIManager.put("ComboBox.buttonHoverArrowColor", ACCENT_SOFT);
        UIManager.put("ComboBox.selectionBackground", ACCENT);
        UIManager.put("ComboBox.selectionForeground", Color.WHITE);
        UIManager.put("ComboBox.popupBackground", RAISED);
        UIManager.put("List.background", RAISED);
        UIManager.put("List.foreground", TEXT);
        UIManager.put("List.selectionBackground", ACCENT);
        UIManager.put("List.selectionForeground", Color.WHITE);
        UIManager.put("PopupMenu.background", RAISED);
        UIManager.put("Spinner.buttonBackground", WELL);
        UIManager.put("Spinner.buttonArrowColor", TEXT_DIM);

        UIManager.put("ToggleButton.background", RAISED);
        UIManager.put("ToggleButton.foreground", TEXT_DIM);
        UIManager.put("ToggleButton.hoverBackground", new Color(0x353945));
        UIManager.put("ToggleButton.selectedBackground", new Color(0x5A2C46));
        UIManager.put("ToggleButton.selectedForeground", Color.WHITE);
        UIManager.put("ToggleButton.pressedBackground", new Color(0x40303C));
        UIManager.put("ToggleButton.arc", 14);
        UIManager.put("ToggleButton.margin", new Insets(6, 10, 6, 10));
        UIManager.put("CheckBox.foreground", TEXT);
        UIManager.put("CheckBox.icon.background", WELL);
        UIManager.put("CheckBox.icon.borderColor", STROKE);
        UIManager.put("CheckBox.icon.selectedBackground", ACCENT);
        UIManager.put("CheckBox.icon.selectedBorderColor", ACCENT);
        UIManager.put("CheckBox.icon.checkmarkColor", Color.WHITE);

        UIManager.put("Table.background", MODULE);
        UIManager.put("Table.foreground", TEXT);
        UIManager.put("Table.alternateRowColor", new Color(0x21232B));
        UIManager.put("Table.selectionBackground", new Color(0x4A2A3D));
        UIManager.put("Table.selectionForeground", Color.WHITE);
        UIManager.put("Table.selectionInactiveBackground", new Color(0x3A2733));
        UIManager.put("Table.gridColor", new Color(0x2A2D37));
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);
        UIManager.put("Table.intercellSpacing", new java.awt.Dimension(0, 1));
        UIManager.put("TableHeader.background", WELL);
        UIManager.put("TableHeader.foreground", TEXT_DIM);
        UIManager.put("TableHeader.separatorColor", STROKE);
        UIManager.put("TableHeader.bottomSeparatorColor", STROKE);
        UIManager.put("TableHeader.height", 32);

        UIManager.put("ScrollBar.track", MODULE);
        UIManager.put("ScrollBar.thumb", new Color(0x3A3E4B));
        UIManager.put("ScrollBar.hoverThumbColor", new Color(0x4C5162));
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 12);
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());

        UIManager.put("ProgressBar.background", WELL);
        UIManager.put("ProgressBar.foreground", ACCENT);
        UIManager.put("ProgressBar.selectionBackground", TEXT);
        UIManager.put("ProgressBar.selectionForeground", Color.WHITE);
        UIManager.put("ProgressBar.arc", 999);
        UIManager.put("Separator.foreground", STROKE);

        UIManager.put("OptionPane.background", MODULE);
        UIManager.put("OptionPane.messageForeground", TEXT);
        UIManager.put("TitlePane.background", DESK);
        UIManager.put("TitlePane.foreground", TEXT_DIM);
        UIManager.put("TitlePane.unifiedBackground", false);
    }

    /** Space inside a module around its contents. */
    public static Border modulePadding() {
        return BorderFactory.createEmptyBorder(12, 14, 14, 14);
    }
}
