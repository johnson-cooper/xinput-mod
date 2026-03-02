package com.attackishere.xinput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import net.minecraft.client.gui.ScaledResolution;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class GuiControllerSettings extends GuiScreen {

    private final GuiScreen parentScreen;
    private final XInputConfig config;

    private ControllerAction listeningAction = null;
    private long listeningStart = 0;
    private static final long LISTEN_TIMEOUT_MS = 5000;

    // Scrolling
    private int scrollOffset = 0;
    private final int rowHeight   = 24;
    private final int viewTop     = 130;
    private final int viewBottom  = 35;

    private static final int BTN_DONE      = 0;
    private static final int BTN_TOGGLE    = 1;
    private static final int BTN_REMAP_BASE = 100;

    private int draggingSlider = -1;

    // Cached button list field   resolved once, works in obfuscated jars
    private Field cachedButtonListField = null;
    private boolean buttonListFieldSearched = false;

    public GuiControllerSettings(GuiScreen parent, XInputConfig config) {
        this.parentScreen = parent;
        this.config = config;
    }

    // =========================================================================
    // Obfuscation-safe button list access
    // =========================================================================

    /**
     * Finds the button list field by:
     * 1. Trying known MCP names ("buttonList", "controlList") up the hierarchy.
     * 2. Scanning every field in the hierarchy for a List that holds GuiButtons.
     *
     * Result is cached so reflection only runs once per screen instance.
     */
    @SuppressWarnings("unchecked")
    private List<GuiButton> getInternalButtonList() {
        // Resolve the field once
        if (!buttonListFieldSearched) {
            buttonListFieldSearched = true;

            // Pass 1   known MCP names
            outer:
            for (String name : new String[]{"buttonList", "controlList"}) {
                Class<?> cur = getClass();
                while (cur != null) {
                    try {
                        Field f = cur.getDeclaredField(name);
                        f.setAccessible(true);
                        cachedButtonListField = f;
                        break outer;
                    } catch (NoSuchFieldException ignored) {}
                    cur = cur.getSuperclass();
                }
            }

            // Pass 2   obfuscated fallback: first List field containing GuiButtons
            if (cachedButtonListField == null) {
                Class<?> cur = getClass();
                while (cur != null && cachedButtonListField == null) {
                    for (Field f : cur.getDeclaredFields()) {
                        if (!List.class.isAssignableFrom(f.getType())) continue;
                        f.setAccessible(true);
                        try {
                            Object val = f.get(this);
                            if (val == null) continue;
                            List<?> list = (List<?>) val;
                            // Accept empty lists too   they'll be populated by initGui
                            if (list.isEmpty() || list.get(0) instanceof GuiButton) {
                                cachedButtonListField = f;
                                System.out.println("[XInputMod] GuiControllerSettings: found button list field '"
                                    + f.getName() + "' in " + cur.getSimpleName());
                                break;
                            }
                        } catch (Throwable ignored) {}
                    }
                    cur = cur.getSuperclass();
                }
            }

            if (cachedButtonListField == null) {
                System.out.println("[XInputMod] GuiControllerSettings: FAILED to find button list field!");
            }
        }

        if (cachedButtonListField == null) return new ArrayList<GuiButton>(); // safe empty fallback
        try {
            Object val = cachedButtonListField.get(this);
            if (val instanceof List) return (List<GuiButton>) val;
        } catch (Throwable t) {
            System.out.println("[XInputMod] getInternalButtonList get() failed: " + t);
        }
        return new ArrayList<GuiButton>();
    }

    // =========================================================================
    // GuiScreen lifecycle
    // =========================================================================

    @Override
    public void initGui() {
        List<GuiButton> buttons = getInternalButtonList();
        buttons.clear();

        int cx   = width / 2;
        int yDone = height - 28;

        buttons.add(new GuiButton(BTN_DONE,   cx - 75,  yDone, 150, 20,
            "Done"));
        buttons.add(new GuiButton(BTN_TOGGLE, cx - 155, 32,   150, 20,
            "Controller: " + (config.enableController ? "ON" : "OFF")));

        for (ControllerAction action : ControllerAction.values()) {
            buttons.add(new GuiButton(BTN_REMAP_BASE + action.ordinal(), cx + 5, 0, 145, 20, ""));
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        int cx = width / 2;
        List<GuiButton> buttons = getInternalButtonList();

        // Mouse wheel scrolling
        int dWheel = Mouse.getDWheel();
        if (dWheel != 0) scrollOffset -= (dWheel > 0) ? 20 : -20;

        int listHeight = ControllerAction.values().length * rowHeight;
        int viewHeight = height - viewTop - viewBottom;
        int maxScroll  = Math.max(0, listHeight - viewHeight + 10);
        scrollOffset   = Math.max(0, Math.min(scrollOffset, maxScroll));

        // Static header
        drawCenteredString(fontRenderer, "Controller Settings", cx, 8, 0xFFFFFF);
        int sliderTop = 60;
        drawSliderRow(cx, sliderTop,                "Look Speed X", config.lookSpeedX,        1);
        drawSliderRow(cx, sliderTop + rowHeight,    "Look Speed Y", config.lookSpeedY,        2);
        drawSliderRow(cx, sliderTop + rowHeight * 2,"Deadzone",     config.deadzone / 0.5f,   3);

        // Scissor clipping for scroll area
        ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int factor   = sr.getScaleFactor();
        int scissorY = viewBottom * factor;
        int scissorH = (height - viewTop - viewBottom) * factor;

        GL11.glEnable(GL11.GL_SCISSOR_TEST);
        GL11.glScissor(0, scissorY, mc.displayWidth, scissorH);

        // Scrollable remap rows
        for (GuiButton btn : buttons) {
            if (btn.id < BTN_REMAP_BASE) continue;
            ControllerAction action = ControllerAction.values()[btn.id - BTN_REMAP_BASE];
            int btnY = viewTop + (action.ordinal() * rowHeight) - scrollOffset;
            btn.yPosition     = btnY;
            btn.displayString = remapLabel(action);
            if (btnY + rowHeight > viewTop && btnY < height - viewBottom) {
                btn.drawButton = true;
                drawString(fontRenderer, action.displayName, cx - 155, btnY + 5, 0xFFFFFF);
                btn.drawButton(mc, mouseX, mouseY);
            } else {
                btn.drawButton = false;
            }
        }

        GL11.glDisable(GL11.GL_SCISSOR_TEST);

        // Footer (Done + Toggle   always visible)
        for (GuiButton btn : buttons) {
            if (btn.id < BTN_REMAP_BASE) btn.drawButton(mc, mouseX, mouseY);
        }

        // Listening timeout
        if (listeningAction != null
                && System.currentTimeMillis() - listeningStart >= LISTEN_TIMEOUT_MS) {
            listeningAction = null;
        }
    }

    // =========================================================================
    // Input
    // =========================================================================

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
            listeningStart  = System.currentTimeMillis();
        }
    }

    /** Called by XInputTickHandler when a controller button is pressed during listening mode. */
    public boolean onControllerButton(int buttonIndex) {
        if (listeningAction == null) return false;
        config.setBinding(listeningAction, buttonIndex);
        config.save();
        listeningAction = null;
        return true;
    }

    public void scrollWithController(int direction) {
        scrollOffset += direction * 20;
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

    // =========================================================================
    // Slider helpers
    // =========================================================================

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
        String label = sliderId == 3
            ? String.format("%.2f", config.deadzone)
            : String.format("%.1f", (sliderId == 1 ? config.lookSpeedX : config.lookSpeedY) * 20f);
        drawString(fontRenderer, label, x + w + 4, y + 1, 0xCCCCCC);
    }

    private int hitSlider(int mx, int my, int sliderTop) {
        int x = width / 2 - 155;
        if (mx < x || mx > x + 150) return -1;
        if (my >= sliderTop + 10            && my <= sliderTop + 20)            return 1;
        if (my >= sliderTop + rowHeight + 10 && my <= sliderTop + rowHeight + 20) return 2;
        if (my >= sliderTop + rowHeight*2+10 && my <= sliderTop + rowHeight*2+20) return 3;
        return -1;
    }

    private void updateSlider(int id, int mouseX, int sliderTop) {
        float t = Math.max(0f, Math.min(1f, (mouseX - (width / 2 - 155)) / 150f));
        if      (id == 1) config.lookSpeedX = t;
        else if (id == 2) config.lookSpeedY = t;
        else if (id == 3) config.deadzone   = t * 0.5f;
    }

    private String remapLabel(ControllerAction action) {
        if (listeningAction == action) return "> Press a button... <";
        int bound = config.getBinding(action);
        // Friendly names for sentinel bindings
        if (bound == XInputTickHandler.BIND_LT_SENTINEL)  return "LT (Trigger)";
        if (bound == XInputTickHandler.BIND_RT_SENTINEL)  return "RT (Trigger)";
        if (bound == XInputTickHandler.BIND_DPAD_UP)      return "DPad Up";
        if (bound == XInputTickHandler.BIND_DPAD_DOWN)    return "DPad Down";
        if (bound == XInputTickHandler.BIND_DPAD_LEFT)    return "DPad Left";
        if (bound == XInputTickHandler.BIND_DPAD_RIGHT)   return "DPad Right";
        if (bound < 0) return "[ default ]";
        return "Button " + bound;
    }

    @Override
    public boolean doesGuiPauseGame() { return false; }
}