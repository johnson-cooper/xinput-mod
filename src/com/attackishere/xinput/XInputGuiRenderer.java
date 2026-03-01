package com.attackishere.xinput;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import java.util.EnumSet;

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

    // Shared with XInputTickHandler   set after both are constructed
    XInputTickHandler tickHandler = null;

    public XInputGuiRenderer(Minecraft mc, XInputSharedState state) {
        this.mc    = mc;
        this.state = state;
    }

    @Override public EnumSet<TickType> ticks() { return EnumSet.of(TickType.RENDER); }
    @Override public String getLabel()         { return "XInputGuiRenderer"; }

    @Override
    public void tickStart(EnumSet<TickType> type, Object... tickData) {
        applyControllerLook();
    }

    @Override
    public void tickEnd(EnumSet<TickType> type, Object... tickData) {
        if (mc.currentScreen == null) {
            try {
                if (!Mouse.isGrabbed() && mc.inGameHasFocus) Mouse.setGrabbed(true);
            } catch (Throwable ignored) {}
            return;
        }

        // Ungrab so the OS cursor is visible and clicks register normally
        try {
            if (Mouse.isGrabbed()) Mouse.setGrabbed(false);
        } catch (Throwable ignored) {}

        // Sync the OS cursor to our virtual position every frame.
        // We do this unconditionally (not just on stick move) so the OS cursor
        // never drifts away from our virtual position   e.g. after opening a
        // screen, or if the physical mouse nudged it slightly.
        syncOsCursor();

        state.stickMovedThisTick = false;

        // Recipe browser overlay
        if (tickHandler != null && tickHandler.recipeBrowser.isOpen) {
            ScaledResolution sr = new ScaledResolution(
                mc.gameSettings, mc.displayWidth, mc.displayHeight);
            tickHandler.recipeBrowser.render(sr.getScaledWidth(), sr.getScaledHeight());
        }

        drawCrosshair();
    }

    // =========================================================================
    // GL crosshair   drawn under the OS cursor for precision targeting
    // =========================================================================

    private void drawCrosshair() {
        if (mc.currentScreen == null) return;

        float cx = state.cursorGuiX;
        float cy = state.cursorGuiY;

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);

        final float ARM = 5f;

        // Dark outline
        GL11.glColor4f(0f, 0f, 0f, 0.6f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - ARM - 1, cy);
        GL11.glVertex2f(cx + ARM + 1, cy);
        GL11.glVertex2f(cx, cy - ARM - 1);
        GL11.glVertex2f(cx, cy + ARM + 1);
        GL11.glEnd();

        // White crosshair
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - ARM, cy);
        GL11.glVertex2f(cx + ARM, cy);
        GL11.glVertex2f(cx, cy - ARM);
        GL11.glVertex2f(cx, cy + ARM);
        GL11.glEnd();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glLineWidth(1f);

        GL11.glPopMatrix();
    }

        // =========================================================================
    // OS cursor sync
    // =========================================================================

    /**
     * Move the OS cursor to match our virtual cursor position every frame.
     *
     * LWJGL's setCursorPosition uses window-space pixels with origin at
     * bottom-left. Our virtual cursor is in scaled GUI coords with origin
     * at top-left, so we scale and flip Y.
     */
    private void syncOsCursor() {
        try {
            ScaledResolution sr = new ScaledResolution(
                mc.gameSettings, mc.displayWidth, mc.displayHeight);
            int scale = sr.getScaleFactor();
            int px = (int)(state.cursorGuiX * scale);
            int py = (int)((sr.getScaledHeight() - state.cursorGuiY) * scale);
            Mouse.setCursorPosition(px, py);
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // Camera look
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

        player.rotationYaw += yawDelta;
        player.prevRotationYaw = player.rotationYaw;

        player.rotationPitch = clamp(player.rotationPitch + pitchDelta, -90f, 90f);
        player.prevRotationPitch = player.rotationPitch;
    }

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