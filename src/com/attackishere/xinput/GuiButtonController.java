package com.attackishere.xinput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;

public class GuiButtonController extends GuiButton {
    private final XInputConfig config;

    public GuiButtonController(int id, int x, int y, int width, int height, String text, XInputConfig config) {
        super(id, x, y, width, height, text);
        this.config = config;
    }

    @Override
    public boolean mousePressed(Minecraft mc, int mouseX, int mouseY) {
        if (super.mousePressed(mc, mouseX, mouseY)) {
            mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
            // Navigates to your settings screen
            mc.displayGuiScreen(new GuiControllerSettings(mc.currentScreen, config));
            return true;
        }
        return false;
    }
}