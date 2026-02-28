package com.attackishere.xinput;

import com.github.strikerx3.jxinput.XInputDevice;
import com.github.strikerx3.jxinput.XInputComponents;
import com.github.strikerx3.jxinput.XInputAxes;
import com.github.strikerx3.jxinput.XInputButtons;
import com.github.strikerx3.jxinput.exceptions.XInputNotLoadedException;
import com.github.strikerx3.jxinput.natives.XInputConstants;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.client.gui.inventory.GuiInventory;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;

public class XInputTickHandler implements ITickHandler {

    private XInputDevice controller = null;
    private final Minecraft mc = Minecraft.getMinecraft();
    private final XInputSharedState state;

    //   Tuning    
    private static final float MOVE_DEADZONE       = 0.25f;
    private static final float GUI_CURSOR_DEADZONE = 0.15f;
    private static final float GUI_CURSOR_SPEED    = 12.0f; // scaled-gui-pixels per tick
    private static final int   DEBUG_PRINT_EVERY   = 0;

    //   Previous button states     ─
    private boolean prevA, prevB, prevX, prevY;
    private boolean prevBack, prevStart, prevLB, prevRB;
    private boolean prevLThumb, prevRThumb;
    private boolean prevDpadUp, prevDpadDown, prevDpadLeft, prevDpadRight;

    //   Drag state           ─
    private boolean isDragging   = false;
    private long    aHeldSince   = 0;
    private static final long DRAG_THRESHOLD_MS = 200;

    //   Misc     
    private int dropFrameCounter = 0;
    private int debugCounter     = 0;

    public XInputTickHandler(XInputSharedState state) {
        this.state = state;
    }

    private void log(String s) { System.out.println("[XInputMod] " + s); }

    // =========================================================================
    // Controller init
    // =========================================================================
    private void initController() {
        controller = null;
        if (!XInputDevice.isAvailable()) { log("JXInput not available."); return; }
        try {
            XInputDevice[] devices = XInputDevice.getAllDevices();
            if (devices != null)
                for (XInputDevice dev : devices)
                    if (dev != null && dev.isConnected()) { controller = dev; break; }
            if (controller == null)
                for (int i = 0; i < XInputConstants.MAX_PLAYERS; i++) {
                    try {
                        XInputDevice dev = XInputDevice.getDeviceFor(i);
                        if (dev != null && dev.isConnected()) { controller = dev; break; }
                    } catch (Throwable ignored) {}
                }
        } catch (XInputNotLoadedException e) {
            log("Native library failed: " + e.getMessage());
        } catch (Throwable t) {
            log("initController error: " + t);
        }
        if (controller != null) {
            log("Controller connected: player " + controller.getPlayerNum());
            XInputDevice.setPreProcessData(true);
        } else {
            log("No Xbox controller detected.");
        }
    }

    // =========================================================================
    // Reflection helpers
    // =========================================================================
    private boolean btn(XInputButtons buttons, String name) {
        try { Field f = buttons.getClass().getField(name); f.setAccessible(true); return f.getBoolean(buttons); }
        catch (Throwable t) {
            try { Field f = buttons.getClass().getDeclaredField(name); f.setAccessible(true); return f.getBoolean(buttons); }
            catch (Throwable ignored) { return false; }
        }
    }

    private float axis(XInputAxes axes, String name) {
        try { Field f = axes.getClass().getField(name); f.setAccessible(true); return f.getFloat(axes); }
        catch (Throwable t) {
            try { Field f = axes.getClass().getDeclaredField(name); f.setAccessible(true); return f.getFloat(axes); }
            catch (Throwable ignored) { return 0f; }
        }
    }

    private static float processAxis(float v, float dz) {
        float abs = Math.abs(v);
        if (abs <= dz) return 0f;
        float sign = v < 0 ? -1f : 1f;
        float norm = (abs - dz) / (1f - dz);
        return sign * norm * norm;
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }

    // =========================================================================
    // ITickHandler
    // =========================================================================
    @Override public EnumSet<TickType> ticks()  { return EnumSet.of(TickType.CLIENT); }
    @Override public void tickEnd(EnumSet<TickType> types, Object... tickData) {}
    @Override public String getLabel()          { return "XInputTickHandler"; }

    @Override
    public void tickStart(EnumSet<TickType> types, Object... tickData) {
        if (controller == null) initController();
        if (controller == null) { state.rawRx = 0f; state.rawRy = 0f; return; }

        boolean ok;
        try { ok = controller.poll(); } catch (Throwable t) { ok = false; }
        if (!ok) { controller = null; state.rawRx = 0f; state.rawRy = 0f; return; }

        XInputComponents components;
        try { components = controller.getComponents(); } catch (Throwable t) { return; }
        if (components == null) return;

        XInputAxes    axes    = components.getAxes();
        XInputButtons buttons = components.getButtons();
        if (axes == null || buttons == null) return;

        state.rawRx = axes.rx;
        state.rawRy = axes.ry;

        boolean curA      = btn(buttons, "a");
        boolean curB      = btn(buttons, "b");
        boolean curX      = btn(buttons, "x");
        boolean curY      = btn(buttons, "y");
        boolean curLB     = btn(buttons, "lShoulder");
        boolean curRB     = btn(buttons, "rShoulder");
        boolean curBack   = btn(buttons, "back");
        boolean curStart  = btn(buttons, "start");
        boolean curLThumb = btn(buttons, "lThumb");
        boolean curRThumb = btn(buttons, "rThumb");
        boolean curDpadUp    = btn(buttons, "up")    || btn(buttons, "dpadUp");
        boolean curDpadDown  = btn(buttons, "down")  || btn(buttons, "dpadDown");
        boolean curDpadLeft  = btn(buttons, "left")  || btn(buttons, "dpadLeft");
        boolean curDpadRight = btn(buttons, "right") || btn(buttons, "dpadRight");

        float ltVal = axis(axes, "lt"); if (ltVal == 0f) ltVal = axis(axes, "lz");
        float rtVal = axis(axes, "rt"); if (rtVal == 0f) rtVal = axis(axes, "rz");
        boolean ltPressed = ltVal > 0.45f;
        boolean rtPressed = rtVal > 0.45f;

        boolean inGui = mc.currentScreen != null;

        if (inGui) {
            handleGui(axes, curA, curB, curX, curY,
                curLB, curRB, curStart, curBack, curLThumb, curRThumb,
                curDpadUp, curDpadDown, curDpadLeft, curDpadRight,
                ltPressed, rtPressed);
            releaseMovementKeys();
            dropFrameCounter = 0;
        } else {
            state.cursorInitialised  = false;
            state.stickMovedThisTick = false;
            isDragging = false;
            handleGameplay(axes, curA, curB, curX, curY,
                curLB, curRB, curStart, curBack, curLThumb, curRThumb,
                curDpadUp, curDpadDown, curDpadLeft, curDpadRight,
                ltPressed, rtPressed);
        }

        if (DEBUG_PRINT_EVERY > 0 && ++debugCounter >= DEBUG_PRINT_EVERY) {
            debugCounter = 0;
            log(String.format("L=(%.2f,%.2f) R=(%.2f,%.2f) LT=%.2f RT=%.2f",
                axes.lx, axes.ly, axes.rx, axes.ry, ltVal, rtVal));
        }

        prevA = curA; prevB = curB; prevX = curX; prevY = curY;
        prevLB = curLB; prevRB = curRB;
        prevBack = curBack; prevStart = curStart;
        prevLThumb = curLThumb; prevRThumb = curRThumb;
        prevDpadUp = curDpadUp; prevDpadDown = curDpadDown;
        prevDpadLeft = curDpadLeft; prevDpadRight = curDpadRight;
    }

    // =========================================================================
    // Gameplay
    // =========================================================================
    private void handleGameplay(
        XInputAxes axes,
        boolean curA, boolean curB, boolean curX, boolean curY,
        boolean curLB, boolean curRB, boolean curStart, boolean curBack,
        boolean curLThumb, boolean curRThumb,
        boolean curDpadUp, boolean curDpadDown, boolean curDpadLeft, boolean curDpadRight,
        boolean ltPressed, boolean rtPressed
    ) {
        float procLx = processAxis(axes.lx, MOVE_DEADZONE);
        float procLy = processAxis(axes.ly, MOVE_DEADZONE);

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, procLy >  0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.keyCode,    procLy < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.keyCode,    procLx < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.keyCode,   procLx >  0.001f);

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, ltPressed);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.keyCode,  rtPressed);
        if (rtPressed && mc.thePlayer != null) {
            mc.thePlayer.swingItem();
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null)
                mc.playerController.attackEntity(mc.thePlayer, mc.objectMouseOver.entityHit);
        }

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, curA);
        if (curB && !prevB) dropFrameCounter = 2;
        if (curX && !prevX && mc.thePlayer != null)
            mc.displayGuiScreen(new GuiInventory(mc.thePlayer));

        if (curLB && !prevLB && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (curRB && !prevRB && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;

        if (curLThumb && !prevLThumb && mc.thePlayer != null)
            mc.thePlayer.setSprinting(!mc.thePlayer.isSprinting());
        if (curRThumb && !prevRThumb && mc.thePlayer != null)
            mc.thePlayer.setSneaking(!mc.thePlayer.isSneaking());

        if (curDpadUp   && !prevDpadUp)
            mc.gameSettings.thirdPersonView = (mc.gameSettings.thirdPersonView + 1) % 3;
        if (curDpadDown && !prevDpadDown)
            mc.gameSettings.hideGUI = !mc.gameSettings.hideGUI;

        if (curStart && !prevStart) openPauseMenu();
        if (curBack  && !prevBack && mc.thePlayer != null)
            mc.displayGuiScreen(new GuiChat());

        if (dropFrameCounter > 0) {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindDrop.keyCode, true);
            dropFrameCounter--;
        } else {
            KeyBinding.setKeyBindState(mc.gameSettings.keyBindDrop.keyCode, false);
        }
    }

    // =========================================================================
    // GUI
    // =========================================================================
    private void handleGui(
        XInputAxes axes,
        boolean curA, boolean curB, boolean curX, boolean curY,
        boolean curLB, boolean curRB, boolean curStart, boolean curBack,
        boolean curLThumb, boolean curRThumb,
        boolean curDpadUp, boolean curDpadDown, boolean curDpadLeft, boolean curDpadRight,
        boolean ltPressed, boolean rtPressed
    ) {
        GuiScreen screen = mc.currentScreen;

        // Get scaled resolution — all GUI coords are in scaled space
        ScaledResolution sr = getScaledResolution();
        int scaledW = sr.getScaledWidth();
        int scaledH = sr.getScaledHeight();

        // Initialise cursor to screen centre in scaled coords
        if (!state.cursorInitialised) {
            state.cursorGuiX      = scaledW / 2f;
            state.cursorGuiY      = scaledH / 2f;
            state.cursorInitialised = true;
        }

        // Move cursor with LEFT stick — work entirely in scaled GUI coords
        // Y is negated because stick-up (positive ry) should move cursor up (decreasing Y)
        float rsX = processAxis(axes.lx, GUI_CURSOR_DEADZONE);
        float rsY = processAxis(axes.ly, GUI_CURSOR_DEADZONE);

        state.stickMovedThisTick = (rsX != 0f || rsY != 0f);

        if (state.stickMovedThisTick) {
            state.cursorGuiX = clamp(state.cursorGuiX + rsX  * GUI_CURSOR_SPEED, 0, scaledW - 1);
            state.cursorGuiY = clamp(state.cursorGuiY + -rsY * GUI_CURSOR_SPEED, 0, scaledH - 1);
        }

        // These are the coords every GuiScreen method expects
        int mouseX = (int) state.cursorGuiX;
        int mouseY = (int) state.cursorGuiY;

        //   A button: click or drag   ─
        if (curA && !prevA) {
            aHeldSince = System.currentTimeMillis();
            isDragging = false;
            simulateMouseClick(screen, mouseX, mouseY, 0);
        } else if (curA && prevA) {
            if (System.currentTimeMillis() - aHeldSince > DRAG_THRESHOLD_MS) {
                isDragging = true;
                simulateMouseDrag(screen, mouseX, mouseY, 0);
            }
        } else if (!curA && prevA) {
            if (isDragging) simulateMouseRelease(screen, mouseX, mouseY, 0);
            isDragging = false;
        }

        //   B: right-click        
        if (curB && !prevB) simulateMouseClick(screen, mouseX, mouseY, 1);

        //   Y: shift-click        
        if (curY && !prevY && screen instanceof GuiContainer)
            shiftClickSlotAt((GuiContainer) screen, mouseX, mouseY);

        //   X: close screen       ─
        if (curX && !prevX) {
            closeGuiProperly(screen);
        }

        //   Start / Back: escape     
        if ((curStart && !prevStart) || (curBack && !prevBack)) {
            closeGuiProperly(screen);
        }

        //   LB/RB: scroll        ─
        if (curLB && !prevLB) simulateScroll(screen, mouseX, mouseY,  1);
        if (curRB && !prevRB) simulateScroll(screen, mouseX, mouseY, -1);

        //   D-pad: hotbar        ─
        if (curDpadLeft  && !prevDpadLeft  && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (curDpadRight && !prevDpadRight && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ScaledResolution getScaledResolution() {
        try {
            return new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        } catch (Throwable t) {
            // Fallback — return a dummy that reports 1:1 scale
            return new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight) {
                @Override public int getScaleFactor() { return 1; }
                @Override public int getScaledWidth()  { return mc.displayWidth; }
                @Override public int getScaledHeight() { return mc.displayHeight; }
            };
        }
    }

    private void releaseMovementKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.keyCode,    false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.keyCode,    false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.keyCode,   false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode,    false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode,   false);
    }

    // =========================================================================
    // Reflection helpers — obfuscation-safe (look up by signature, not name)
    // =========================================================================

    /**
     * Cache for mouse-event methods on GuiScreen, resolved by type signature
     * rather than name so they survive reobfuscation.
     */
    // All (int,int,int) void methods on GuiScreen — both mouseClicked and
    // mouseMovedOrUp share this signature. After obfuscation we cannot tell
    // them apart by name, so we call ALL of them on press. mouseMovedOrUp
    // called on press is harmless; missing mouseClicked means no response.
    private final List<Method> cachedTripleIntMethods = new java.util.ArrayList<Method>();
    private Method cachedMouseDrag    = null;
    private Method cachedActionPerf   = null;
    private Field  cachedButtonList   = null;
    private Field  cachedBtnX = null, cachedBtnY = null;
    private Field  cachedBtnW = null, cachedBtnH = null;
    private boolean guiMethodsResolved = false;

    private void resolveGuiMethods() {
        if (guiMethodsResolved) return;
        guiMethodsResolved = true;
        try {
            //   All (int,int,int) void methods — covers mouseClicked + mouseMovedOrUp
            for (Method m : GuiScreen.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    cachedTripleIntMethods.add(m);
                }
            }

            //   mouseClickMove: (int, int, int, long) void           
            for (Method m : GuiScreen.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 4
                        && p[0] == int.class && p[1] == int.class
                        && p[2] == int.class && p[3] == long.class
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    cachedMouseDrag = m;
                    break;
                }
            }

            //   actionPerformed: (GuiButton) void  
            for (Class<?> cls = GuiScreen.class; cls != null; cls = cls.getSuperclass()) {
                for (Method m : cls.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && GuiButton.class.isAssignableFrom(p[0])
                            && m.getReturnType() == void.class) {
                        m.setAccessible(true);
                        cachedActionPerf = m;
                        break;
                    }
                }
                if (cachedActionPerf != null) break;
            }

            //   buttonList: first List field on GuiScreen  
            for (Field f : GuiScreen.class.getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    cachedButtonList = f;
                    break;
                }
            }

            //   GuiButton int fields: id(0) width(1) height(2) xPosition(3) yPosition(4)
            List<Field> intFields = new java.util.ArrayList<Field>();
            for (Field f : GuiButton.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    intFields.add(f);
                }
            }
            if (intFields.size() >= 5) {
                cachedBtnW = intFields.get(1);
                cachedBtnH = intFields.get(2);
                cachedBtnX = intFields.get(3);
                cachedBtnY = intFields.get(4);
            }

            log("resolveGuiMethods: found " + cachedTripleIntMethods.size()
                + " triple-int methods, drag=" + (cachedMouseDrag != null)
                + ", actionPerf=" + (cachedActionPerf != null)
                + ", buttonList=" + (cachedButtonList != null)
                + ", btnFields=" + (cachedBtnX != null));
        } catch (Throwable t) {
            log("resolveGuiMethods failed: " + t);
        }
    }

    private void simulateMouseClick(GuiScreen screen, int mouseX, int mouseY, int button) {
        resolveGuiMethods();
        // Call every (int,int,int) void method — one of them is mouseClicked.
        // Calling mouseMovedOrUp on press is a no-op in vanilla so this is safe.
        boolean called = false;
        for (Method m : cachedTripleIntMethods) {
            try { m.invoke(screen, mouseX, mouseY, button); called = true; }
            catch (Throwable ignored) {}
        }
        if (called) return;
        // Hard fallback: find button under cursor and fire actionPerformed
        if (cachedButtonList != null && cachedActionPerf != null) {
            try {
                @SuppressWarnings("unchecked")
                List<GuiButton> btns = (List<GuiButton>) cachedButtonList.get(screen);
                if (btns != null) for (GuiButton btn : btns) {
                    if (btn.enabled && isOverButton(btn, mouseX, mouseY)) {
                        cachedActionPerf.invoke(screen, btn);
                        break;
                    }
                }
            } catch (Throwable t2) { log("simulateMouseClick fallback failed: " + t2); }
        }
    }

    private void simulateMouseDrag(GuiScreen screen, int mouseX, int mouseY, int button) {
        resolveGuiMethods();
        if (cachedMouseDrag != null) {
            try {
                cachedMouseDrag.invoke(screen, mouseX, mouseY, button,
                    System.currentTimeMillis() - aHeldSince);
            } catch (Throwable ignored) {}
        }
    }

    private void simulateMouseRelease(GuiScreen screen, int mouseX, int mouseY, int button) {
        resolveGuiMethods();
        // Same as simulateMouseClick — call all (int,int,int) void methods.
        // mouseMovedOrUp is in this set and will fire correctly.
        for (Method m : cachedTripleIntMethods) {
            try { m.invoke(screen, mouseX, mouseY, button); }
            catch (Throwable ignored) {}
        }
    }

    private boolean isOverButton(GuiButton btn, int mouseX, int mouseY) {
        resolveGuiMethods();
        try {
            if (cachedBtnX == null) return false;
            int x = cachedBtnX.getInt(btn), y = cachedBtnY.getInt(btn);
            int w = cachedBtnW.getInt(btn), h = cachedBtnH.getInt(btn);
            return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        } catch (Throwable ignored) { return false; }
    }

    private void shiftClickSlotAt(GuiContainer gui, int mouseX, int mouseY) {
        try {
            Method slotMethod = null;
            for (Method m : GuiContainer.class.getDeclaredMethods())
                if (m.getParameterTypes().length == 2
                        && (m.getReturnType() == Slot.class
                            || m.getReturnType().getSimpleName().equals("Slot"))) {
                    slotMethod = m; slotMethod.setAccessible(true); break;
                }
            if (slotMethod == null)
                for (Method m : gui.getClass().getMethods())
                    if (m.getParameterTypes().length == 2
                            && m.getReturnType().getSimpleName().equals("Slot")) {
                        slotMethod = m; slotMethod.setAccessible(true); break;
                    }
            if (slotMethod != null) {
                Object s = slotMethod.invoke(gui, mouseX, mouseY);
                if (s instanceof Slot)
                    mc.playerController.windowClick(
                        mc.thePlayer.openContainer.windowId,
                        ((Slot) s).slotNumber, 0, 1, mc.thePlayer);
            }
        } catch (Throwable t) { log("shiftClickSlotAt failed: " + t); }
    }

    private void simulateScroll(GuiScreen screen, int mouseX, int mouseY, int dir) {
        if (screen instanceof GuiContainer)
            shiftClickSlotAt((GuiContainer) screen, mouseX, mouseY);
    }

    /**
     * Properly close a GUI screen, returning any held crafting items to inventory.
     *
     * The bug: calling mc.displayGuiScreen(null) directly skips the container
     * close sequence. For GuiContainer screens (inventory, crafting, chests),
     * we must call mc.thePlayer.closeScreen() instead, which:
     *   1. Calls container.onContainerClosed(player) — drops crafting items back
     *   2. Sends the CloseWindow packet to the server
     *   3. Calls mc.displayGuiScreen(null) internally
     *
     * For non-container screens (title, pause, chat) the direct path is fine.
     */
    private void closeGuiProperly(GuiScreen screen) {
        if (screen instanceof GuiContainer && mc.thePlayer != null) {
            mc.thePlayer.closeScreen();
        } else {
            try { screen.onGuiClosed(); } catch (Throwable ignored) {}
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
        }
    }

    private void openPauseMenu() {
        try {
            Class<?> cls = Class.forName("net.minecraft.client.gui.GuiIngameMenu");
            mc.displayGuiScreen((GuiScreen) cls.newInstance());
        } catch (Throwable t) {
            log("Could not open pause menu: " + t.getMessage());
            mc.displayGuiScreen(null);
        }
    }
}