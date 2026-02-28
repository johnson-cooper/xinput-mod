package com.attackishere.xinput;

import net.java.games.input.Component;
import net.java.games.input.Component.Identifier;
import net.java.games.input.Component.Identifier.Axis;
import net.java.games.input.Component.Identifier.Button;
import net.java.games.input.Component.POV;
import net.java.games.input.Controller;
import net.java.games.input.ControllerEnvironment;

/**
 * Crossplatform controller backend using JInput, which ships bundled with
 * Minecraft 1.4.7's LWJGL installation  no extra dependencies needed.
 *
 * Xbox controller axis layout varies by OS and driver:
 *
 *   Windows (XInput via JInput wrapper):
 *     LX=X  LY=Y  RX=RX  RY=RY  Triggers share Z axis (LT negative, RT positive)
 *
 *   macOS / Linux (HID driver):
 *     LX=X  LY=Y  RX=RX  RY=RY  LT=Z  RT=RZ  (separate axes)
 *     Some Linux drivers: RX=Z  RY=RZ  LT=RX  RT=RY  (varies by driver)
 *
 * We detect which layout is in use at init time by inspecting which
 * Component identifiers are actually present on the found controller,
 * then read accordingly.
 *
 * Dpad is reported as a POV hat returning cardinal/diagonal floats:
 *   UP=0.25  RIGHT=0.5  DOWN=0.75  LEFT=1.0  (0.0 = centred)
 *   Diagonals: UP_RIGHT=0.375  DOWN_RIGHT=0.625  DOWN_LEFT=0.875  UP_LEFT=0.125
 *
 * Buttons are found by index (0based) since JInput button identifiers
 * are consistent across platforms for standard gamepads.
 */
public class JInputController {

    /** Result of an init() call so the caller can distinguish failure modes. */
    public enum InitResult {
        OK,                  // controller found and ready
        NO_CONTROLLER,       // environment ok but no gamepad/stick detected
        ENVIRONMENT_BROKEN   // JInput's RawInput plugin crashed  unrecoverable
    }

    private Controller controller = null;

    // Axis components resolved at init
    private Component compLX, compLY, compRX, compRY;
    private Component compLT, compRT;
    private Component compPOV;

    // Button components by index
    // Standard Xbox layout: A=0 B=1 X=2 Y=3 LB=4 RB=5 Back=6 Start=7 LStick=8 RStick=9
    private static final int BTN_A=0, BTN_B=1, BTN_X=2, BTN_Y=3;
    private static final int BTN_LB=4, BTN_RB=5;
    private static final int BTN_BACK=6, BTN_START=7;
    private static final int BTN_LSTICK=8, BTN_RSTICK=9;
    private static final int MAX_BUTTONS = 10;

    private final Component[] buttons = new Component[MAX_BUTTONS];

    // Whether LT/RT share a single Z axis (Windows XInput mode)
    private boolean sharedTriggerAxis = false;

    private boolean initialised = false;

    // =========================================================================
    // Init
    // =========================================================================

    public InitResult init() {
        if (initialised) return controller != null ? InitResult.OK : InitResult.NO_CONTROLLER;
        initialised = true;

        // On Windows, DefaultControllerEnvironment loads ALL plugins including
        // RawInputEnvironmentPlugin, which NPEs on certain HID devices (ITE Device,
        // some touchpads, etc.) and crashes the entire enumeration before your
        // controller is ever reached.
        //
        // Fix: on Windows, directly instantiate DirectInputEnvironmentPlugin,
        // which skips RawInput entirely and sees XInput/DirectInput controllers
        // cleanly. On macOS and Linux, DefaultControllerEnvironment works fine
        // so we use it as normal.
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

        // Prefer GAMEPAD, fall back to STICK, skip any that throw on getType()
        for (Controller c : all) {
            try {
                if (c != null && c.getType() == Controller.Type.GAMEPAD) {
                    controller = c; break;
                }
            } catch (Throwable ignored) {}
        }
        if (controller == null) {
            for (Controller c : all) {
                try {
                    if (c != null && c.getType() == Controller.Type.STICK) {
                        controller = c; break;
                    }
                } catch (Throwable ignored) {}
            }
        }

        if (controller == null) {
            System.out.println("[XInputMod] JInput: no gamepad/stick found.");
            System.out.println("[XInputMod] Available controllers:");
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

    /**
     * On Windows, directly instantiate DirectInputEnvironmentPlugin rather than
     * going through DefaultControllerEnvironment, which also loads
     * RawInputEnvironmentPlugin and crashes on certain HID devices.
     *
     * DirectInputEnvironmentPlugin is in the same jinput jar and handles
     * both legacy DirectInput controllers and XInput controllers (via the
     * DirectInput compatibility layer Microsoft provides for XInput devices).
     *
     * We instantiate it via reflection so the code still compiles and runs
     * on macOS/Linux where the Windowsspecific plugin class doesn't exist.
     */
    private Controller[] getControllersWindows() {
        // Try DirectInputEnvironmentPlugin first (no RawInput, no NPE)
        String[] windowsPlugins = {
            "net.java.games.input.DirectInputEnvironmentPlugin",
            "net.java.games.input.DirectAndRawInputEnvironmentPlugin", // fallback
        };
        for (String className : windowsPlugins) {
            try {
                Class<?> cls = Class.forName(className);
                Object plugin = cls.newInstance();
                java.lang.reflect.Method getControllers =
                    cls.getMethod("getControllers");
                Controller[] controllers = (Controller[]) getControllers.invoke(plugin);
                if (controllers != null) {
                    System.out.println("[XInputMod] JInput using plugin: " + className
                        + " (" + controllers.length + " controllers)");
                    return controllers;
                }
            } catch (Throwable t) {
                System.out.println("[XInputMod] Plugin " + className + " failed: " + t);
            }
        }
        // Last resort  try default and hope for the best
        System.out.println("[XInputMod] Falling back to DefaultControllerEnvironment.");
        return ControllerEnvironment.getDefaultEnvironment().getControllers();
    }

    private void resolveComponents() {
        Component[] comps = controller.getComponents();

        // Log all components at init so users can debug unexpected layouts
        System.out.println("[XInputMod] JInput components:");
        for (Component c : comps)
            System.out.println("[XInputMod]   id=" + c.getIdentifier()
                + " name=" + c.getName() + " analog=" + c.isAnalog());

        //  Sticks 
        compLX = find(comps, Axis.X);
        compLY = find(comps, Axis.Y);
        compRX = find(comps, Axis.RX);
        compRY = find(comps, Axis.RY);

        //  Triggers 
        // Case 1: separate Z and RZ (macOS/Linux HID, most common)
        Component z  = find(comps, Axis.Z);
        Component rz = find(comps, Axis.RZ);

        if (z != null && rz != null) {
            // Both present  Z=LT, RZ=RT (standard HID)
            compLT = z;
            compRT = rz;
            sharedTriggerAxis = false;
            System.out.println("[XInputMod] Trigger mode: separate Z/RZ axes");
        } else if (z != null) {
            // Only Z  Windows XInput shared trigger axis (LT pulls negative, RT pulls positive)
            compLT = z;
            compRT = z;
            sharedTriggerAxis = true;
            System.out.println("[XInputMod] Trigger mode: shared Z axis (Windows XInput)");
        } else {
            // Some Linux drivers put right stick on Z/RZ and triggers elsewhere.
            // In that case RX/RY might not have been found above either.
            // Reassign: if RX/RY are missing, try Z/RZ for right stick instead.
            if (compRX == null && z  != null) { compRX = z;  compLT = null; }
            if (compRY == null && rz != null) { compRY = rz; compRT = null; }
            System.out.println("[XInputMod] Trigger mode: none found (no trigger input)");
        }

        //  POV / Dpad 
        compPOV = find(comps, Axis.POV);

        //  Buttons 
        // Collect all button components in order, map by index
        int idx = 0;
        for (Component c : comps) {
            if (c.getIdentifier() instanceof Button && idx < MAX_BUTTONS) {
                buttons[idx++] = c;
            }
        }
        System.out.println("[XInputMod] JInput: found " + idx + " buttons");
    }

    private Component find(Component[] comps, Identifier id) {
        for (Component c : comps)
            if (c.getIdentifier() == id) return c;
        return null;
    }

    // =========================================================================
    // Poll
    // =========================================================================

    /**
     * Poll the controller and fill cs with normalised state.
     * Returns false if the controller is gone.
     */
    public boolean poll(ControllerState cs) {
        if (controller == null) return false;
        try {
            if (!controller.poll()) {
                System.out.println("[XInputMod] JInput controller disconnected.");
                controller = null;
                initialised = false;
                cs.zero();
                return false;
            }

            cs.lx =  readAxis(compLX);
            cs.ly = -readAxis(compLY);   // JInput Y is inverted vs what we expect
            cs.rx =  readAxis(compRX);
            cs.ry = -readAxis(compRY);   // same

            if (sharedTriggerAxis) {
                // Shared Z axis: on this controller (Xbox 360 via DirectInput)
                // RT pulls negative and LT pulls positive  opposite of what the
                // comment said. Confirmed by component log showing triggers swapped.
                float z = readAxis(compLT);
                cs.lt = z > 0f ?  z : 0f;  // LT = positive half
                cs.rt = z < 0f ? -z : 0f;  // RT = negative half
            } else {
                // Separate axes, already in [1, 1], remap to [0, 1]
                cs.lt = compLT != null ? (readAxis(compLT) + 1f) * 0.5f : 0f;
                cs.rt = compRT != null ? (readAxis(compRT) + 1f) * 0.5f : 0f;
            }

            cs.a      = btnDown(BTN_A);
            cs.b      = btnDown(BTN_B);
            cs.x      = btnDown(BTN_X);
            cs.y      = btnDown(BTN_Y);
            cs.lb     = btnDown(BTN_LB);
            cs.rb     = btnDown(BTN_RB);
            cs.back   = btnDown(BTN_BACK);
            cs.start  = btnDown(BTN_START);
            cs.lThumb = btnDown(BTN_LSTICK);
            cs.rThumb = btnDown(BTN_RSTICK);

            readPOV(cs);

            return true;
        } catch (Throwable t) {
            System.out.println("[XInputMod] JInput poll error: " + t);
            controller = null;
            initialised = false;
            cs.zero();
            return false;
        }
    }

    public boolean isAvailable() {
        return controller != null;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private float readAxis(Component c) {
        if (c == null) return 0f;
        float v = c.getPollData();
        return Float.isNaN(v) ? 0f : v;
    }

    private boolean btnDown(int idx) {
        if (idx >= MAX_BUTTONS || buttons[idx] == null) return false;
        return buttons[idx].getPollData() > 0.5f;
    }

    private void readPOV(ControllerState cs) {
        cs.dpadUp    = false;
        cs.dpadDown  = false;
        cs.dpadLeft  = false;
        cs.dpadRight = false;

        if (compPOV == null) return;
        float pov = compPOV.getPollData();
        if (pov == POV.OFF || pov == 0f) return;

        // POV values: UP=0.25  RIGHT=0.5  DOWN=0.75  LEFT=1.0
        // Diagonals split the difference  we set both adjacent directions
        cs.dpadUp    = (pov >= 0.125f && pov <= 0.375f); // up and diagonals
        cs.dpadRight = (pov >= 0.375f && pov <= 0.625f);
        cs.dpadDown  = (pov >= 0.625f && pov <= 0.875f);
        cs.dpadLeft  = (pov >= 0.875f && pov <= 1.0f) || pov < 0.125f;
    }
}