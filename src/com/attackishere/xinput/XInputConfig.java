package com.attackishere.xinput;

import net.minecraftforge.common.Configuration;
import java.io.File;

public class XInputConfig {

    private static final String CAT          = "general";
    private static final String CAT_BINDINGS = "bindings";

    public static final int UNDETECTED = -99;

    private final Configuration forge;

    public boolean enableController = true;
    public float   lookSpeedX  = 0.5f;
    public float   lookSpeedY  = 0.5f;
    public float   deadzone    = 0.25f;

    private final int[] bindings = new int[ControllerAction.values().length];

    // Fallback defaults  must stay aligned with ControllerAction ordinals.
    // -1 means "use hardware default" (handled in isActionPressed switch).
    // Sentinels from XInputTickHandler are used for dpad/trigger bindings.
    //
    // New default layout (matches this controller's ZRZ_RSTICK + 15-button map):
    //   Sneak  = dpad down   (sentinel BIND_DPAD_DOWN = -111)
    //   Sprint = dpad up     (sentinel BIND_DPAD_UP   = -110)
    //   Hotbar = dpad left/right (-1 = hardware default = cs.dpadLeft/Right)
    private static final int[] FALLBACK_DEFAULTS = new int[]{
        /* JUMP           */  0,    // A
        /* ATTACK         */ -1,    // RT (hardware default)
        /* USE_ITEM       */ -1,    // LT (hardware default)
        /* SNEAK          */ -111,  // dpad down (BIND_DPAD_DOWN)
        /* SPRINT         */ -110,  // dpad up   (BIND_DPAD_UP)
        /* INVENTORY      */  3,    // Y
        /* DROP_ITEM      */  1,    // B
        /* HOTBAR_PREV    */ -1,    // dpad left (hardware default)
        /* HOTBAR_NEXT    */ -1,    // dpad right (hardware default)
        /* RECIPE_BROWSER */  8,    // Back (wireless index)
        /* PAUSE          */  9,    // Start (wireless index)
        /* CHAT           */  8,    // Back
        /* THIRD_PERSON   */ -1,    // unbound (was dpad up, now taken by sprint)
        /* HIDE_HUD       */ -1,    // unbound
    };

    public XInputConfig(File configFile) {
        forge = new Configuration(configFile);
        load();
    }

    public void load() {
        forge.load();
        enableController = forge.get(CAT, "EnableController", true).getBoolean(true);
        lookSpeedX = (float) forge.get(CAT, "LookSpeedX",  0.5).getDouble(0.5);
        lookSpeedY = (float) forge.get(CAT, "LookSpeedY",  0.5).getDouble(0.5);
        deadzone   = (float) forge.get(CAT, "Deadzone",   0.25).getDouble(0.25);

        for (ControllerAction action : ControllerAction.values()) {
            int fallback = fallbackFor(action);
            int saved = forge.get(CAT_BINDINGS, action.name(), UNDETECTED).getInt(UNDETECTED);
            bindings[action.ordinal()] = (saved == UNDETECTED) ? fallback : saved;
        }
        try { forge.save(); } catch (Throwable ignored) {}
    }

    public void save() {
        try {
            forge.get(CAT, "EnableController", true).value = String.valueOf(enableController);
            forge.get(CAT, "LookSpeedX",  0.5).value = String.valueOf(lookSpeedX);
            forge.get(CAT, "LookSpeedY",  0.5).value = String.valueOf(lookSpeedY);
            forge.get(CAT, "Deadzone",   0.25).value = String.valueOf(deadzone);
            for (ControllerAction action : ControllerAction.values())
                forge.get(CAT_BINDINGS, action.name(), UNDETECTED).value = String.valueOf(bindings[action.ordinal()]);
            forge.save();
        } catch (Throwable t) { System.out.println("[XInputMod] save failed: " + t); }
    }

    /**
     * Called once after JInputController detects the plugged-in controller.
     * Writes the actual JInput button indices for buttons that are still at
     * their fallback values (i.e. user hasn't manually remapped them).
     * Sentinels (-1, -110, -111, etc.) are left alone  they're correct
     * regardless of controller layout.
     */
    public void applyDetectedDefaults(JInputController jinput) {
        boolean changed = false;

        int[] detected = new int[]{
            /* JUMP           */ jinput.btnA(),
            /* ATTACK         */ -1,          // always RT via cs.rt
            /* USE_ITEM       */ -1,          // always LT via cs.lt
            /* SNEAK          */ -111,        // always dpad down
            /* SPRINT         */ -110,        // always dpad up
            /* INVENTORY      */ jinput.btnY(),
            /* DROP_ITEM      */ jinput.btnB(),
            /* HOTBAR_PREV    */ -1,          // always dpad left
            /* HOTBAR_NEXT    */ -1,          // always dpad right
            /* RECIPE_BROWSER */ jinput.btnBack(),
            /* PAUSE          */ jinput.btnStart(),
            /* CHAT           */ jinput.btnBack(),
            /* THIRD_PERSON   */ -1,
            /* HIDE_HUD       */ -1,
        };

        for (ControllerAction action : ControllerAction.values()) {
            int ord = action.ordinal();
            if (ord >= detected.length) continue;
            int current  = bindings[ord];
            int fallback = fallbackFor(action);
            int det      = detected[ord];
            if ((current == fallback || current == UNDETECTED) && current != det) {
                bindings[ord] = det;
                changed = true;
            }
        }

        if (changed) {
            System.out.println("[XInputMod] Applied detected defaults, saving.");
            save();
        } else {
            System.out.println("[XInputMod] Detected defaults match saved config.");
        }
    }

    public int     getBinding(ControllerAction a)           { return bindings[a.ordinal()]; }
    public void    setBinding(ControllerAction a, int idx)  { bindings[a.ordinal()] = idx; }
    public boolean matches(ControllerAction a, int idx)     { return bindings[a.ordinal()] == idx; }

    private int fallbackFor(ControllerAction action) {
        int ord = action.ordinal();
        return ord < FALLBACK_DEFAULTS.length ? FALLBACK_DEFAULTS[ord] : -1;
    }
}