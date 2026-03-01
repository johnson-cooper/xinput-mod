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
import net.minecraft.client.gui.GuiControls;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.inventory.Slot;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;

/**
 * Tick handler driving controller polling + GUI interaction.
 * Reworked to pull bindings from XInputMod.config via ControllerAction.
 */
public class XInputTickHandler implements ITickHandler {

    private final Minecraft mc = Minecraft.getMinecraft();
    private final XInputSharedState state;

    // Controller backends
    private final JInputController jinput = new JInputController();
    private boolean jinputPermanentlyFailed = false;
    private Object jxController = null;
    private boolean jxInitAttempted = false;
    private boolean usingJXInput = false;

    private final ControllerState cs = new ControllerState();

    // Recipe browser
    final RecipeBrowser recipeBrowser = new RecipeBrowser(Minecraft.getMinecraft());

    // Tuning
    private static final float MOVE_DEADZONE       = 0.25f;
    private static final float GUI_CURSOR_DEADZONE = 0.15f;
    private static final float GUI_CURSOR_SPEED    = 12.0f;
    private static final float TRIGGER_THRESHOLD   = 0.45f; // analog trigger threshold
    private static final int   DEBUG_PRINT_EVERY   = 0;

    // Previous low-level button states (from controller)
    private boolean prevA, prevB, prevX, prevY;
    private boolean prevBack, prevStart, prevLB, prevRB;
    private boolean prevLThumb, prevRThumb;
    private boolean prevDpadUp, prevDpadDown, prevDpadLeft, prevDpadRight;
    private float   prevLt = 0f, prevRt = 0f;

    // Per-action previous states (so remaps work)
    private final boolean[] prevActionPressed = new boolean[ControllerAction.values().length];

    // Drag state
    private boolean isDragging = false;
    private long    aHeldSince = 0;
    private static final long DRAG_THRESHOLD_MS = 200;

    // Auto-craft state (Y hold)
    private long yCraftLastFired  = 0;
    private static final long CRAFT_INTERVAL_MS = 150;

    // Screen tracking (cursor re-centres when screen changes)
    private GuiScreen lastScreen = null;

    // Misc
    private int debugCounter = 0;

    // Controller settings button id (same as injector)
    private static final int BTN_CONTROLLER = 9876;

    // Sentinels for trigger bindings when user binds triggers explicitly.
    // (We store these sentinel ints in the config if user binds a trigger;
    // they are negative and won't collide with normal button indices.)
    private static final int BIND_LT_SENTINEL = -100;
    private static final int BIND_RT_SENTINEL = -101;
    private static final int BIND_DPAD_UP    = -110;
    private static final int BIND_DPAD_DOWN  = -111;
    private static final int BIND_DPAD_LEFT  = -112;
    private static final int BIND_DPAD_RIGHT = -113;

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
        if (!XInputMod.modEnabled) return;

        // ensure GUI injector runs while Controls screen is open
        GuiControlsInjector.tick(mc, XInputMod.config);

        boolean ok = pollController();
        if (!ok) {
            state.rawRx = 0f;
            state.rawRy = 0f;
         // NEW: GUI Cursor uses Left Stick
            state.rawLx = 0f; // Reset Left Stick
            state.rawLy = 0f; // Reset Left Stick
            releaseMovementKeys();
            // Clear previous action states so we don't get phantom edges
            for (int i = 0; i < prevActionPressed.length; i++) prevActionPressed[i] = false;
            return;
        }

        state.rawRx = cs.rx;
        state.rawRy = cs.ry;
        state.rawLx = cs.lx; // Add this line
        state.rawLy = cs.ly; // Add this line

        // Build per-action "currently pressed" snapshot (for remapping + edge detection)
        boolean[] curActionPressed = new boolean[ControllerAction.values().length];
        for (ControllerAction a : ControllerAction.values()) {
            curActionPressed[a.ordinal()] = isActionPressed(a);
        }

        boolean inGui = mc.currentScreen != null;

        if (inGui) {
            handleGuiWithActionEdges(curActionPressed); // uses curActionPressed & prevActionPressed
            releaseMovementKeys();
        } else {
            state.cursorInitialised  = false;
            state.stickMovedThisTick = false;
            isDragging = false;
            lastScreen = null;
            recipeBrowser.close();
            handleGameplayWithActionEdges(curActionPressed);
        }

        if (DEBUG_PRINT_EVERY > 0 && ++debugCounter >= DEBUG_PRINT_EVERY) {
            debugCounter = 0;
            log(String.format("L=(%.2f,%.2f) R=(%.2f,%.2f) LT=%.2f RT=%.2f",
                cs.lx, cs.ly, cs.rx, cs.ry, cs.lt, cs.rt));
        }

        // Update low-level prevs (keep these for compatibility & direct checks)
        prevA = cs.a; prevB = cs.b; prevX = cs.x; prevY = cs.y;
        prevLB = cs.lb; prevRB = cs.rb;
        prevBack = cs.back; prevStart = cs.start;
        prevLThumb = cs.lThumb; prevRThumb = cs.rThumb;
        prevDpadUp = cs.dpadUp; prevDpadDown = cs.dpadDown;
        prevDpadLeft = cs.dpadLeft; prevDpadRight = cs.dpadRight;

        // Update trigger floats
        prevLt = cs.lt; prevRt = cs.rt;

        // Persist per-action previous states for next tick
        for (int i = 0; i < prevActionPressed.length; i++) prevActionPressed[i] = curActionPressed[i];
    }

    // =========================================================================
    // Controller polling — JInput primary, JXInput fallback (Windows-only)
    // =========================================================================

    private boolean pollController() {
        // Try JInput first
        if (!usingJXInput && !jinputPermanentlyFailed) {
            JInputController.InitResult result = jinput.init();
            if (result == JInputController.InitResult.OK) {
                boolean ok = jinput.poll(cs);
                if (ok) return true;
                // poll returned false = disconnected, will re-init next tick
            } else if (result == JInputController.InitResult.ENVIRONMENT_BROKEN) {
                jinputPermanentlyFailed = true;
                log("JInput environment broken, falling back to JXInput.");
            }
        }

        // JXInput fallback (Windows)
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

        if (usingJXInput && jxController == null) {
            jxInitAttempted = false; // retry later
        }

        cs.zero();
        return false;
    }

    // JXInput via reflection (unchanged)
    private Object initJXInput() {
        try {
            Class<?> devCls = Class.forName("com.github.strikerx3.jxinput.XInputDevice");
            Method isAvail = devCls.getMethod("isAvailable");
            if (!(Boolean) isAvail.invoke(null)) return null;

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
            // expected on non-windows
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
    private void toggleInventory() {
        if (mc.thePlayer == null) return;

        if (mc.currentScreen instanceof GuiInventory) {
            // Close inventory
            mc.thePlayer.closeScreen();
            mc.setIngameFocus();
        } else if (mc.currentScreen == null) {
            // Open inventory
            mc.displayGuiScreen(new GuiInventory(mc.thePlayer));
        }
    }
    // =========================================================================
    // Gameplay (uses action-based edges)
    // =========================================================================

    private void handleGameplayWithActionEdges(boolean[] curActionPressed) {
        // Movement from left stick (unchanged)
        float procLx = processAxis(cs.lx, MOVE_DEADZONE);
        float procLy = processAxis(cs.ly, MOVE_DEADZONE);

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode,  procLy >  0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.keyCode,     procLy < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.keyCode,     procLx < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.keyCode,    procLx >  0.001f);

        // Use item / attack / jump pulled from config bindings
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode,
            curActionPressed[ControllerAction.USE_ITEM.ordinal()]);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.keyCode,
            curActionPressed[ControllerAction.ATTACK.ordinal()]);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode,
            curActionPressed[ControllerAction.JUMP.ordinal()]);

        // Attack immediate actions (swing/attackEntity) on attack-press edge
        boolean attackNow = curActionPressed[ControllerAction.ATTACK.ordinal()];
        boolean attackPrev = prevActionPressed[ControllerAction.ATTACK.ordinal()];
        if (attackNow && !attackPrev && mc.thePlayer != null) {
            mc.thePlayer.swingItem();
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null)
                mc.playerController.attackEntity(mc.thePlayer, mc.objectMouseOver.entityHit);
        }

        // Drop item
        if (curActionPressed[ControllerAction.DROP_ITEM.ordinal()] && !prevActionPressed[ControllerAction.DROP_ITEM.ordinal()]
                && mc.thePlayer != null) {
            mc.thePlayer.dropOneItem(false);
        }

     // Inventory toggle (open/close)
        if (curActionPressed[ControllerAction.INVENTORY.ordinal()]
            && !prevActionPressed[ControllerAction.INVENTORY.ordinal()]) {
            toggleInventory();
        }

        // Hotbar cycle
        if (curActionPressed[ControllerAction.HOTBAR_PREV.ordinal()] && !prevActionPressed[ControllerAction.HOTBAR_PREV.ordinal()]
                && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        }
        if (curActionPressed[ControllerAction.HOTBAR_NEXT.ordinal()] && !prevActionPressed[ControllerAction.HOTBAR_NEXT.ordinal()]
                && mc.thePlayer != null) {
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;
        }

        // Sprint toggle (on LThumb press edge)
        if (curActionPressed[ControllerAction.SPRINT.ordinal()] && !prevActionPressed[ControllerAction.SPRINT.ordinal()]
                && mc.thePlayer != null) {
            mc.thePlayer.setSprinting(!mc.thePlayer.isSprinting());
        }

        // Hold sneak while SNEAK action active
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode,
            curActionPressed[ControllerAction.SNEAK.ordinal()]);

        // D-pad actions (these are usually mapped separately in config to negative/other values;
        // we also honor the default behaviour — third person / hide GUI)
        if (curActionPressed[ControllerAction.THIRD_PERSON.ordinal()] && !prevActionPressed[ControllerAction.THIRD_PERSON.ordinal()]) {
            mc.gameSettings.thirdPersonView = (mc.gameSettings.thirdPersonView + 1) % 3;
        }
        if (curActionPressed[ControllerAction.HIDE_HUD.ordinal()] && !prevActionPressed[ControllerAction.HIDE_HUD.ordinal()]) {
            mc.gameSettings.hideGUI = !mc.gameSettings.hideGUI;
        }

        // Pause / chat
        if (curActionPressed[ControllerAction.PAUSE.ordinal()] && !prevActionPressed[ControllerAction.PAUSE.ordinal()]) openPauseMenu();
        if (curActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()] && !prevActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()]
                && mc.thePlayer != null) {
            // Using RECIPE_BROWSER as "back" default - same as before
            if (mc.currentScreen instanceof GuiContainer) {
                if (recipeBrowser.isOpen) recipeBrowser.close(); else recipeBrowser.open();
            } else {
                closeGuiProperly(mc.currentScreen);
            }
        }
        if (curActionPressed[ControllerAction.CHAT.ordinal()] && !prevActionPressed[ControllerAction.CHAT.ordinal()]) {
            mc.displayGuiScreen(new GuiChat());
        }

        // Detect Click on our custom button (using virtual cursor and A-equivalent action)
        // We'll treat the JUMP action (default A) for clicks — but this is governed by config binding
        boolean aPressedNow = curActionPressed[ControllerAction.JUMP.ordinal()];
        boolean aPressedPrev = prevActionPressed[ControllerAction.JUMP.ordinal()];
        if (aPressedNow && !aPressedPrev) { // On press edge
            if (mc.currentScreen instanceof GuiControls) {
                GuiControls gui = (GuiControls) mc.currentScreen;
                List buttons = getButtonList(gui);
                if (buttons != null) {
                    for (Object obj : buttons) {
                        if (!(obj instanceof GuiButton)) continue;
                        GuiButton btn = (GuiButton) obj;
                        if (btn.id == BTN_CONTROLLER && btn.mousePressed(mc, (int) state.cursorGuiX, (int) state.cursorGuiY)) {
                            mc.sndManager.playSoundFX("random.click", 1.0F, 1.0F);
                            mc.displayGuiScreen(new GuiControllerSettings(gui, XInputMod.config));
                            return;
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // GUI handling (uses action-edge snapshot)
    // =========================================================================

    private void handleGuiWithActionEdges(boolean[] curActionPressed) {
        GuiScreen screen = mc.currentScreen;

        // If we're on the Controller Settings screen and it's listening for a
        // binding, forward the raw button (or trigger) as it is pressed.
        if (screen instanceof GuiControllerSettings) {
            GuiControllerSettings settingsGui = (GuiControllerSettings) screen;

            // Standard face buttons -> send conventional indices (0..9)
            if (cs.a && !prevA) { if (settingsGui.onControllerButton(0)) { /* consumed */ return; } }
            if (cs.b && !prevB) { if (settingsGui.onControllerButton(1)) { return; } }
            if (cs.x && !prevX) { if (settingsGui.onControllerButton(2)) { return; } }
            if (cs.y && !prevY) { if (settingsGui.onControllerButton(3)) { return; } }

            if (cs.lb && !prevLB) { if (settingsGui.onControllerButton(4)) { return; } }
            if (cs.rb && !prevRB) { if (settingsGui.onControllerButton(5)) { return; } }

            if (cs.lThumb && !prevLThumb) { if (settingsGui.onControllerButton(8)) { return; } }
            if (cs.rThumb && !prevRThumb) { if (settingsGui.onControllerButton(9)) { return; } }

            if (cs.back && !prevBack) { if (settingsGui.onControllerButton(6)) { return; } }
            if (cs.start && !prevStart) { if (settingsGui.onControllerButton(7)) { return; } }

            // Triggers: if pressed past threshold while previously below threshold,
            // send sentinel so GUI can bind triggers specially.
            if (cs.lt > TRIGGER_THRESHOLD && prevLt <= TRIGGER_THRESHOLD) {
                if (settingsGui.onControllerButton(BIND_LT_SENTINEL)) return;
            }
            if (cs.rt > TRIGGER_THRESHOLD && prevRt <= TRIGGER_THRESHOLD) {
                if (settingsGui.onControllerButton(BIND_RT_SENTINEL)) return;
            }
         // D-pad binding support
            if (cs.dpadUp && !prevDpadUp) {
                if (settingsGui.onControllerButton(BIND_DPAD_UP)) return;
            }
            if (cs.dpadDown && !prevDpadDown) {
                if (settingsGui.onControllerButton(BIND_DPAD_DOWN)) return;
            }
            if (cs.dpadLeft && !prevDpadLeft) {
                if (settingsGui.onControllerButton(BIND_DPAD_LEFT)) return;
            }
            if (cs.dpadRight && !prevDpadRight) {
                if (settingsGui.onControllerButton(BIND_DPAD_RIGHT)) return;
            }
        }

        // Normal GUI processing (cursor movement / clicks etc.)
        handleGui(screen, curActionPressed);
    }

    // Keep core GUI behavior (cursor, clicks) in a separate method that
    // receives current action pressed snapshot for uses that map to actions.
    private void handleGui(GuiScreen screen, boolean[] curActionPressed) {
        ScaledResolution sr = getScaledResolution();
        int scaledW = sr.getScaledWidth();
        int scaledH = sr.getScaledHeight();
     // Inventory toggle while GUI is open
        if (curActionPressed[ControllerAction.INVENTORY.ordinal()]
            && !prevActionPressed[ControllerAction.INVENTORY.ordinal()]) {
            toggleInventory();
            return;
        }
        // Re-centre cursor whenever the active screen changes
        if (!state.cursorInitialised || screen != lastScreen) {
            state.cursorGuiX       = scaledW / 2f;
            state.cursorGuiY       = scaledH / 2f;
            state.cursorInitialised = true;
            lastScreen = screen;
        }

        // Back: toggle recipe browser (containers only) or close screen
        if (curActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()] && !prevActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()]) {
            if (screen instanceof GuiContainer) {
                if (recipeBrowser.isOpen) recipeBrowser.close(); else recipeBrowser.open();
            } else {
                closeGuiProperly(screen);
            }
        }

        // Recipe browser: consume all input while open
        if (recipeBrowser.isOpen) {
            if (curActionPressed[ControllerAction.THIRD_PERSON.ordinal()] && !prevActionPressed[ControllerAction.THIRD_PERSON.ordinal()]) recipeBrowser.scroll(-1);
            if (curActionPressed[ControllerAction.HIDE_HUD.ordinal()] && !prevActionPressed[ControllerAction.HIDE_HUD.ordinal()]) recipeBrowser.scroll(1);
            if (curActionPressed[ControllerAction.JUMP.ordinal()] && !prevActionPressed[ControllerAction.JUMP.ordinal()]) recipeBrowser.confirm();
            if (curActionPressed[ControllerAction.DROP_ITEM.ordinal()] && !prevActionPressed[ControllerAction.DROP_ITEM.ordinal()]) recipeBrowser.close();
            if (curActionPressed[ControllerAction.INVENTORY.ordinal()] && !prevActionPressed[ControllerAction.INVENTORY.ordinal()]) recipeBrowser.close();
            if (curActionPressed[ControllerAction.PAUSE.ordinal()] && !prevActionPressed[ControllerAction.PAUSE.ordinal()]) recipeBrowser.close();
            return;
        }

        // Left stick: free analog cursor movement
        float rsX = processAxis(cs.lx, GUI_CURSOR_DEADZONE);
        float rsY = processAxis(cs.ly, GUI_CURSOR_DEADZONE);

        state.stickMovedThisTick = (rsX != 0f || rsY != 0f);

        if (state.stickMovedThisTick) {
            state.cursorGuiX = clamp(state.cursorGuiX +  rsX * GUI_CURSOR_SPEED, 0, scaledW - 1);
            state.cursorGuiY = clamp(state.cursorGuiY + -rsY * GUI_CURSOR_SPEED, 0, scaledH - 1);
        }

        // D-pad hotbar cycling in GUIs (still handy)
        if (cs.dpadLeft && !prevDpadLeft && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (cs.dpadRight && !prevDpadRight && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;

        int mouseX = (int) state.cursorGuiX;
        int mouseY = (int) state.cursorGuiY;

        // A: click / drag (we use ACTION JUMP by default)
        boolean aNow = curActionPressed[ControllerAction.JUMP.ordinal()];
        boolean aPrev = prevActionPressed[ControllerAction.JUMP.ordinal()];
        if (aNow && !aPrev) {
            aHeldSince = System.currentTimeMillis();
            isDragging = false;
            simulateMouseClick(screen, mouseX, mouseY, 0);
        } else if (aNow && aPrev) {
            if (System.currentTimeMillis() - aHeldSince > DRAG_THRESHOLD_MS) {
                isDragging = true;
                simulateMouseDrag(screen, mouseX, mouseY, 0);
            }
        } else if (!aNow && aPrev) {
            if (isDragging) simulateMouseRelease(screen, mouseX, mouseY, 0);
            isDragging = false;
        }

        // B: right-click (mapped to DROP_ITEM action by default)
        if (curActionPressed[ControllerAction.DROP_ITEM.ordinal()] && !prevActionPressed[ControllerAction.DROP_ITEM.ordinal()])
            simulateMouseClick(screen, mouseX, mouseY, 1);

        // Y: shift-click (we map INVENTORY/shift-click behavior to INVENTORY action)
        if (curActionPressed[ControllerAction.INVENTORY.ordinal()] && !prevActionPressed[ControllerAction.INVENTORY.ordinal()] && screen instanceof GuiContainer)
            shiftClickSlotAt((GuiContainer) screen, mouseX, mouseY);

        // X: close screen (mapped to PAUSE or custom)
        if (curActionPressed[ControllerAction.PAUSE.ordinal()] && !prevActionPressed[ControllerAction.PAUSE.ordinal()])
            closeGuiProperly(screen);

        // Start: close in-game GUIs; do nothing on title screen (no player)
        if (curActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()] && !prevActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()] && mc.thePlayer != null)
            closeGuiProperly(screen);

        // LB/RB: scroll
        if (cs.lb && !prevLB) simulateScroll(screen, mouseX, mouseY,  1);
        if (cs.rb && !prevRB) simulateScroll(screen, mouseX, mouseY, -1);
    }

    // =========================================================================
    // Action -> controller mapping helpers
    // =========================================================================

    /**
     * Returns true if the given logical ControllerAction is currently active
     * according to the binding in config and the current controller state.
     *
     * Defaults:
     *  - ATTACK -> RT analog if binding is negative/unset
     *  - USE_ITEM -> LT analog if binding is negative/unset
     *
     * If user binds the action to a numeric button index, we map common indices:
     *   0=A,1=B,2=X,3=Y,4=LB,5=RB,6=Back,7=Start,8=LStick,9=RStick
     *
     * If the user explicitly binds to the sentinel values BIND_LT_SENTINEL/BIND_RT_SENTINEL
     * (when GUI supplies them), those map to analog triggers as well.
     */
    private boolean isActionPressed(ControllerAction action) {
        if (XInputMod.config == null) return false;
        int binding = XInputMod.config.getBinding(action);
     // D-pad sentinels
        if (binding == BIND_DPAD_UP)    return cs.dpadUp;
        if (binding == BIND_DPAD_DOWN)  return cs.dpadDown;
        if (binding == BIND_DPAD_LEFT)  return cs.dpadLeft;
        if (binding == BIND_DPAD_RIGHT) return cs.dpadRight;
        if (binding < 0) {
            if (action == ControllerAction.HOTBAR_PREV) return cs.dpadLeft;
            if (action == ControllerAction.HOTBAR_NEXT) return cs.dpadRight;
            if (action == ControllerAction.THIRD_PERSON) return cs.dpadUp;
            if (action == ControllerAction.HIDE_HUD) return cs.dpadDown;
        }
        // Explicit trigger sentinels
        if (binding == BIND_LT_SENTINEL) return cs.lt > TRIGGER_THRESHOLD;
        if (binding == BIND_RT_SENTINEL) return cs.rt > TRIGGER_THRESHOLD;

        // Legacy defaults: ATTACK/USE_ITEM use triggers if unbound (-1)
        if (binding < 0) {
            if (action == ControllerAction.ATTACK) return cs.rt > TRIGGER_THRESHOLD;
            if (action == ControllerAction.USE_ITEM) return cs.lt > TRIGGER_THRESHOLD;
            // otherwise unbound / false
            return false;
        }

        // Numeric button mapping (best-effort)
        switch (binding) {
            case 0: return cs.a;
            case 1: return cs.b;
            case 2: return cs.x;
            case 3: return cs.y;
            case 4: return cs.lb;
            case 5: return cs.rb;
            case 6: return cs.back;
            case 7: return cs.start;
            case 8: return cs.lThumb;
            case 9: return cs.rThumb;
            default:
                // If JInput reports more buttons and user bound one >9, we try
                // to detect it by comparing raw indices in JInput (best-effort).
                // However JInputController currently doesn't expose raw button read
                // externally; in practice the common buttons above cover most cases.
                return false;
        }
    }

    // =========================================================================
    // Helpers (existing code, mostly unchanged)
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
        if (screen == null) return;
        if (screen instanceof GuiContainer && mc.thePlayer != null) {
            mc.thePlayer.closeScreen();
        } else if (mc.thePlayer != null) {
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

    // Reflection helper to get button list from a GuiControls instance (best-effort)
    private List getButtonList(Object gui) {
        try {
            Field f = gui.getClass().getSuperclass().getDeclaredField("buttonList");
            f.setAccessible(true);
            return (List) f.get(gui);
        } catch (Exception e) { return null; }
    }
}