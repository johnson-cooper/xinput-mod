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
        if (mc == null || mc.currentScreen == null) return;

        // 1. Reset logic: Ensure we only run this inside the Controls menu
        if (!(mc.currentScreen instanceof GuiControls)) {
            injected = false;
            return;
        }

        try {
            GuiControls gui = (GuiControls) mc.currentScreen;
            Field listField = findButtonListField(gui.getClass());
            if (listField == null) return;
            listField.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<Object> buttons = (List<Object>) listField.get(gui);
            if (buttons == null) return;

            // 2. Check if our button already exists (prevents duplicates)
            boolean foundOurBtn = false;
            for (Object o : buttons) {
                if (o instanceof GuiButton && ((GuiButton) o).id == BTN_CONTROLLER) {
                    foundOurBtn = true;
                    break;
                }
            }
            
            // If the screen refreshed (e.g., window resize), re-inject
            if (!foundOurBtn) injected = false;
            if (injected) return;

            // 3. Layout Math
            final int gap = 4;
            final int ctrlWidth = 150; // Your new button
            final int doneWidth = 74;  // Shrunken Done button
            final int totalWidth = ctrlWidth + doneWidth + gap;

            // startX is the left-most edge of the two-button group
            final int startX = (gui.width - totalWidth) / 2;
            final int y = gui.height - 28;

            for (Object o : buttons) {
                if (o instanceof GuiButton) {
                    GuiButton b = (GuiButton) o;
                    if (b.id == 200) { // Vanilla "Done" button
                        b.xPosition = startX + ctrlWidth + gap; // Move to the right of our button
                        setButtonWidth(b, doneWidth);
                    }
                }
            }

            // 4. Inject the Custom Button
            buttons.add(new GuiButtonController(
                BTN_CONTROLLER, startX, y, ctrlWidth, 20, "Controller Settings...", config
            ));

            injected = true;

        } catch (Throwable t) {
            System.out.println("[XInput] Injection failed: " + t.getMessage());
        }
    }

    private static Field findButtonListField(Class<?> clazz) {
        Class<?> cur = clazz;
        while (cur != null) {
            // Check for both common MCP names
            try { return cur.getDeclaredField("buttonList"); } catch (Exception e) {}
            try { return cur.getDeclaredField("controlList"); } catch (Exception e) {}
            cur = cur.getSuperclass();
        }
        return null;
    }

    private static void setButtonWidth(GuiButton button, int width) {
        try {
            Field f;
            try {
                f = GuiButton.class.getDeclaredField("width");
            } catch (NoSuchFieldException e) {
                // IMPORTANT: In 1.4.7 MCP, field 3 is width. Field 1 is xPosition!
                f = GuiButton.class.getDeclaredFields()[3]; 
            }
            f.setAccessible(true);
            f.setInt(button, width);
        } catch (Throwable ignored) {}
    }
}	