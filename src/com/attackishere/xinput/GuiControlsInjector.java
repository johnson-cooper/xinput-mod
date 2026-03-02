package com.attackishere.xinput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiControls;

import java.lang.reflect.Field;
import java.util.List;

public class GuiControlsInjector {

    private static final int BTN_CONTROLLER = 9876;
    private static boolean injected = false;

    public static void tick(Minecraft mc, XInputConfig config) {
        if (mc == null || mc.currentScreen == null) {
            injected = false;
            return;
        }
        if (!(mc.currentScreen instanceof GuiControls)) {
            injected = false;
            return;
        }

        try {
            GuiControls gui = (GuiControls) mc.currentScreen;

            //  Find buttonList field 
            // Obfuscation-safe: walk the class hierarchy looking for any List
            // field, since "buttonList" / "controlList" names won't survive
            // obfuscation. The first List field in GuiScreen is always buttonList.
            List<Object> buttons = getButtonList(gui);
            if (buttons == null) return;

            //  Check for duplicate / screen refresh 
            boolean foundOurBtn = false;
            for (Object o : buttons) {
                if (o instanceof GuiButton && ((GuiButton) o).id == BTN_CONTROLLER) {
                    foundOurBtn = true;
                    break;
                }
            }
            if (!foundOurBtn) injected = false;
            if (injected) return;

            //  Layout math 
            final int gap       = 4;
            final int ctrlWidth = 150;
            final int doneWidth = 74;
            final int startX    = (gui.width - ctrlWidth - gap - doneWidth) / 2;
            final int y         = gui.height - 28;

            // Shrink and reposition the vanilla Done button (id=200)
            for (Object o : buttons) {
                if (o instanceof GuiButton && ((GuiButton) o).id == 200) {
                    GuiButton done = (GuiButton) o;
                    done.xPosition = startX + ctrlWidth + gap;
                    setButtonWidth(done, doneWidth);
                    break;
                }
            }

            //  Inject 
            buttons.add(new GuiButtonController(
                BTN_CONTROLLER, startX, y, ctrlWidth, 20, "Controller Settings...", config
            ));
            injected = true;

        } catch (Throwable t) {
            System.out.println("[XInputMod] GuiControlsInjector failed: " + t);
        }
    }

    // =========================================================================
    // Helpers  all obfuscation-safe
    // =========================================================================

    /**
     * Find buttonList by walking the class hierarchy and returning the first
     * java.util.List field found.  Works whether the field is named "buttonList",
     * "controlList", or a single obfuscated letter.
     */
    @SuppressWarnings("unchecked")
    private static List<Object> getButtonList(GuiControls gui) {
        // Try known MCP names first (fast path for non-obfuscated environments)
        for (String name : new String[]{"buttonList", "controlList"}) {
            try {
                Class<?> cur = gui.getClass();
                while (cur != null) {
                    try {
                        Field f = cur.getDeclaredField(name);
                        f.setAccessible(true);
                        Object val = f.get(gui);
                        if (val instanceof List) return (List<Object>) val;
                    } catch (NoSuchFieldException ignored) {}
                    cur = cur.getSuperclass();
                }
            } catch (Throwable ignored) {}
        }

        // Obfuscated fallback: scan every field in the hierarchy for a List
        try {
            Class<?> cur = gui.getClass();
            while (cur != null) {
                for (Field f : cur.getDeclaredFields()) {
                    if (List.class.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object val = f.get(gui);
                        if (val instanceof List) {
                            // Verify it actually contains GuiButtons (not some other list)
                            List<?> list = (List<?>) val;
                            if (list.isEmpty() || list.get(0) instanceof GuiButton) {
                                return (List<Object>) val;
                            }
                        }
                    }
                }
                cur = cur.getSuperclass();
            }
        } catch (Throwable t) {
            System.out.println("[XInputMod] getButtonList scan failed: " + t);
        }

        System.out.println("[XInputMod] Could not find buttonList on GuiControls!");
        return null;
    }

    /**
     * Set the width of a GuiButton obfuscation-safely.
     *
     * In MCP the int fields of GuiButton are declared in this order:
     *   id, width, height, xPosition, yPosition  (indices 0-4)
     *
     * But in obfuscated jars the order may differ.  Strategy:
     * 1. Try the known MCP field name "width".
     * 2. Scan int fields and pick the one whose current value is closest
     *    to 200 (the default Done button width), then set it to doneWidth.
     */
    private static void setButtonWidth(GuiButton button, int newWidth) {
        // Try known name first
        try {
            Field f = getIntField(button.getClass(), "width");
            if (f != null) { f.setInt(button, newWidth); return; }
        } catch (Throwable ignored) {}

        // Obfuscated fallback: find the int field currently holding ~200
        // (vanilla Done button is 200px wide)
        try {
            List<Field> intFields = new java.util.ArrayList<Field>();
            Class<?> cur = button.getClass();
            while (cur != null) {
                for (Field f : cur.getDeclaredFields()) {
                    if (f.getType() == int.class) {
                        f.setAccessible(true);
                        intFields.add(f);
                    }
                }
                cur = cur.getSuperclass();
            }

            // Find which field holds the current width (closest to 200)
            Field bestField = null;
            int bestDiff = Integer.MAX_VALUE;
            for (Field f : intFields) {
                try {
                    int val = f.getInt(button);
                    int diff = Math.abs(val - 200);
                    if (diff < bestDiff) { bestDiff = diff; bestField = f; }
                } catch (Throwable ignored) {}
            }

            if (bestField != null && bestDiff < 50) {
                bestField.setInt(button, newWidth);
                System.out.println("[XInputMod] Set button width via field: " + bestField.getName());
            } else {
                System.out.println("[XInputMod] Could not identify width field (best diff=" + bestDiff + ")");
            }
        } catch (Throwable t) {
            System.out.println("[XInputMod] setButtonWidth fallback failed: " + t);
        }
    }

    private static Field getIntField(Class<?> clazz, String name) {
        Class<?> cur = clazz;
        while (cur != null) {
            try {
                Field f = cur.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
            cur = cur.getSuperclass();
        }
        return null;
    }
}