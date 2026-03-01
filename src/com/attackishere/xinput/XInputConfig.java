package com.attackishere.xinput;

import net.minecraftforge.common.Configuration;

import java.io.File;

/**
 * Holds all configurable values for the controller mod.
 * Reads from and writes to the Forge config file.
 *
 * Default button bindings match the Xbox 360 standard layout (10-button):
 *   A=0 B=1 X=2 Y=3 LB=4 RB=5 Back=6 Start=7 LStick=8 RStick=9
 */
public class XInputConfig {

    private static final String CAT = "general";
    private static final String CAT_BINDINGS = "bindings";

    private final Configuration forge;

    //  Settings
    public boolean enableController = true;
    public float   lookSpeedX  = 0.5f;   // 0..1 (displayed as 0..20)
    public float   lookSpeedY  = 0.5f;
    public float   deadzone    = 0.25f;  // 0..0.5

    //  Button bindings
    // Index = ControllerAction.ordinal(), value = JInput button index (-1 = unbound)
    private final int[] bindings = new int[ControllerAction.values().length];

    private static final int[] DEFAULTS = new int[]{
        /* JUMP          */ 0,   // A
        /* ATTACK        */ -1,  // RT (analog, not a button in 10-btn layout)
        /* USE_ITEM      */ -1,  // LT (analog)
        /* SNEAK         */ 9,   // RStick
        /* SPRINT        */ 8,   // LStick
        /* INVENTORY     */ 2,   // X
        /* DROP_ITEM     */ 1,   // B
        /* HOTBAR_PREV   */ 4,   // LB
        /* HOTBAR_NEXT   */ 5,   // RB
        /* RECIPE_BROWSER*/ 6,   // Back
        /* PAUSE         */ 7,   // Start
        /* CHAT          */ 6,   // Back (same as recipe browser context-sensitive)
        /* THIRD_PERSON  */ -1,  // dpad up (handled separately, no button index)
        /* HIDE_HUD      */ -1,  // dpad down
    };

    public XInputConfig(File configFile) {
        forge = new Configuration(configFile);
        load();
    }

    public void load() {
        forge.load();

        enableController = forge.get(CAT, "EnableController", true).getBoolean(true);
        lookSpeedX  = (float) forge.get(CAT, "LookSpeedX",  0.5).getDouble(0.5);
        lookSpeedY  = (float) forge.get(CAT, "LookSpeedY",  0.5).getDouble(0.5);
        deadzone    = (float) forge.get(CAT, "Deadzone",   0.25).getDouble(0.25);

        for (ControllerAction action : ControllerAction.values()) {
            int def = action.ordinal() < DEFAULTS.length ? DEFAULTS[action.ordinal()] : -1;
            bindings[action.ordinal()] = forge.get(CAT_BINDINGS,
                action.name(), def).getInt(def);
        }

        // Older Forge mappings may not have hasChanged(), so always save to ensure file is updated.
        try { forge.save(); } catch (Throwable ignored) {}
    }

    public void save() {
        try {
            // In Forge 1.4.7, we access the .value field directly on the Property object
            forge.get(CAT, "EnableController", true).value = String.valueOf(enableController);
            forge.get(CAT, "LookSpeedX", 0.5).value = String.valueOf(lookSpeedX);
            forge.get(CAT, "LookSpeedY", 0.5).value = String.valueOf(lookSpeedY);
            forge.get(CAT, "Deadzone", 0.25).value = String.valueOf(deadzone);

            for (ControllerAction action : ControllerAction.values()) {
                // Same here for the bindings
                forge.get(CAT_BINDINGS, action.name(), -1).value = String.valueOf(bindings[action.ordinal()]);
            }

            forge.save();
        } catch (Throwable t) {
            System.out.println("[XInputMod] Failed to save config: " + t);
        }
    }

    public int getBinding(ControllerAction action) {
        return bindings[action.ordinal()];
    }

    public void setBinding(ControllerAction action, int buttonIndex) {
        bindings[action.ordinal()] = buttonIndex;
    }

    /** Convenience: check if a raw JInput button index matches an action's binding. */
    public boolean matches(ControllerAction action, int buttonIndex) {
        return bindings[action.ordinal()] == buttonIndex;
    }
}