package com.attackishere.xinput;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;
import java.util.EnumSet;

public class XInputGuiRenderer implements ITickHandler {

    private final Minecraft mc;
    private final XInputSharedState state;

    // Camera tuning
    private static final float SMOOTH_ALPHA   = 0.18f;
    private static final float LOOK_SCALE      = 6.0f;
    private static final float LOOK_MAX_DELTA = 12.0f;
    private static final float LOOK_DEADZONE  = 0.10f;

    private float smoothRx = 0f;
    private float smoothRy = 0f;

    // Shared with XInputTickHandler
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

        // 1. Release mouse for OS movement
        try {
            if (Mouse.isGrabbed()) Mouse.setGrabbed(false);
        } catch (Throwable ignored) {}

        // 2. Controller "Nudge": Stick moves the actual OS cursor
        handleControllerStickNudge();

        // 3. Sync: Map the real OS cursor back to our virtual crosshair
        updateVirtualFromRealMouse();

        state.stickMovedThisTick = false;

        // Recipe browser rendering
        if (tickHandler != null && tickHandler.recipeBrowser.isOpen) {
            ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
            tickHandler.recipeBrowser.render(sr.getScaledWidth(), sr.getScaledHeight());
        }

        drawCrosshair();
    }

    /**
     * Reads the real OS cursor position and converts it to scaled GUI coords.
     * This makes the crosshair "stick" to the mouse perfectly.
     */
    private void updateVirtualFromRealMouse() {
        if (!Display.isActive()) return;

        ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
        int scale = sr.getScaleFactor();
        if (scale <= 0) scale = 1;

        // LWJGL origin is bottom-left
        int mx = Mouse.getX();
        int my = Mouse.getY();

        // Convert to Minecraft top-left scaled coordinates
        state.cursorGuiX = (float) mx / scale;
        state.cursorGuiY = (float) (mc.displayHeight - my) / scale;
    }

    /**
     * If the stick is moved, we move the ACTUAL OS cursor. 
     * The method above will then update the crosshair to match.
     */
    private void handleControllerStickNudge() {
        // Use rawRx/Ry from state to move the cursor
        float speed = 8.0f; 
        
        float dx = processAxis(state.rawLx, 0.15f) * speed;
        float dy = processAxis(state.rawLy, 0.15f) * speed;

        if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f) {
            // Move the OS cursor position directly
            Mouse.setCursorPosition(Mouse.getX() + (int)dx, Mouse.getY() + (int)dy);
        }
    }

    private void drawCrosshair() {
        if (mc.currentScreen == null) return;

        ScaledResolution sr = new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);

        // Clamp drawing to window bounds
        float cx = clamp(state.cursorGuiX, 0, sr.getScaledWidth());
        float cy = clamp(state.cursorGuiY, 0, sr.getScaledHeight());

        GL11.glPushMatrix();
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glEnable(GL11.GL_BLEND);
        GL11.glBlendFunc(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glLineWidth(1.5f);

        final float ARM = 5f;

        // Shadow
        GL11.glColor4f(0f, 0f, 0f, 0.6f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - ARM - 1, cy); GL11.glVertex2f(cx + ARM + 1, cy);
        GL11.glVertex2f(cx, cy - ARM - 1); GL11.glVertex2f(cx, cy + ARM + 1);
        GL11.glEnd();

        // White Foreground
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - ARM, cy); GL11.glVertex2f(cx + ARM, cy);
        GL11.glVertex2f(cx, cy - ARM); GL11.glVertex2f(cx, cy + ARM);
        GL11.glEnd();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
    }

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

        // Apply config look speed multiplier
        float speedModX = (XInputMod.config != null) ? XInputMod.config.lookSpeedX * 2.0f : 1.0f;
        float speedModY = (XInputMod.config != null) ? XInputMod.config.lookSpeedY * 2.0f : 1.0f;

        float sens  = mc.gameSettings.mouseSensitivity;
        float scale = LOOK_SCALE * (0.5f + sens);

        player.rotationYaw += clamp(smoothRx * scale * speedModX, -LOOK_MAX_DELTA, LOOK_MAX_DELTA);
        player.rotationPitch = clamp(player.rotationPitch + (-smoothRy * scale * speedModY), -90f, 90f);
        
        player.prevRotationYaw = player.rotationYaw;
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