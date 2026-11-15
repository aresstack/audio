package com.aresstack.audio.studio.ui;

import org.junit.Assert;
import org.junit.Test;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Point;

/** The lamp in front of a module's name glows only while the pointer is over that module. */
public class ModuleLampTest {
    @Test
    public void theLampIsDarkUntilThePointerIsOverItsModule() {
        DeckWidgets.Module module = new DeckWidgets.Module("Transport", new JLabel("contents"));
        Assert.assertFalse("a module nobody points at has its lamp off", module.lit());
        // Not on the screen: wherever the pointer is said to be, it is not over this module.
        module.setSize(200, 80);
        module.pointerAt(module, new Point(10, 10));
        Assert.assertFalse(module.lit());
        module.pointerAt(null, null);
        Assert.assertFalse(module.lit());
        module.pointerAt(new JPanel(), new Point(10, 10));
        Assert.assertFalse("a pointer over another window is not over this module", module.lit());
    }
}
