package com.attackishere.xinput;

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

    private final Minecraft mc = Minecraft.getMinecraft();
    private final XInputSharedState state;

    //  Controller backends 
    // JInput is tried first — it ships bundled with Minecraft's LWJGL, so
    // no extra dependencies are needed and it works on Windows/macOS/Linux.
    // JXInput is used as a fallback on Windows if JInput's environment is
    // broken (e.g. RawInput NPE on certain HID devices like ITE Device).
    private final JInputController jinput = new JInputController();
    private boolean jinputPermanentlyFailed = false;
    private Object jxController = null;   // XInputDevice, held as Object to avoid hard dep
    private boolean jxInitAttempted = false;
    private boolean usingJXInput = false;

    // Normalised state filled each tick by whichever backend is active
    private final ControllerState cs = new ControllerState();

    //  Recipe browser 
    final RecipeBrowser recipeBrowser = new RecipeBrowser(Minecraft.getMinecraft());

    //  Tuning 
    private static final float MOVE_DEADZONE       = 0.25f;
    private static final float GUI_CURSOR_DEADZONE = 0.15f;
    private static final float GUI_CURSOR_SPEED    = 12.0f;
    private static final int   DEBUG_PRINT_EVERY   = 0;

    //  Previous button states 
    private boolean prevA, prevB, prevX, prevY;
    private boolean prevBack, prevStart, prevLB, prevRB;
    private boolean prevLThumb, prevRThumb;
    private boolean prevDpadUp, prevDpadDown, prevDpadLeft, prevDpadRight;

    //  Drag state 
    private boolean isDragging = false;
    private long    aHeldSince = 0;
    private static final long DRAG_THRESHOLD_MS = 200;

    //  Auto-craft state (Y hold) 
    private long yCraftLastFired  = 0;
    private static final long CRAFT_INTERVAL_MS = 150;

    //  Misc 
    private int dropFrameCounter = 0;
    private int debugCounter     = 0;

    public XInputTickHandler(XInputSharedState state) {
        this.state = state;
    }

    private void log(String s) { System.out.println("[XInputMod] " + s); }

    // =========================================================================
    // ITickHandler
    // =========================================================================
    @Override public EnumSet<TickType> ticks()  { return EnumSet.of(TickType.CLIENT); }
    @Override public void tickEnd(EnumSet<TickType> types, Object... tickData) {}
    @Override public String getLabel()          { return "XInputTickHandler"; }

    @Override
    public void tickStart(EnumSet<TickType> types, Object... tickData) {
    	if (!XInputMod.modEnabled) {
            return; 
        }
        boolean ok = pollController();
        if (!ok) {
            state.rawRx = 0f;
            state.rawRy = 0f;
            releaseMovementKeys();
            return;
        }

        state.rawRx = cs.rx;
        state.rawRy = cs.ry;

        boolean inGui = mc.currentScreen != null;

        if (inGui) {
            handleGui();
            releaseMovementKeys();
            dropFrameCounter = 0;
        } else {
            state.cursorInitialised  = false;
            state.stickMovedThisTick = false;
            isDragging = false;
            recipeBrowser.close(); // always close browser when exiting a GUI
            handleGameplay();
        }

        if (DEBUG_PRINT_EVERY > 0 && ++debugCounter >= DEBUG_PRINT_EVERY) {
            debugCounter = 0;
            log(String.format("L=(%.2f,%.2f) R=(%.2f,%.2f) LT=%.2f RT=%.2f",
                cs.lx, cs.ly, cs.rx, cs.ry, cs.lt, cs.rt));
        }

        prevA = cs.a; prevB = cs.b; prevX = cs.x; prevY = cs.y;
        prevLB = cs.lb; prevRB = cs.rb;
        prevBack = cs.back; prevStart = cs.start;
        prevLThumb = cs.lThumb; prevRThumb = cs.rThumb;
        prevDpadUp = cs.dpadUp; prevDpadDown = cs.dpadDown;
        prevDpadLeft = cs.dpadLeft; prevDpadRight = cs.dpadRight;
    }

    // =========================================================================
    // Controller polling — SDL2 primary, JXInput fallback (Windows only)
    // =========================================================================

    /**
     * Fills cs with the current controller state.
     * Returns false if no controller is available.
     *
     * Priority:
     *   1. JInput (bundled with Minecraft's LWJGL — works on Windows/macOS/Linux)
     *   2. JXInput (Windows only fallback, used when JInput's environment is
     *      broken by a bad HID device like ITE Device causing a RawInput NPE)
     */
    private boolean pollController() {
        //  Try JInput first 
        if (!usingJXInput && !jinputPermanentlyFailed) {
            JInputController.InitResult result = jinput.init();
            if (result == JInputController.InitResult.OK) {
                boolean ok = jinput.poll(cs);
                if (ok) return true;
                // poll returned false = disconnected, will re-init next tick
            } else if (result == JInputController.InitResult.ENVIRONMENT_BROKEN) {
                // JInput's environment failed. On Windows this used to mean the
                // RawInput NPE was unrecoverable, but now JInputController tries
                // DirectInputEnvironmentPlugin directly which avoids the NPE.
                // If even that failed, permanently fall back to JXInput.
                jinputPermanentlyFailed = true;
                log("JInput environment broken, falling back to JXInput.");
            }
            // InitResult.NO_CONTROLLER = environment ok but no gamepad found yet,
            // keep retrying JInput in case controller is plugged in later.
        }

        //  JInput unavailable — try JXInput (Windows only) 
        if (!usingJXInput && !jxInitAttempted) {
            jxInitAttempted = true;
            jxController = initJXInput();
            if (jxController != null) {
                usingJXInput = true;
                log("Using JXInput backend.");
            } else {
                log("No controller backend available.");
            }
        }

        if (usingJXInput && jxController != null) {
            return pollJXInput();
        }

        // If JXInput also failed, keep retrying it periodically in case
        // the controller gets plugged in later.
        if (usingJXInput && jxController == null) {
            jxInitAttempted = false;
        }

        cs.zero();
        return false;
    }

    //  JXInput via reflection 
    // All JXInput access goes through reflection so the mod compiles and loads
    // on macOS/Linux where the JXInput classes aren't present at all.

    private Object initJXInput() {
        try {
            Class<?> devCls = Class.forName("com.github.strikerx3.jxinput.XInputDevice");
            Method isAvail = devCls.getMethod("isAvailable");
            if (!(Boolean) isAvail.invoke(null)) return null;

            // Try getAllDevices first
            try {
                Method getAll = devCls.getMethod("getAllDevices");
                Object[] devices = (Object[]) getAll.invoke(null);
                if (devices != null) {
                    for (Object dev : devices) {
                        Method isConn = devCls.getMethod("isConnected");
                        if (dev != null && (Boolean) isConn.invoke(dev)) {
                            Method preProcess = devCls.getMethod("setPreProcessData", boolean.class);
                            preProcess.invoke(null, true);
                            log("JXInput controller connected.");
                            return dev;
                        }
                    }
                }
            } catch (Throwable ignored) {}

            // Fall back to getDeviceFor(0..3)
            Method getFor = devCls.getMethod("getDeviceFor", int.class);
            for (int i = 0; i < 4; i++) {
                try {
                    Object dev = getFor.invoke(null, i);
                    Method isConn = devCls.getMethod("isConnected");
                    if (dev != null && (Boolean) isConn.invoke(dev)) {
                        try {
                            Method preProcess = devCls.getMethod("setPreProcessData", boolean.class);
                            preProcess.invoke(null, true);
                        } catch (Throwable ignored) {}
                        log("JXInput controller connected at index " + i);
                        return dev;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (ClassNotFoundException ignored) {
            // JXInput not on classpath — expected on macOS/Linux
        } catch (Throwable t) {
            log("JXInput init error: " + t);
        }
        return null;
    }

    private boolean pollJXInput() {
        try {
            Class<?> devCls  = jxController.getClass();
            Method pollM     = devCls.getMethod("poll");
            boolean ok       = (Boolean) pollM.invoke(jxController);
            if (!ok) { jxController = null; usingJXInput = false; jxInitAttempted = false; cs.zero(); return false; }

            Method getComp   = devCls.getMethod("getComponents");
            Object comps     = getComp.invoke(jxController);
            if (comps == null) return false;

            Object axes    = comps.getClass().getMethod("getAxes").invoke(comps);
            Object buttons = comps.getClass().getMethod("getButtons").invoke(comps);
            if (axes == null || buttons == null) return false;

            cs.lx = jxAxis(axes, "lx");
            cs.ly = jxAxis(axes, "ly");
            cs.rx = jxAxis(axes, "rx");
            cs.ry = jxAxis(axes, "ry");
            cs.lt = jxAxis(axes, "lt"); if (cs.lt == 0f) cs.lt = jxAxis(axes, "lz");
            cs.rt = jxAxis(axes, "rt"); if (cs.rt == 0f) cs.rt = jxAxis(axes, "rz");

            cs.a      = jxBtn(buttons, "a");
            cs.b      = jxBtn(buttons, "b");
            cs.x      = jxBtn(buttons, "x");
            cs.y      = jxBtn(buttons, "y");
            cs.lb     = jxBtn(buttons, "lShoulder");
            cs.rb     = jxBtn(buttons, "rShoulder");
            cs.lThumb = jxBtn(buttons, "lThumb");
            cs.rThumb = jxBtn(buttons, "rThumb");
            cs.start  = jxBtn(buttons, "start");
            cs.back   = jxBtn(buttons, "back");
            cs.dpadUp    = jxBtn(buttons, "up")    || jxBtn(buttons, "dpadUp");
            cs.dpadDown  = jxBtn(buttons, "down")  || jxBtn(buttons, "dpadDown");
            cs.dpadLeft  = jxBtn(buttons, "left")  || jxBtn(buttons, "dpadLeft");
            cs.dpadRight = jxBtn(buttons, "right") || jxBtn(buttons, "dpadRight");
            return true;
        } catch (Throwable t) {
            log("JXInput poll error: " + t);
            jxController = null; usingJXInput = false; jxInitAttempted = false;
            cs.zero();
            return false;
        }
    }

    private boolean jxBtn(Object buttons, String name) {
        try {
            Field f;
            try { f = buttons.getClass().getField(name); }
            catch (Throwable ignored) { f = buttons.getClass().getDeclaredField(name); }
            f.setAccessible(true);
            return f.getBoolean(buttons);
        } catch (Throwable ignored) { return false; }
    }

    private float jxAxis(Object axes, String name) {
        try {
            Field f;
            try { f = axes.getClass().getField(name); }
            catch (Throwable ignored) { f = axes.getClass().getDeclaredField(name); }
            f.setAccessible(true);
            return f.getFloat(axes);
        } catch (Throwable ignored) { return 0f; }
    }

    // =========================================================================
    // Gameplay
    // =========================================================================
    private void handleGameplay() {
        float procLx = processAxis(cs.lx, MOVE_DEADZONE);
        float procLy = processAxis(cs.ly, MOVE_DEADZONE);

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode,  procLy >  0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.keyCode,     procLy < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.keyCode,     procLx < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.keyCode,    procLx >  0.001f);

        boolean ltPressed = cs.lt > 0.45f;
        boolean rtPressed = cs.rt > 0.45f;

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, ltPressed);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.keyCode,  rtPressed);
        if (rtPressed && mc.thePlayer != null) {
            mc.thePlayer.swingItem();
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null)
                mc.playerController.attackEntity(mc.thePlayer, mc.objectMouseOver.entityHit);
        }

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, cs.a);
        if (cs.b && !prevB) dropFrameCounter = 2;
        if (cs.x && !prevX && mc.thePlayer != null)
            mc.displayGuiScreen(new GuiInventory(mc.thePlayer));

        if (cs.lb && !prevLB && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (cs.rb && !prevRB && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;

        if (cs.lThumb && !prevLThumb && mc.thePlayer != null)
            mc.thePlayer.setSprinting(!mc.thePlayer.isSprinting());
        // Hold sneak keybind while rThumb is held — setSneaking() alone doesn't
        // sync crouch state to the server correctly in 1.4.7.
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode, cs.rThumb);

        if (cs.dpadUp   && !prevDpadUp)
            mc.gameSettings.thirdPersonView = (mc.gameSettings.thirdPersonView + 1) % 3;
        if (cs.dpadDown && !prevDpadDown)
            mc.gameSettings.hideGUI = !mc.gameSettings.hideGUI;

        if (cs.start && !prevStart) openPauseMenu();
        if (cs.back  && !prevBack && mc.thePlayer != null)
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
    private void handleGui() {
        GuiScreen screen = mc.currentScreen;

        ScaledResolution sr = getScaledResolution();
        int scaledW = sr.getScaledWidth();
        int scaledH = sr.getScaledHeight();

        if (!state.cursorInitialised) {
            state.cursorGuiX       = scaledW / 2f;
            state.cursorGuiY       = scaledH / 2f;
            state.cursorInitialised = true;
        }

        //  Back: toggle recipe browser (containers only) or close screen 
        // Checked first so the toggle fires before any other input is processed.
        if (cs.back && !prevBack) {
            if (screen instanceof GuiContainer) {
                if (recipeBrowser.isOpen) recipeBrowser.close();
                else                      recipeBrowser.open();
            } else {
                closeGuiProperly(screen);
            }
        }

        //  Recipe browser: consume all input while open 
        if (recipeBrowser.isOpen) {
            if (cs.dpadUp   && !prevDpadUp)   recipeBrowser.scroll(-1);
            if (cs.dpadDown && !prevDpadDown)  recipeBrowser.scroll( 1);
            if (cs.a        && !prevA)         recipeBrowser.confirm();
            if (cs.b        && !prevB)         recipeBrowser.close();
            if (cs.x        && !prevX)         recipeBrowser.close();
            if (cs.start    && !prevStart)     recipeBrowser.close();
            // Do not process any other GUI input while browser is open
            return;
        }

        //  Left stick: free analog cursor movement 
        float rsX = processAxis(cs.lx, GUI_CURSOR_DEADZONE);
        float rsY = processAxis(cs.ly, GUI_CURSOR_DEADZONE);

        state.stickMovedThisTick = (rsX != 0f || rsY != 0f);

        if (state.stickMovedThisTick) {
            state.cursorGuiX = clamp(state.cursorGuiX +  rsX * GUI_CURSOR_SPEED, 0, scaledW - 1);
            state.cursorGuiY = clamp(state.cursorGuiY + -rsY * GUI_CURSOR_SPEED, 0, scaledH - 1);
        }

        //  D-pad: hotbar cycling in all GUI screens 
        if (cs.dpadLeft  && !prevDpadLeft  && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (cs.dpadRight && !prevDpadRight && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;

        int mouseX = (int) state.cursorGuiX;
        int mouseY = (int) state.cursorGuiY;

        //  A: click / drag 
        if (cs.a && !prevA) {
            aHeldSince = System.currentTimeMillis();
            isDragging = false;
            simulateMouseClick(screen, mouseX, mouseY, 0);
        } else if (cs.a && prevA) {
            if (System.currentTimeMillis() - aHeldSince > DRAG_THRESHOLD_MS) {
                isDragging = true;
                simulateMouseDrag(screen, mouseX, mouseY, 0);
            }
        } else if (!cs.a && prevA) {
            if (isDragging) simulateMouseRelease(screen, mouseX, mouseY, 0);
            isDragging = false;
        }

        //  B: right-click 
        if (cs.b && !prevB) simulateMouseClick(screen, mouseX, mouseY, 1);

        //  Y: shift-click 
        if (cs.y && !prevY && screen instanceof GuiContainer)
            shiftClickSlotAt((GuiContainer) screen, mouseX, mouseY);

        //  X / Start: close 
        if ((cs.x && !prevX) || (cs.start && !prevStart))
            closeGuiProperly(screen);

        //  LB/RB: scroll 
        if (cs.lb && !prevLB) simulateScroll(screen, mouseX, mouseY,  1);
        if (cs.rb && !prevRB) simulateScroll(screen, mouseX, mouseY, -1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

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

    private ScaledResolution getScaledResolution() {
        try {
            return new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        } catch (Throwable t) {
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
            for (Method m : GuiScreen.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length == 3
                        && p[0] == int.class && p[1] == int.class && p[2] == int.class
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    cachedTripleIntMethods.add(m);
                }
            }

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

            for (Field f : GuiScreen.class.getDeclaredFields()) {
                if (java.util.List.class.isAssignableFrom(f.getType())) {
                    f.setAccessible(true);
                    cachedButtonList = f;
                    break;
                }
            }

            List<Field> intFields = new java.util.ArrayList<Field>();
            for (Field f : GuiButton.class.getDeclaredFields()) {
                if (f.getType() == int.class) {
                    f.setAccessible(true);
                    intFields.add(f);
                }
            }
            // MCP field order: id(0) width(1) height(2) xPosition(3) yPosition(4)
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
        boolean called = false;
        for (Method m : cachedTripleIntMethods) {
            try { m.invoke(screen, mouseX, mouseY, button); called = true; }
            catch (Throwable ignored) {}
        }
        if (called) return;
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
            try { cachedMouseDrag.invoke(screen, mouseX, mouseY, button,
                System.currentTimeMillis() - aHeldSince); }
            catch (Throwable ignored) {}
        }
    }

    private void simulateMouseRelease(GuiScreen screen, int mouseX, int mouseY, int button) {
        resolveGuiMethods();
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

    // Cached slot-lookup method (obfuscation-safe, resolved by signature)
    private Method cachedSlotMethod = null;

    /**
     * Find the slot under (mouseX, mouseY) in scaled GUI coords.
     * Returns null if no slot is there or lookup fails.
     * Uses the same (int,int)->Slot signature search as before, but extracted
     * so both shiftClickSlotAt and the auto-craft hold check can use it.
     */
    private Slot getSlotAt(GuiContainer gui, int mouseX, int mouseY) {
        try {
            if (cachedSlotMethod == null) {
                for (Method m : GuiContainer.class.getDeclaredMethods())
                    if (m.getParameterTypes().length == 2
                            && (m.getReturnType() == Slot.class
                                || m.getReturnType().getSimpleName().equals("Slot"))) {
                        m.setAccessible(true); cachedSlotMethod = m; break;
                    }
                if (cachedSlotMethod == null)
                    for (Method m : gui.getClass().getMethods())
                        if (m.getParameterTypes().length == 2
                                && m.getReturnType().getSimpleName().equals("Slot")) {
                            m.setAccessible(true); cachedSlotMethod = m; break;
                        }
            }
            if (cachedSlotMethod != null) {
                Object s = cachedSlotMethod.invoke(gui, mouseX, mouseY);
                if (s instanceof Slot) return (Slot) s;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void shiftClickSlotAt(GuiContainer gui, int mouseX, int mouseY) {
        try {
            Slot s = getSlotAt(gui, mouseX, mouseY);
            if (s != null)
                mc.playerController.windowClick(
                    mc.thePlayer.openContainer.windowId,
                    s.slotNumber, 0, 1, mc.thePlayer);
        } catch (Throwable t) { log("shiftClickSlotAt failed: " + t); }
    }

    private void simulateScroll(GuiScreen screen, int mouseX, int mouseY, int dir) {
        if (screen instanceof GuiContainer)
            shiftClickSlotAt((GuiContainer) screen, mouseX, mouseY);
    }

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