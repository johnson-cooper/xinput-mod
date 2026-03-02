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

    private final JInputController jinput = new JInputController();
    private boolean jinputPermanentlyFailed = false;
    private Object  jxController    = null;
    private boolean jxInitAttempted = false;
    private boolean usingJXInput    = false;

    private final ControllerState cs = new ControllerState();

    final RecipeBrowser recipeBrowser = new RecipeBrowser(Minecraft.getMinecraft());

    private static final float MOVE_DEADZONE     = 0.25f;
    private static final float TRIGGER_THRESHOLD = 0.45f;
    private static final int   DEBUG_PRINT_EVERY = 0;

    // Low-level previous button states (for edge detection)
    private boolean prevA, prevB, prevX, prevY;
    private boolean prevBack, prevStart, prevLB, prevRB;
    private boolean prevLThumb, prevRThumb;
    private boolean prevDpadUp, prevDpadDown, prevDpadLeft, prevDpadRight;
    private float   prevLt = 0f, prevRt = 0f;

    // Per-action previous states
    private final boolean[] prevActionPressed = new boolean[ControllerAction.values().length];

    private boolean isDragging = false;
    private long    aHeldSince = 0;
    private static final long DRAG_THRESHOLD_MS = 200;

    private GuiScreen lastScreen = null;
    private int debugCounter = 0;
    private boolean defaultsApplied = false;

    // Sentinels stored in config for non-button inputs
    public static final int BIND_LT_SENTINEL  = -100;
    public static final int BIND_RT_SENTINEL  = -101;
    public static final int BIND_DPAD_UP      = -110;
    public static final int BIND_DPAD_DOWN    = -111;
    public static final int BIND_DPAD_LEFT    = -112;
    public static final int BIND_DPAD_RIGHT   = -113;

    public XInputTickHandler(XInputSharedState state) { this.state = state; }

    private void log(String s) { System.out.println("[XInputMod] " + s); }

    @Override public EnumSet<TickType> ticks()  { return EnumSet.of(TickType.CLIENT); }
    @Override public void tickEnd(EnumSet<TickType> t, Object... d) {}
    @Override public String getLabel() { return "XInputTickHandler"; }

    // =========================================================================
    // Main tick
    // =========================================================================

    @Override
    public void tickStart(EnumSet<TickType> types, Object... tickData) {
        if (!XInputMod.modEnabled) return;

        GuiControlsInjector.tick(mc, XInputMod.config);

        boolean ok = pollController();
        if (!ok) {
            state.rawRx = 0f; state.rawRy = 0f;
            state.rawLx = 0f; state.rawLy = 0f;
            releaseMovementKeys();
            for (int i = 0; i < prevActionPressed.length; i++) prevActionPressed[i] = false;
            return;
        }

        state.rawRx = cs.rx; state.rawRy = cs.ry;
        state.rawLx = cs.lx; state.rawLy = cs.ly;

        if (!defaultsApplied && !usingJXInput && XInputMod.config != null) {
            XInputMod.config.applyDetectedDefaults(jinput);
            defaultsApplied = true;
        }

        boolean[] cur = new boolean[ControllerAction.values().length];
        for (ControllerAction a : ControllerAction.values())
            cur[a.ordinal()] = isActionPressed(a);

        boolean inGui = mc.currentScreen != null;
        if (inGui) {
            handleGuiWithActionEdges(cur);
            releaseMovementKeys();
        } else {
            state.cursorInitialised  = false;
            state.stickMovedThisTick = false;
            isDragging               = false;
            lastScreen               = null;
            recipeBrowser.close();
            handleGameplay(cur);
        }

        if (DEBUG_PRINT_EVERY > 0 && ++debugCounter >= DEBUG_PRINT_EVERY) {
            debugCounter = 0;
            log(String.format("L=(%.2f,%.2f) R=(%.2f,%.2f) LT=%.2f RT=%.2f start=%b back=%b",
                cs.lx, cs.ly, cs.rx, cs.ry, cs.lt, cs.rt, cs.start, cs.back));
        }

        prevA = cs.a; prevB = cs.b; prevX = cs.x; prevY = cs.y;
        prevLB = cs.lb; prevRB = cs.rb;
        prevBack = cs.back; prevStart = cs.start;
        prevLThumb = cs.lThumb; prevRThumb = cs.rThumb;
        prevDpadUp = cs.dpadUp; prevDpadDown = cs.dpadDown;
        prevDpadLeft = cs.dpadLeft; prevDpadRight = cs.dpadRight;
        prevLt = cs.lt; prevRt = cs.rt;
        for (int i = 0; i < prevActionPressed.length; i++) prevActionPressed[i] = cur[i];
    }

    // =========================================================================
    // Polling
    // =========================================================================

    private boolean pollController() {
        if (!usingJXInput && !jinputPermanentlyFailed) {
            JInputController.InitResult r = jinput.init();
            if (r == JInputController.InitResult.OK) {
                if (jinput.poll(cs)) return true;
            } else if (r == JInputController.InitResult.ENVIRONMENT_BROKEN) {
                jinputPermanentlyFailed = true;
                log("JInput broken, falling back to JXInput.");
            }
        }
        if (!usingJXInput && !jxInitAttempted) {
            jxInitAttempted = true;
            jxController = initJXInput();
            if (jxController != null) { usingJXInput = true; log("Using JXInput."); }
            else log("No controller backend available.");
        }
        if (usingJXInput && jxController != null) return pollJXInput();
        if (usingJXInput && jxController == null) jxInitAttempted = false;
        cs.zero(); return false;
    }

    private Object initJXInput() {
        try {
            Class<?> dc = Class.forName("com.github.strikerx3.jxinput.XInputDevice");
            if (!(Boolean) dc.getMethod("isAvailable").invoke(null)) return null;
            try {
                Object[] devs = (Object[]) dc.getMethod("getAllDevices").invoke(null);
                if (devs != null) for (Object d : devs)
                    if (d != null && (Boolean) dc.getMethod("isConnected").invoke(d)) {
                        dc.getMethod("setPreProcessData", boolean.class).invoke(null, true);
                        log("JXInput connected."); return d;
                    }
            } catch (Throwable ignored) {}
            for (int i = 0; i < 4; i++) {
                try {
                    Object d = dc.getMethod("getDeviceFor", int.class).invoke(null, i);
                    if (d != null && (Boolean) dc.getMethod("isConnected").invoke(d)) {
                        try { dc.getMethod("setPreProcessData", boolean.class).invoke(null, true); } catch (Throwable ignored) {}
                        log("JXInput at " + i); return d;
                    }
                } catch (Throwable ignored) {}
            }
        } catch (ClassNotFoundException ignored) {
        } catch (Throwable t) { log("JXInput init: " + t); }
        return null;
    }

    private boolean pollJXInput() {
        try {
            Class<?> dc = jxController.getClass();
            if (!(Boolean) dc.getMethod("poll").invoke(jxController)) {
                jxController=null; usingJXInput=false; jxInitAttempted=false; cs.zero(); return false;
            }
            Object comps = dc.getMethod("getComponents").invoke(jxController);
            if (comps == null) return false;
            Object axes = comps.getClass().getMethod("getAxes").invoke(comps);
            Object btns = comps.getClass().getMethod("getButtons").invoke(comps);
            if (axes == null || btns == null) return false;
            cs.lx=jxAxis(axes,"lx"); cs.ly=jxAxis(axes,"ly");
            cs.rx=jxAxis(axes,"rx"); cs.ry=jxAxis(axes,"ry");
            cs.lt=jxAxis(axes,"lt"); if(cs.lt==0f)cs.lt=jxAxis(axes,"lz");
            cs.rt=jxAxis(axes,"rt"); if(cs.rt==0f)cs.rt=jxAxis(axes,"rz");
            cs.a=jxBtn(btns,"a"); cs.b=jxBtn(btns,"b"); cs.x=jxBtn(btns,"x"); cs.y=jxBtn(btns,"y");
            cs.lb=jxBtn(btns,"lShoulder"); cs.rb=jxBtn(btns,"rShoulder");
            cs.lThumb=jxBtn(btns,"lThumb"); cs.rThumb=jxBtn(btns,"rThumb");
            cs.start=jxBtn(btns,"start"); cs.back=jxBtn(btns,"back");
            cs.dpadUp   =jxBtn(btns,"up")   ||jxBtn(btns,"dpadUp");
            cs.dpadDown =jxBtn(btns,"down") ||jxBtn(btns,"dpadDown");
            cs.dpadLeft =jxBtn(btns,"left") ||jxBtn(btns,"dpadLeft");
            cs.dpadRight=jxBtn(btns,"right")||jxBtn(btns,"dpadRight");
            return true;
        } catch (Throwable t) {
            log("JXInput poll: "+t); jxController=null; usingJXInput=false; jxInitAttempted=false; cs.zero(); return false;
        }
    }

    private boolean jxBtn(Object b,String n){try{Field f;try{f=b.getClass().getField(n);}catch(Throwable i){f=b.getClass().getDeclaredField(n);}f.setAccessible(true);return f.getBoolean(b);}catch(Throwable i){return false;}}
    private float jxAxis(Object a,String n){try{Field f;try{f=a.getClass().getField(n);}catch(Throwable i){f=a.getClass().getDeclaredField(n);}f.setAccessible(true);return f.getFloat(a);}catch(Throwable i){return 0f;}}

    // =========================================================================
    // isActionPressed
    // =========================================================================

    private boolean isActionPressed(ControllerAction action) {
        if (XInputMod.config == null) return false;
        int binding = XInputMod.config.getBinding(action);

        // Explicit sentinel bindings
        if (binding == BIND_DPAD_UP)     return cs.dpadUp;
        if (binding == BIND_DPAD_DOWN)   return cs.dpadDown;
        if (binding == BIND_DPAD_LEFT)   return cs.dpadLeft;
        if (binding == BIND_DPAD_RIGHT)  return cs.dpadRight;
        if (binding == BIND_LT_SENTINEL) return cs.lt > TRIGGER_THRESHOLD;
        if (binding == BIND_RT_SENTINEL) return cs.rt > TRIGGER_THRESHOLD;

        // binding == -1 means "use hardware default for this action"
        if (binding < 0) {
            switch (action) {
                case ATTACK:       return cs.rt > TRIGGER_THRESHOLD;
                case USE_ITEM:     return cs.lt > TRIGGER_THRESHOLD;
                case HOTBAR_PREV:  return cs.dpadLeft;
                case HOTBAR_NEXT:  return cs.dpadRight;
                // New defaults: dpad-based
                case SNEAK:        return cs.dpadDown;
                case SPRINT:       return cs.dpadUp;
                case THIRD_PERSON: return false; // no default dpad slot any more
                case HIDE_HUD:     return false;
                default:           return false;
            }
        }

        // Numeric binding — map via JInput's detected button array
        return jinput.rawButtonPressed(binding, cs);
    }

    // =========================================================================
    // Gameplay
    // =========================================================================

    private void handleGameplay(boolean[] cur) {
        float px = processAxis(cs.lx, MOVE_DEADZONE);
        float py = processAxis(cs.ly, MOVE_DEADZONE);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode,  py >  0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.keyCode,     py < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.keyCode,     px < -0.001f);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.keyCode,    px >  0.001f);

        KeyBinding.setKeyBindState(mc.gameSettings.keyBindUseItem.keyCode, cur[ControllerAction.USE_ITEM.ordinal()]);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindAttack.keyCode,  cur[ControllerAction.ATTACK.ordinal()]);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode,    cur[ControllerAction.JUMP.ordinal()]);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode,   cur[ControllerAction.SNEAK.ordinal()]);

        if (cur[ControllerAction.ATTACK.ordinal()] && !prevActionPressed[ControllerAction.ATTACK.ordinal()]
                && mc.thePlayer != null) {
            mc.thePlayer.swingItem();
            if (mc.objectMouseOver != null && mc.objectMouseOver.entityHit != null)
                mc.playerController.attackEntity(mc.thePlayer, mc.objectMouseOver.entityHit);
        }

        if (cur[ControllerAction.DROP_ITEM.ordinal()] && !prevActionPressed[ControllerAction.DROP_ITEM.ordinal()]
                && mc.thePlayer != null)
            mc.thePlayer.dropOneItem(false);

        if (cur[ControllerAction.INVENTORY.ordinal()] && !prevActionPressed[ControllerAction.INVENTORY.ordinal()]
                && mc.thePlayer != null)
            mc.displayGuiScreen(new GuiInventory(mc.thePlayer));

        if (cur[ControllerAction.HOTBAR_PREV.ordinal()] && !prevActionPressed[ControllerAction.HOTBAR_PREV.ordinal()]
                && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (cur[ControllerAction.HOTBAR_NEXT.ordinal()] && !prevActionPressed[ControllerAction.HOTBAR_NEXT.ordinal()]
                && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;

        if (cur[ControllerAction.SPRINT.ordinal()] && !prevActionPressed[ControllerAction.SPRINT.ordinal()]
                && mc.thePlayer != null)
            mc.thePlayer.setSprinting(!mc.thePlayer.isSprinting());

        if (cur[ControllerAction.THIRD_PERSON.ordinal()] && !prevActionPressed[ControllerAction.THIRD_PERSON.ordinal()])
            mc.gameSettings.thirdPersonView = (mc.gameSettings.thirdPersonView + 1) % 3;
        if (cur[ControllerAction.HIDE_HUD.ordinal()] && !prevActionPressed[ControllerAction.HIDE_HUD.ordinal()])
            mc.gameSettings.hideGUI = !mc.gameSettings.hideGUI;

        // Start button = pause. Use low-level cs.start so it always works regardless of binding.
        if (cs.start && !prevStart)
            openPauseMenu();

        if (cur[ControllerAction.RECIPE_BROWSER.ordinal()] && !prevActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()]
                && mc.thePlayer != null && mc.currentScreen instanceof GuiContainer) {
            if (recipeBrowser.isOpen) recipeBrowser.close(); else recipeBrowser.open();
        }

        if (cur[ControllerAction.CHAT.ordinal()] && !prevActionPressed[ControllerAction.CHAT.ordinal()])
            mc.displayGuiScreen(new GuiChat());
    }

    // =========================================================================
    // GUI
    // =========================================================================

    private void handleGuiWithActionEdges(boolean[] cur) {
        GuiScreen screen = mc.currentScreen;

        // Re-centre cursor when screen changes
        if (screen != lastScreen) {
            state.cursorInitialised = false;
            lastScreen = screen;
        }

        // Feed ALL raw button presses to the settings binding listener
        if (screen instanceof GuiControllerSettings) {
            GuiControllerSettings s = (GuiControllerSettings) screen;
            // Face buttons
            if (cs.a     && !prevA      && s.onControllerButton(jinput.btnA()))      return;
            if (cs.b     && !prevB      && s.onControllerButton(jinput.btnB()))      return;
            if (cs.x     && !prevX      && s.onControllerButton(jinput.btnX()))      return;
            if (cs.y     && !prevY      && s.onControllerButton(jinput.btnY()))      return;
            // Shoulders
            if (cs.lb    && !prevLB     && s.onControllerButton(jinput.btnLB()))     return;
            if (cs.rb    && !prevRB     && s.onControllerButton(jinput.btnRB()))     return;
            // Back/Start
            if (cs.back  && !prevBack   && s.onControllerButton(jinput.btnBack()))   return;
            if (cs.start && !prevStart  && s.onControllerButton(jinput.btnStart()))  return;
            // Stick clicks
            if (cs.lThumb && !prevLThumb && s.onControllerButton(jinput.btnLStick())) return;
            if (cs.rThumb && !prevRThumb && s.onControllerButton(jinput.btnRStick())) return;
            // Triggers (use sentinels so they can be stored/recognised in config)
            if (cs.lt > TRIGGER_THRESHOLD && prevLt <= TRIGGER_THRESHOLD
                    && s.onControllerButton(BIND_LT_SENTINEL)) return;
            if (cs.rt > TRIGGER_THRESHOLD && prevRt <= TRIGGER_THRESHOLD
                    && s.onControllerButton(BIND_RT_SENTINEL)) return;
            // D-pad
            if (cs.dpadUp    && !prevDpadUp    && s.onControllerButton(BIND_DPAD_UP))    return;
            if (cs.dpadDown  && !prevDpadDown  && s.onControllerButton(BIND_DPAD_DOWN))  return;
            if (cs.dpadLeft  && !prevDpadLeft  && s.onControllerButton(BIND_DPAD_LEFT))  return;
            if (cs.dpadRight && !prevDpadRight && s.onControllerButton(BIND_DPAD_RIGHT)) return;
        }

        handleGui(screen, cur);
    }

    private void handleGui(GuiScreen screen, boolean[] cur) {
        // Virtual cursor coords are maintained by XInputGuiRenderer (which stays
        // grabbed and tracks getDX/getDY + stick deltas). We just read them here.
        int mouseX = (int) state.cursorGuiX;
        int mouseY = (int) state.cursorGuiY;

        // Back: toggle recipe browser in containers, else close screen
        if (cur[ControllerAction.RECIPE_BROWSER.ordinal()]
                && !prevActionPressed[ControllerAction.RECIPE_BROWSER.ordinal()]) {
            if (screen instanceof GuiContainer) {
                if (recipeBrowser.isOpen) recipeBrowser.close(); else recipeBrowser.open();
            } else {
                closeGuiProperly(screen);
            }
        }

        // Recipe browser consumes input when open
        if (recipeBrowser.isOpen) {
            if (cs.dpadUp   && !prevDpadUp)   recipeBrowser.scroll(-1);
            if (cs.dpadDown && !prevDpadDown) recipeBrowser.scroll(1);
            if (cs.a && !prevA) recipeBrowser.confirm();
            if (cs.b && !prevB) recipeBrowser.close();
            if (cs.x && !prevX) recipeBrowser.close();
            return;
        }

        // D-pad: hotbar prev/next (always available in any GUI)
        if (cs.dpadLeft  && !prevDpadLeft  && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 8) % 9;
        if (cs.dpadRight && !prevDpadRight && mc.thePlayer != null)
            mc.thePlayer.inventory.currentItem = (mc.thePlayer.inventory.currentItem + 1) % 9;

        // A: left-click / drag
        if (cs.a && !prevA) {
            aHeldSince = System.currentTimeMillis(); isDragging = false;
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

        // B: right-click
        if (cs.b && !prevB) simulateMouseClick(screen, mouseX, mouseY, 1);

        // Y: shift-click
        if (cs.y && !prevY && screen instanceof GuiContainer)
            shiftClickSlotAt((GuiContainer) screen, mouseX, mouseY);

        // X: close screen
        if (cs.x && !prevX && mc.thePlayer != null)
            closeGuiProperly(screen);

        // Start: resume from pause menu, or close any other GUI
        if (cs.start && !prevStart)
            handleStartInGui(screen);

        // LB/RB: scroll
        if (cs.lb && !prevLB) simulateScroll(screen, mouseX, mouseY,  1);
        if (cs.rb && !prevRB) simulateScroll(screen, mouseX, mouseY, -1);
    }

    /**
     * Start in a GUI:
     *   - Pause menu → resume (close + grab focus)
     *   - Any other GUI → close it (same as X)
     *   - No player (title screen) → do nothing
     */
    private void handleStartInGui(GuiScreen screen) {
        if (screen == null) return;
        if (mc.thePlayer == null) return;

        // Detect pause menu by class name — works through obfuscation because
        // the simple name "GuiIngameMenu" survives in FML's class loading on 1.4.7
        String cls = screen.getClass().getName();
        if (cls.contains("GuiIngameMenu") || cls.contains("GuiGameOver")) {
            mc.displayGuiScreen(null);
            mc.setIngameFocus();
            return;
        }
        closeGuiProperly(screen);
    }

    // =========================================================================
    // Mouse event simulation
    // =========================================================================

    private final List<Method> cachedTripleIntMethods = new java.util.ArrayList<Method>();
    private Method  cachedMouseDrag  = null;
    private Method  cachedActionPerf = null;
    private Field   cachedButtonList = null;
    private Field   cachedBtnX = null, cachedBtnY = null, cachedBtnW = null, cachedBtnH = null;
    private boolean guiMethodsResolved = false;

    private void resolveGuiMethods() {
        if (guiMethodsResolved) return;
        guiMethodsResolved = true;
        try {
            for (Method m : GuiScreen.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length==3 && p[0]==int.class && p[1]==int.class && p[2]==int.class && m.getReturnType()==void.class)
                    { m.setAccessible(true); cachedTripleIntMethods.add(m); }
            }
            for (Method m : GuiScreen.class.getDeclaredMethods()) {
                Class<?>[] p = m.getParameterTypes();
                if (p.length==4 && p[0]==int.class && p[1]==int.class && p[2]==int.class && p[3]==long.class && m.getReturnType()==void.class)
                    { m.setAccessible(true); cachedMouseDrag=m; break; }
            }
            for (Class<?> c=GuiScreen.class;c!=null;c=c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length==1 && GuiButton.class.isAssignableFrom(p[0]) && m.getReturnType()==void.class)
                        { m.setAccessible(true); cachedActionPerf=m; break; }
                }
                if (cachedActionPerf!=null) break;
            }
            // Button list — full hierarchy scan
            for (Class<?> c=GuiScreen.class;c!=null;c=c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) { f.setAccessible(true); cachedButtonList=f; break; }
                }
                if (cachedButtonList!=null) break;
            }
            // GuiButton int fields: id, width, height, xPosition, yPosition
            List<Field> ints = new java.util.ArrayList<Field>();
            for (Field f : GuiButton.class.getDeclaredFields())
                if (f.getType()==int.class) { f.setAccessible(true); ints.add(f); }
            if (ints.size()>=5) { cachedBtnW=ints.get(1); cachedBtnH=ints.get(2); cachedBtnX=ints.get(3); cachedBtnY=ints.get(4); }
            log("resolveGuiMethods: "+cachedTripleIntMethods.size()+" tripleInt, drag="+(cachedMouseDrag!=null)+", action="+(cachedActionPerf!=null)+", list="+(cachedButtonList!=null));
        } catch (Throwable t) { log("resolveGuiMethods: "+t); }
    }

    private void simulateMouseClick(GuiScreen s, int x, int y, int btn) {
        resolveGuiMethods();
        boolean called = false;
        for (Method m : cachedTripleIntMethods) { try{m.invoke(s,x,y,btn);called=true;}catch(Throwable ignored){} }
        if (called) return;
        if (cachedButtonList!=null && cachedActionPerf!=null) {
            try {
                @SuppressWarnings("unchecked") List<GuiButton> btns=(List<GuiButton>)cachedButtonList.get(s);
                if (btns!=null) for (GuiButton b:btns) if (b.enabled && isOverButton(b,x,y)) { cachedActionPerf.invoke(s,b); break; }
            } catch (Throwable ignored) {}
        }
    }
    private void simulateMouseDrag(GuiScreen s, int x, int y, int btn) {
        resolveGuiMethods();
        if (cachedMouseDrag!=null) try{cachedMouseDrag.invoke(s,x,y,btn,System.currentTimeMillis()-aHeldSince);}catch(Throwable ignored){}
    }
    private void simulateMouseRelease(GuiScreen s, int x, int y, int btn) {
        resolveGuiMethods();
        for (Method m : cachedTripleIntMethods) try{m.invoke(s,x,y,btn);}catch(Throwable ignored){}
    }
    private boolean isOverButton(GuiButton b, int mx, int my) {
        resolveGuiMethods();
        if (cachedBtnX==null) return false;
        try {
            int bx=cachedBtnX.getInt(b),by=cachedBtnY.getInt(b),bw=cachedBtnW.getInt(b),bh=cachedBtnH.getInt(b);
            return mx>=bx && mx<bx+bw && my>=by && my<by+bh;
        } catch (Throwable ignored) { return false; }
    }

    private Method cachedSlotMethod = null;
    private Slot getSlotAt(GuiContainer gui, int mx, int my) {
        try {
            if (cachedSlotMethod==null) {
                for (Method m : GuiContainer.class.getDeclaredMethods())
                    if (m.getParameterTypes().length==2 && (m.getReturnType()==Slot.class||m.getReturnType().getSimpleName().equals("Slot")))
                        { m.setAccessible(true); cachedSlotMethod=m; break; }
                if (cachedSlotMethod==null)
                    for (Method m : gui.getClass().getMethods())
                        if (m.getParameterTypes().length==2 && m.getReturnType().getSimpleName().equals("Slot"))
                            { m.setAccessible(true); cachedSlotMethod=m; break; }
            }
            if (cachedSlotMethod!=null) { Object r=cachedSlotMethod.invoke(gui,mx,my); if (r instanceof Slot) return (Slot)r; }
        } catch (Throwable ignored) {}
        return null;
    }
    private void shiftClickSlotAt(GuiContainer gui, int mx, int my) {
        try {
            Slot s=getSlotAt(gui,mx,my);
            if (s!=null) mc.playerController.windowClick(mc.thePlayer.openContainer.windowId,s.slotNumber,0,1,mc.thePlayer);
        } catch (Throwable t) { log("shiftClick: "+t); }
    }
    private void simulateScroll(GuiScreen screen, int mx, int my, int dir) {
        if (screen instanceof GuiContainer) shiftClickSlotAt((GuiContainer)screen, mx, my);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void closeGuiProperly(GuiScreen screen) {
        if (screen==null || mc.thePlayer==null) return;
        if (screen instanceof GuiContainer) mc.thePlayer.closeScreen();
        else { try{screen.onGuiClosed();}catch(Throwable ignored){} mc.displayGuiScreen(null); mc.setIngameFocus(); }
    }

    private void openPauseMenu() {
        try { Class<?> c=Class.forName("net.minecraft.client.gui.GuiIngameMenu"); mc.displayGuiScreen((GuiScreen)c.newInstance()); }
        catch (Throwable t) { log("pause: "+t); }
    }

    private void releaseMovementKeys() {
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindBack.keyCode,    false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindLeft.keyCode,    false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindRight.keyCode,   false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode,    false);
        KeyBinding.setKeyBindState(mc.gameSettings.keyBindSneak.keyCode,   false);
    }

    private static float processAxis(float v, float dz) {
        float abs=Math.abs(v); if(abs<=dz)return 0f;
        float sign=v<0?-1f:1f; float n=(abs-dz)/(1f-dz); return sign*n*n;
    }
    private static float clamp(float v,float min,float max){return v<min?min:(v>max?max:v);}
}