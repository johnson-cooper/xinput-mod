package com.attackishere.xinput;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.input.Cursor;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.nio.IntBuffer;
import java.util.EnumSet;

/**
 * Fires every rendered frame via TickType.RENDER.
 *
 * Responsibilities:
 *   1. Apply controller camera look (replaces the deleted XInputEntityRenderer
 *      subclass, which caused Reflector NPEs in Forge/OptiFine 1.4.7).
 *   2. Draw the GUI cursor crosshair.
 *   3. Warp the OS cursor to follow the virtual cursor position when the
 *      stick is moving (so vanilla mouse hover detection stays in sync).
 */
public class XInputGuiRenderer implements ITickHandler {

    private final Minecraft mc;
    private final XInputSharedState state;

    //  Camera tuning 
    private static final float SMOOTH_ALPHA   = 0.18f;
    private static final float LOOK_SCALE     = 6.0f;
    private static final float LOOK_MAX_DELTA = 12.0f;
    private static final float LOOK_DEADZONE  = 0.10f;

    private float smoothRx = 0f;
    private float smoothRy = 0f;

    //  Crosshair drawing constants 
    private static final int CROSS_ARM = 6;
    private static final int CROSS_GAP = 2;

    // Shared with XInputTickHandler   set after both are constructed
    XInputTickHandler tickHandler = null;

    //  Blank cursor (hides OS cursor while our GUI crosshair is shown) 
    private Cursor blankCursor  = null;
    private boolean cursorHidden = false;

    public XInputGuiRenderer(Minecraft mc, XInputSharedState state) {
        this.mc    = mc;
        this.state = state;
    }

    // =========================================================================
    // ITickHandler
    // =========================================================================

    @Override
    public EnumSet<TickType> ticks() {
        return EnumSet.of(TickType.RENDER);
    }

    @Override
    public void tickStart(EnumSet<TickType> type, Object... tickData) {
        // Apply camera look at the START of the render tick   before vanilla
        // draws the frame   so the rotation values are ready before rendering.
        applyControllerLook();
    }

    @Override
    public void tickEnd(EnumSet<TickType> type, Object... tickData) {
        // Draw GUI overlays at the END of the render tick, after vanilla has
        // already painted the GUI screen   so our crosshair and recipe browser
        // appear on top rather than being painted over.
        if (mc.currentScreen == null) {
            // No GUI open   restore the real cursor if we hid it
            showOsCursor();
            return;
        }

        // Hide the OS cursor and show our crosshair instead
        hideOsCursor();

        if (state.stickMovedThisTick) {
            warpOsCursor();
            state.stickMovedThisTick = false;
        }

        drawCrosshair((int) state.cursorGuiX, (int) state.cursorGuiY);

        //  Recipe browser overlay 
        if (tickHandler != null && tickHandler.recipeBrowser.isOpen) {
            ScaledResolution sr = new ScaledResolution(
                mc.gameSettings, mc.displayWidth, mc.displayHeight);
            tickHandler.recipeBrowser.render(sr.getScaledWidth(), sr.getScaledHeight());
        }
    }

    @Override
    public String getLabel() { return "XInputGuiRenderer"; }

    // =========================================================================
    // Camera look (every frame, stutter-free)
    // =========================================================================

    private void applyControllerLook() {
        EntityPlayer player = mc.thePlayer;
        if (player == null || mc.currentScreen != null) {
            smoothRx *= (1f - SMOOTH_ALPHA);
            smoothRy *= (1f - SMOOTH_ALPHA);
            return;
        }

        float procRx = processAxis(state.rawRx, LOOK_DEADZONE);
        float procRy = processAxis(state.rawRy, LOOK_DEADZONE);

        smoothRx += (procRx - smoothRx) * SMOOTH_ALPHA;
        smoothRy += (procRy - smoothRy) * SMOOTH_ALPHA;

        if (Math.abs(smoothRx) < 0.0001f && Math.abs(smoothRy) < 0.0001f) return;

        float sens  = mc.gameSettings.mouseSensitivity;
        float scale = LOOK_SCALE * (0.5f + sens);

        float yawDelta   = clamp( smoothRx * scale, -LOOK_MAX_DELTA, LOOK_MAX_DELTA);
        float pitchDelta = clamp(-smoothRy * scale, -LOOK_MAX_DELTA, LOOK_MAX_DELTA);

        float newYaw = player.rotationYaw + yawDelta;
        player.rotationYaw     = newYaw;
        player.prevRotationYaw = newYaw;

        float newPitch = clamp(player.rotationPitch + pitchDelta, -90f, 90f);
        player.rotationPitch     = newPitch;
        player.prevRotationPitch = newPitch;
    }

    // =========================================================================
    // GUI cursor
    // =========================================================================

    private void hideOsCursor() {
        if (cursorHidden) return;
        try {
            if (blankCursor == null) {
                // Create a 1x1 transparent cursor   the minimum LWJGL allows
                IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(1);
                buf.put(0, 0x00000000); // fully transparent pixel
                blankCursor = new Cursor(1, 1, 0, 0, 1, buf, null);
            }
            Mouse.setNativeCursor(blankCursor);
            cursorHidden = true;
        } catch (Throwable ignored) {}
    }

    private void showOsCursor() {
        if (!cursorHidden) return;
        try {
            Mouse.setNativeCursor(null); // null restores the default OS cursor
            cursorHidden = false;
        } catch (Throwable ignored) {}
    }

    private void warpOsCursor() {
        try {
            ScaledResolution sr = new ScaledResolution(
                mc.gameSettings, mc.displayWidth, mc.displayHeight);
            int scale = sr.getScaleFactor();
            int px = (int)(state.cursorGuiX * scale);
            int py = (int)((sr.getScaledHeight() - state.cursorGuiY) * scale);
            Mouse.setCursorPosition(px, py);
        } catch (Throwable ignored) {}
    }

    private void drawCrosshair(int cx, int cy) {
        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glLineWidth(2f);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);

        drawCrossLines(cx + 1, cy + 1, 0f, 0f, 0f, 0.6f); // shadow
        drawCrossLines(cx,     cy,     1f, 1f, 1f, 1f);    // white

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glPopMatrix();
    }

    private void drawCrossLines(int cx, int cy, float r, float g, float b, float a) {
        GL11.glColor4f(r, g, b, a);
        GL11.glBegin(GL11.GL_LINES);
        // horizontal
        GL11.glVertex2i(cx - CROSS_ARM, cy);
        GL11.glVertex2i(cx - CROSS_GAP, cy);
        GL11.glVertex2i(cx + CROSS_GAP, cy);
        GL11.glVertex2i(cx + CROSS_ARM, cy);
        // vertical
        GL11.glVertex2i(cx, cy - CROSS_ARM);
        GL11.glVertex2i(cx, cy - CROSS_GAP);
        GL11.glVertex2i(cx, cy + CROSS_GAP);
        GL11.glVertex2i(cx, cy + CROSS_ARM);
        GL11.glEnd();
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private static float processAxis(float value, float deadzone) {
        float abs = Math.abs(value);
        if (abs <= deadzone) return 0f;
        float sign = value < 0f ? -1f : 1f;
        float norm = (abs - deadzone) / (1f - deadzone);
        return sign * (norm * norm);
    }

    private static float clamp(float v, float min, float max) {
        return v < min ? min : (v > max ? max : v);
    }
}