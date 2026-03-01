package com.attackishere.xinput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.ScaledResolution;
import java.lang.reflect.Field;
import java.util.List;

public class GuiControllerSettings extends GuiScreen {

    private final GuiScreen parentScreen;
    private final XInputConfig config;

    private ControllerAction listeningAction = null;
    private long listeningStart = 0;
    private static final long LISTEN_TIMEOUT_MS = 5000;

    // Scrolling Logic
    private int scrollOffset = 0;
    private final int rowHeight = 24;
    private final int viewTop = 130;    // Scroll list start
    private final int viewBottom = 35; // Margin for Done button area

    private static final int BTN_DONE = 0;
    private static final int BTN_TOGGLE = 1;
    private static final int BTN_REMAP_BASE = 100;

    private int draggingSlider = -1;

    public GuiControllerSettings(GuiScreen parent, XInputConfig config) {
        this.parentScreen = parent;
        this.config = config;
    }

    /**
     * SAFELY gets the button list using reflection to avoid the "cannot be resolved" error.
     */
    @SuppressWarnings("unchecked")
    private List<GuiButton> getInternalButtonList() {
        try {
            Field f;
            try {
                // Try standard MCP name
                f = GuiScreen.class.getDeclaredField("buttonList");
            } catch (NoSuchFieldException e) {
                // Try common 1.4.7 alternative name
                f = GuiScreen.class.getDeclaredField("controlList");
            }
            f.setAccessible(true);
            return (List<GuiButton>) f.get(this);
        } catch (Exception e) {
            System.out.println("[XInput] Critical Error: Could not find button list field!");
            return null;
        }
    }

    @Override
    public void initGui() {
        List<GuiButton> buttons = getInternalButtonList();
        if (buttons == null) return;
        
        buttons.clear();

        int cx = width / 2;
        int yDone = height - 28;

        // Static header buttons
        buttons.add(new GuiButton(BTN_DONE, cx - 75, yDone, 150, 20, "Done"));
        buttons.add(new GuiButton(BTN_TOGGLE, cx - 155, 32, 150, 20, 
            "Controller: " + (config.enableController ? "ON" : "OFF")));

        // Add remapping buttons (Positioned later in drawScreen)
        for (ControllerAction action : ControllerAction.values()) {
            int id = BTN_REMAP_BASE + action.ordinal();
            buttons.add(new GuiButton(id, cx + 5, 0, 145, 20, ""));
        }
    }

    
    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = width / 2;
        List<GuiButton> buttons = getInternalButtonList();

        // 1. Mouse Wheel Scrolling
        int dWheel = Mouse.getDWheel();
        if (dWheel != 0) {
            scrollOffset -= (dWheel > 0) ? 20 : -20;
        }
        
        // Dynamic Max Scroll Calculation
        int listHeight = ControllerAction.values().length * rowHeight;
        int viewHeight = height - viewTop - viewBottom;
        int maxScroll = Math.max(0, listHeight - viewHeight + 10); // +10 for padding
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        // 2. Draw Static Header & Sliders
        drawCenteredString(fontRenderer, "Controller Settings", cx, 8, 0xFFFFFF);
        int sliderTop = 60;
        drawSliderRow(cx, sliderTop, "Look Speed X", config.lookSpeedX, 1);
        drawSliderRow(cx, sliderTop + rowHeight, "Look Speed Y", config.lookSpeedY, 2);
        drawSliderRow(cx, sliderTop + rowHeight * 2, "Deadzone", config.deadzone / 0.5f, 3);
        

        // 3. SCISSOR CLIPPING
        ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int factor = sr.getScaleFactor();
        
        // We calculate the Y from the bottom up for OpenGL
        int scissorY = viewBottom * factor;
        int scissorH = (height - viewTop - viewBottom) * factor;

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, scissorY, mc.displayWidth, scissorH);

        // 4. Draw Scrollable Area
        if (buttons != null) {
            for (GuiButton btn : buttons) {
                if (btn.id >= BTN_REMAP_BASE) {
                    ControllerAction action = ControllerAction.values()[btn.id - BTN_REMAP_BASE];
                    int btnY = viewTop + (action.ordinal() * rowHeight) - scrollOffset;

                    btn.yPosition = btnY;
                    btn.displayString = remapLabel(action);
                    
                    // Visibility check: If any part of the button is in the window, draw it
                    if (btnY + rowHeight > viewTop && btnY < height - viewBottom) {
                        btn.drawButton = true;
                        drawString(fontRenderer, action.displayName, cx - 155, btnY + 5, 0xFFFFFF);
                        btn.drawButton(mc, mouseX, mouseY); 
                    } else {
                        btn.drawButton = false;
                    }
                }
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // 5. Draw Footer (Done & Toggle)
        for (GuiButton btn : buttons) {
            if (btn.id < BTN_REMAP_BASE) {
                btn.drawButton(mc, mouseX, mouseY);
            }
        }

        if (listeningAction != null && System.currentTimeMillis() - listeningStart >= LISTEN_TIMEOUT_MS) {
            listeningAction = null;
        }
    }

    /**
     * Call this from your Controller Input Loop to handle scrolling
     * @param direction -1 for up, 1 for down
     */
    public void scrollWithController(int direction) {
        this.scrollOffset += (direction * 20);
    }

    private void drawSliderRow(int cx, int y, String title, float val, int id) {
        drawString(fontRenderer, title, cx - 155, y, 0xAAAAAA);
        drawSlider(cx - 155, y + 10, 150, val, id);
    }

    private void drawSlider(int x, int y, int w, float value, int sliderId) {
        drawRect(x, y + 3, x + w, y + 7, 0xFF555555);
        int fillW = (int)(value * w);
        drawRect(x, y + 3, x + fillW, y + 7, 0xFF44AA44);
        int hx = x + fillW - 3;
        drawRect(hx, y, hx + 6, y + 10, 0xFFFFFFFF);
        
        String label = (sliderId == 3) ? String.format("%.2f", config.deadzone) : 
                       String.format("%.1f", (sliderId == 1 ? config.lookSpeedX : config.lookSpeedY) * 20f);
        drawString(fontRenderer, label, x + w + 4, y + 1, 0xCCCCCC);
    }

    private String remapLabel(ControllerAction action) {
        if (listeningAction == action) return "> Listening... <";
        int bound = config.getBinding(action);
        return bound < 0 ? "[ unbound ]" : "Button " + bound;
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == BTN_DONE) {
            config.save();
            mc.displayGuiScreen(parentScreen);
        } else if (button.id == BTN_TOGGLE) {
            config.enableController = !config.enableController;
            button.displayString = "Controller: " + (config.enableController ? "ON" : "OFF");
        } else if (button.id >= BTN_REMAP_BASE) {
            listeningAction = ControllerAction.values()[button.id - BTN_REMAP_BASE];
            listeningStart = System.currentTimeMillis();
        }
    }

    public boolean onControllerButton(int buttonIndex) {
        if (listeningAction == null) return false;
        config.setBinding(listeningAction, buttonIndex);
        config.save();
        listeningAction = null;
        return true;
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        int sliderTop = 60;
        draggingSlider = hitSlider(mouseX, mouseY, sliderTop);
        if (draggingSlider <= 0) super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void mouseMovedOrUp(int mouseX, int mouseY, int which) {
        if (which == 0 && draggingSlider > 0) updateSlider(draggingSlider, mouseX, 60);
        if (which != 0) draggingSlider = -1;
        super.mouseMovedOrUp(mouseX, mouseY, which);
    }

    private int hitSlider(int mx, int my, int sliderTop) {
        int x = width / 2 - 155;
        if (mx < x || mx > x + 150) return -1;
        if (my >= sliderTop + 10 && my <= sliderTop + 20) return 1;
        if (my >= sliderTop + rowHeight + 10 && my <= sliderTop + rowHeight + 20) return 2;
        if (my >= sliderTop + rowHeight * 2 + 10 && my <= sliderTop + rowHeight * 2 + 20) return 3;
        return -1;
    }

    private void updateSlider(int id, int mouseX, int sliderTop) {
        float t = Math.max(0f, Math.min(1f, (mouseX - (width / 2 - 155)) / 150f));
        if (id == 1) config.lookSpeedX = t;
        else if (id == 2) config.lookSpeedY = t;
        else if (id == 3) config.deadzone = t * 0.5f;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}