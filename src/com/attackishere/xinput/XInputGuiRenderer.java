package com.attackishere.xinput;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

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

    // Camera tuning
    private static final float SMOOTH_ALPHA   = 0.18f;
    private static final float LOOK_SCALE     = 6.0f;
    private static final float LOOK_MAX_DELTA = 12.0f;
    private static final float LOOK_DEADZONE  = 0.10f;

    private float smoothRx = 0f;
    private float smoothRy = 0f;

    // Crosshair drawing constants 
    private static final int CROSS_ARM = 6;
    private static final int CROSS_GAP = 2;

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
       
        applyControllerLook();

        // GUI cursor work only when a screen is open
        if (mc.currentScreen == null) return;

        if (state.stickMovedThisTick) {
            warpOsCursor();
            state.stickMovedThisTick = false;
        }

        drawCrosshair((int) state.cursorGuiX, (int) state.cursorGuiY);
    }

    @Override
    public void tickEnd(EnumSet<TickType> type, Object... tickData) {}

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