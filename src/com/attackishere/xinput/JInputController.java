package com.attackishere.xinput;

import net.java.games.input.Component;
import net.java.games.input.Component.Identifier;
import net.java.games.input.Component.Identifier.Axis;
import net.java.games.input.Component.Identifier.Button;
import net.java.games.input.Component.POV;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;

/**
 * Cross-platform controller backend using JInput (bundled with Minecraft's LWJGL).
 * (Updated: robust D-pad detection: POV, named hat/pov components, or button fallbacks.)
 */
public class JInputController {

    public enum InitResult { OK, NO_CONTROLLER, ENVIRONMENT_BROKEN }

    private enum Layout { STANDARD, ZRZ_RSTICK, SHARED_Z, UNKNOWN }
    private Layout layout = Layout.UNKNOWN;

    private Controller controller = null;

    // Resolved axis components
    private Component compLX, compLY, compRX, compRY;
    private Component compLT, compRT;  // null if triggers are digital buttons
    private Component compPOV;

    // Resolved button components sized to whatever the controller reports
    private Component[] buttons = new Component[0];

    // Button index mapping
    private int btnA, btnB, btnX, btnY;
    private int btnLB, btnRB;
    private int btnBack, btnStart;
    private int btnLStick, btnRStick;
    private int btnLT = -1, btnRT = -1;

    // Optional button fallbacks for dpad (when no POV)
    private int btnDpadUp = -1, btnDpadDown = -1, btnDpadLeft = -1, btnDpadRight = -1;

    private boolean initialised = false;

    // =========================================================================
    // Public accessors  return the detected JInput button index for each
    // logical button. Used by XInputTickHandler when forwarding button presses
    // to the controller settings GUI so bindings store the correct index.
    // =========================================================================

    public int btnA()      { return btnA; }
    public int btnB()      { return btnB; }
    public int btnX()      { return btnX; }
    public int btnY()      { return btnY; }
    public int btnLB()     { return btnLB; }
    public int btnRB()     { return btnRB; }
    public int btnBack()   { return btnBack; }
    public int btnStart()  { return btnStart; }
    public int btnLStick() { return btnLStick; }
    public int btnRStick() { return btnRStick; }

    /**
     * Returns true if the button at the given JInput index is currently pressed.
     * Used by isActionPressed() to evaluate user-configured bindings by index,
     * so the correct physical button is used regardless of layout/platform.
     *
     * Falls back to named cs.* fields for known indices so triggers/dpad
     * handled elsewhere still work even if cs.* was set from a different path.
     */
    public boolean rawButtonPressed(int index, ControllerState cs) {
        if (index < 0) return false;
        // Map known logical indices back to cs.* fields so the result is
        // always consistent with what poll() already computed.
        if (index == btnA)      return cs.a;
        if (index == btnB)      return cs.b;
        if (index == btnX)      return cs.x;
        if (index == btnY)      return cs.y;
        if (index == btnLB)     return cs.lb;
        if (index == btnRB)     return cs.rb;
        if (index == btnBack)   return cs.back;
        if (index == btnStart)  return cs.start;
        if (index == btnLStick) return cs.lThumb;
        if (index == btnRStick) return cs.rThumb;
        // For any other index (e.g. extra buttons on unusual controllers)
        // read directly from the buttons array populated during poll().
        return btn(index);
    }

    // =========================================================================
    // Init
    // =========================================================================

    public InitResult init() {
        if (initialised) return controller != null ? InitResult.OK : InitResult.NO_CONTROLLER;
        initialised = true;

        Controller[] all = null;
        try {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                all = getControllersWindows();
            } else {
                all = ControllerEnvironment.getDefaultEnvironment().getControllers();
            }
        } catch (Throwable t) {
            System.out.println("[XInputMod] JInput environment failed: " + t);
            initialised = false;
            return InitResult.ENVIRONMENT_BROKEN;
        }

        if (all == null || all.length == 0) {
            System.out.println("[XInputMod] JInput returned no controllers.");
            return InitResult.NO_CONTROLLER;
        }

        for (Controller c : all) {
            try { if (c != null && c.getType() == Controller.Type.GAMEPAD) { controller = c; break; } }
            catch (Throwable ignored) {}
        }
        if (controller == null) {
            for (Controller c : all) {
                try { if (c != null && c.getType() == Controller.Type.STICK) { controller = c; break; } }
                catch (Throwable ignored) {}
            }
        }

        if (controller == null) {
            System.out.println("[XInputMod] JInput: no gamepad/stick found. Controllers seen:");
            for (Controller c : all) {
                try { System.out.println("[XInputMod]   " + c.getName() + " type=" + c.getType()); }
                catch (Throwable ignored) {}
            }
            return InitResult.NO_CONTROLLER;
        }

        try {
            System.out.println("[XInputMod] JInput controller: " + controller.getName()
                + " (" + controller.getType() + ")");
            resolveComponents();
            return InitResult.OK;
        } catch (Throwable t) {
            System.out.println("[XInputMod] JInput init failed: " + t);
            controller = null;
            return InitResult.NO_CONTROLLER;
        }
    }

    private Controller[] getControllersWindows() {
        String[] plugins = {
            "net.java.games.input.DirectInputEnvironmentPlugin",
            "net.java.games.input.DirectAndRawInputEnvironmentPlugin",
        };
        for (String cls : plugins) {
            try {
                Class<?> c = Class.forName(cls);
                Object plugin = c.newInstance();
                Controller[] result = (Controller[]) c.getMethod("getControllers").invoke(plugin);
                if (result != null) {
                    System.out.println("[XInputMod] JInput plugin: " + cls + " (" + result.length + " controllers)");
                    return result;
                }
            } catch (Throwable t) {
                System.out.println("[XInputMod] Plugin " + cls + " failed: " + t);
            }
        }
        System.out.println("[XInputMod] Falling back to DefaultControllerEnvironment.");
        return ControllerEnvironment.getDefaultEnvironment().getControllers();
    }

    // =========================================================================
    // Component resolution
    // =========================================================================

    private void resolveComponents() {
        Component[] comps = controller.getComponents();

        System.out.println("[XInputMod] JInput components:");
        for (Component c : comps) {
            try {
                System.out.println("[XInputMod]   id=" + c.getIdentifier()
                    + " name=\"" + c.getName() + "\" analog=" + c.isAnalog());
            } catch (Throwable ignored) {}
        }

        // Collect named axes
        Component axX  = find(comps, Axis.X);
        Component axY  = find(comps, Axis.Y);
        Component axZ  = find(comps, Axis.Z);
        Component axRX = find(comps, Axis.RX);
        Component axRY = find(comps, Axis.RY);
        Component axRZ = find(comps, Axis.RZ);

        // Detect layout
        boolean hasRXRY    = axRX != null && axRY != null;
        boolean hasZandRZ  = axZ  != null && axRZ != null;
        boolean hasZonly   = axZ  != null && axRZ == null;

        if (hasRXRY) {
            compLX = axX;  compLY = axY;
            compRX = axRX; compRY = axRY;
            if (hasZandRZ) {
                compLT = axZ; compRT = axRZ;
                layout = Layout.STANDARD;
                System.out.println("[XInputMod] Layout: STANDARD (RX/RY sticks, Z/RZ triggers)");
            } else if (hasZonly) {
                compLT = axZ; compRT = axZ;
                layout = Layout.SHARED_Z;
                System.out.println("[XInputMod] Layout: SHARED_Z (RX/RY sticks, shared Z trigger)");
            } else {
                compLT = null; compRT = null;
                layout = Layout.STANDARD;
                System.out.println("[XInputMod] Layout: STANDARD (RX/RY sticks, no analog triggers)");
            }
        } else if (hasZandRZ) {
            compLX = axX;  compLY = axY;
            compRX = axZ;  compRY = axRZ;
            compLT = null; compRT = null;
            layout = Layout.ZRZ_RSTICK;
            System.out.println("[XInputMod] Layout: ZRZ_RSTICK (Z/RZ = right stick, digital triggers)");
        } else {
            compLX = axX;  compLY = axY;
            compRX = axRX != null ? axRX : axZ;
            compRY = axRY != null ? axRY : axRZ;
            compLT = null; compRT = null;
            layout = Layout.UNKNOWN;
            System.out.println("[XInputMod] Layout: UNKNOWN (best-effort axis mapping)");
        }

        // Collect buttons
        java.util.List<Component> btnList = new java.util.ArrayList<Component>();
        for (Component c : comps)
            if (c.getIdentifier() instanceof Button) btnList.add(c);
        buttons = btnList.toArray(new Component[0]);
        System.out.println("[XInputMod] Button count: " + buttons.length);

        // Default index map (fallback)
        if (buttons.length >= 13) {
            btnA=0; btnB=1; btnX=2; btnY=3;
            btnLB=4; btnRB=5;
            btnLT=6; btnRT=7;
            btnBack=8; btnStart=9;
            btnLStick=11; btnRStick=12;
            System.out.println("[XInputMod] Default button map: Xbox Wireless (13+ btn)");
        } else {
            btnA=0; btnB=1; btnX=2; btnY=3;
            btnLB=4; btnRB=5;
            btnLT=-1; btnRT=-1;
            btnBack=6; btnStart=7;
            btnLStick=8; btnRStick=9;
            System.out.println("[XInputMod] Default button map: Xbox 360 standard (10 btn)");
        }

        // Name-based detection for Start/Back/LStick/RStick (as before)
        int foundStart = -1, foundBack = -1, foundLStick = -1, foundRStick = -1;
        for (int i = 0; i < buttons.length; i++) {
            Component b = buttons[i];
            if (b == null) continue;
            String name = b.getName();
            if (name == null) name = "";
            String n = name.toLowerCase();

            if (foundStart == -1 && (n.contains("start") || n.contains("menu") || n.contains("options")
                    || n.contains("guide") || n.contains("pause") || n.contains("mode"))) {
                foundStart = i;
            }
            if (foundBack == -1 && (n.contains("back") || n.contains("select") || n.contains("view"))) {
                foundBack = i;
            }
            if (foundLStick == -1 && (n.contains("lstick") || n.contains("left stick") || n.contains("left thumb")
                    || n.contains("l thumb") || (n.contains("stick") && n.contains("left")))) {
                foundLStick = i;
            }
            if (foundRStick == -1 && (n.contains("rstick") || n.contains("right stick") || n.contains("right thumb")
                    || n.contains("r thumb") || (n.contains("stick") && n.contains("right")))) {
                foundRStick = i;
            }
        }

        if (foundStart >= 0 && foundStart < buttons.length) {
            if (foundStart != btnA && foundStart != btnB && foundStart != btnX && foundStart != btnY) {
                btnStart = foundStart;
                System.out.println("[XInputMod] Detected Start button at index " + btnStart + " (name=\"" +
                    safeButtonName(btnStart) + "\")");
            }
        }
        if (foundBack >= 0 && foundBack < buttons.length) {
            if (foundBack != btnA && foundBack != btnB && foundBack != btnX && foundBack != btnY) {
                btnBack = foundBack;
                System.out.println("[XInputMod] Detected Back/Select button at index " + btnBack + " (name=\"" +
                    safeButtonName(btnBack) + "\")");
            }
        }
        if (foundLStick >= 0 && foundLStick < buttons.length) {
            btnLStick = foundLStick;
            System.out.println("[XInputMod] Detected LStick button at index " + btnLStick + " (name=\"" +
                safeButtonName(btnLStick) + "\")");
        }
        if (foundRStick >= 0 && foundRStick < buttons.length) {
            btnRStick = foundRStick;
            System.out.println("[XInputMod] Detected RStick button at index " + btnRStick + " (name=\"" +
                safeButtonName(btnRStick) + "\")");
        }

        // ------------------------
        // D-Pad / POV detection
        // ------------------------

        // try official POV axis first
        compPOV = find(comps, Axis.POV);

        // if not found, look for any component whose identifier or name mentions hat/pov/dpad
        if (compPOV == null) {
            for (Component c : comps) {
                try {
                    String idStr = c.getIdentifier() == null ? "" : c.getIdentifier().toString().toLowerCase();
                    String name = c.getName() == null ? "" : c.getName().toLowerCase();
                    if (idStr.contains("pov") || idStr.contains("hat") || name.contains("pov") || name.contains("hat") || name.contains("dpad") || name.contains("hatswitch")) {
                        compPOV = c;
                        System.out.println("[XInputMod] Detected POV/hat component by name/identifier: id=" + c.getIdentifier() + " name=\"" + c.getName() + "\"");
                        break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        // If we still have no POV component, try to detect dpad buttons by name
        if (compPOV == null) {
            for (int i = 0; i < buttons.length; i++) {
                Component b = buttons[i];
                if (b == null) continue;
                String name = b.getName() == null ? "" : b.getName().toLowerCase();
                if (btnDpadUp == -1 && (name.contains("dpad up") || name.contains("dpad_up") || name.contains("up") && name.contains("dpad") || name.contains("hat up") || name.contains("d-pad up") || name.equals("up")))
                    btnDpadUp = i;
                if (btnDpadDown == -1 && (name.contains("dpad down") || name.contains("dpad_down") || name.contains("down") && name.contains("dpad") || name.contains("hat down") || name.contains("d-pad down") || name.equals("down")))
                    btnDpadDown = i;
                if (btnDpadLeft == -1 && (name.contains("dpad left") || name.contains("dpad_left") || name.contains("left") && name.contains("dpad") || name.contains("hat left") || name.contains("d-pad left") || name.equals("left")))
                    btnDpadLeft = i;
                if (btnDpadRight == -1 && (name.contains("dpad right") || name.contains("dpad_right") || name.contains("right") && name.contains("dpad") || name.contains("hat right") || name.contains("d-pad right") || name.equals("right")))
                    btnDpadRight = i;
            }

            if (btnDpadUp >= 0 || btnDpadDown >= 0 || btnDpadLeft >= 0 || btnDpadRight >= 0) {
                System.out.println("[XInputMod] Detected D-pad as buttons: up=" + btnDpadUp + " down=" + btnDpadDown + " left=" + btnDpadLeft + " right=" + btnDpadRight);
            } else {
                System.out.println("[XInputMod] No POV/hatswitch found and no named dpad buttons detected will attempt POV-only and button-fallback reading at runtime.");
            }
        } else {
            System.out.println("[XInputMod] Using POV component: id=" + compPOV.getIdentifier() + " name=\"" + compPOV.getName() + "\"");
        }

        // Final sanity check for Start conflicting with L/R stick
        if (btnStart == btnLStick || btnStart == btnRStick) {
            System.out.println("[XInputMod] Warning: start index conflicts with stick index; reverting to fallback index.");
            if (buttons.length >= 13) btnStart = 9;
            else btnStart = 7;
            System.out.println("[XInputMod] Using fallback Start index " + btnStart + " (name=\"" + safeButtonName(btnStart) + "\")");
        }

        // Debug dump of final effective mapping
        System.out.println("[XInputMod] Final button mapping:");
        System.out.println("[XInputMod]  A=" + btnA + " B=" + btnB + " X=" + btnX + " Y=" + btnY);
        System.out.println("[XInputMod]  LB=" + btnLB + " RB=" + btnRB + " LT=" + btnLT + " RT=" + btnRT);
        System.out.println("[XInputMod]  Back=" + btnBack + " Start=" + btnStart);
        System.out.println("[XInputMod]  LStick=" + btnLStick + " RStick=" + btnRStick);
    }

    private String safeButtonName(int idx) {
        if (idx < 0 || idx >= buttons.length || buttons[idx] == null) return "<n/a>";
        String n = buttons[idx].getName();
        return n == null ? "<unnamed>" : n;
    }

    private Component find(Component[] comps, Identifier id) {
        for (Component c : comps)
            if (c.getIdentifier() == id) return c;
        return null;
    }

    // =========================================================================
    // Poll
    // =========================================================================

    public boolean poll(ControllerState cs) {
        if (controller == null) return false;
        try {
            if (!controller.poll()) {
                System.out.println("[XInputMod] JInput controller disconnected.");
                controller = null; initialised = false; cs.zero();
                return false;
            }

            // Sticks
            cs.lx =  readAxis(compLX);
            cs.ly = -readAxis(compLY);  // JInput Y is inverted
            cs.rx =  readAxis(compRX);
            cs.ry = -readAxis(compRY);

            // Triggers
            if (layout == Layout.SHARED_Z) {
                float z = readAxis(compLT);
                cs.lt = z > 0f ?  z : 0f;
                cs.rt = z < 0f ? -z : 0f;
            } else if (compLT != null) {
                cs.lt = (readAxis(compLT) + 1f) * 0.5f;
                cs.rt = (readAxis(compRT) + 1f) * 0.5f;
            } else {
                cs.lt = (btnLT >= 0 && btnLT < buttons.length
                    && buttons[btnLT] != null && buttons[btnLT].getPollData() > 0.5f) ? 1f : 0f;
                cs.rt = (btnRT >= 0 && btnRT < buttons.length
                    && buttons[btnRT] != null && buttons[btnRT].getPollData() > 0.5f) ? 1f : 0f;
            }

            // Face / shoulder buttons
            cs.a      = btn(btnA);
            cs.b      = btn(btnB);
            cs.x      = btn(btnX);
            cs.y      = btn(btnY);
            cs.lb     = btn(btnLB);
            cs.rb     = btn(btnRB);
            cs.back   = btn(btnBack);
            cs.start  = btn(btnStart);
            cs.lThumb = btn(btnLStick);
            cs.rThumb = btn(btnRStick);

            // Read D-pad / POV (robust)
            readPOV(cs);

            return true;

        } catch (Throwable t) {
            System.out.println("[XInputMod] JInput poll error: " + t);
            controller = null; initialised = false; cs.zero();
            return false;
        }
    }

    public boolean isAvailable() { return controller != null; }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float readAxis(Component c) {
        if (c == null) return 0f;
        float v = c.getPollData();
        return Float.isNaN(v) ? 0f : v;
    }

    private boolean btn(int idx) {
        if (idx < 0 || idx >= buttons.length || buttons[idx] == null) return false;
        return buttons[idx].getPollData() > 0.5f;
    }

    private void readPOV(ControllerState cs) {
        cs.dpadUp = cs.dpadDown = cs.dpadLeft = cs.dpadRight = false;

        if (compPOV != null) {
            try {
                float pov = compPOV.getPollData();
                if (pov == POV.OFF) return;

                final float EPS = 0.02f;
                if (Math.abs(pov - 0.25f) < EPS || (pov > 0.125f && pov < 0.375f)) cs.dpadUp = true;
                if (Math.abs(pov - 0.50f) < EPS || (pov > 0.375f && pov < 0.625f)) cs.dpadRight = true;
                if (Math.abs(pov - 0.75f) < EPS || (pov > 0.625f && pov < 0.875f)) cs.dpadDown = true;
                if (Math.abs(pov - 1.00f) < EPS || pov > 0.875f || pov < 0.125f)   cs.dpadLeft = true;
                return;
            } catch (Throwable ignored) {}
        }

        if (btnDpadUp    >= 0 && btnDpadUp    < buttons.length && buttons[btnDpadUp]    != null && buttons[btnDpadUp].getPollData()    > 0.5f) cs.dpadUp    = true;
        if (btnDpadDown  >= 0 && btnDpadDown  < buttons.length && buttons[btnDpadDown]  != null && buttons[btnDpadDown].getPollData()  > 0.5f) cs.dpadDown  = true;
        if (btnDpadLeft  >= 0 && btnDpadLeft  < buttons.length && buttons[btnDpadLeft]  != null && buttons[btnDpadLeft].getPollData()  > 0.5f) cs.dpadLeft  = true;
        if (btnDpadRight >= 0 && btnDpadRight < buttons.length && buttons[btnDpadRight] != null && buttons[btnDpadRight].getPollData() > 0.5f) cs.dpadRight = true;

        if (!(cs.dpadUp || cs.dpadDown || cs.dpadLeft || cs.dpadRight)) {
            for (int i = 0; i < buttons.length; i++) {
                Component b = buttons[i];
                if (b == null) continue;
                String name = b.getName() == null ? "" : b.getName().toLowerCase();
                float pd = b.getPollData();
                if (pd <= 0.5f) continue;
                if (name.contains("up")    || name.contains("dpad up")    || name.contains("hat up"))    cs.dpadUp    = true;
                if (name.contains("down")  || name.contains("dpad down")  || name.contains("hat down"))  cs.dpadDown  = true;
                if (name.contains("left")  || name.contains("dpad left")  || name.contains("hat left"))  cs.dpadLeft  = true;
                if (name.contains("right") || name.contains("dpad right") || name.contains("hat right")) cs.dpadRight = true;
            }
        }
    }
}