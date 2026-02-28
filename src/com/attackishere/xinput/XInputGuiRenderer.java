package com.attackishere.xinput;

import cpw.mods.fml.common.ITickHandler;
import cpw.mods.fml.common.TickType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.player.EntityPlayer;
import org.lwjgl.input.Mouse;
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

    // Shared with XInputTickHandler set after both are constructed
    XInputTickHandler tickHandler = null;

    public XInputGuiRenderer(Minecraft mc, XInputSharedState state) {
        this.mc    = mc;
        this.state = state;
    }

    @Override
    public EnumSet<TickType> ticks() {
        return EnumSet.of(TickType.RENDER);
    }

    @Override
    public void tickStart(EnumSet<TickType> type, Object... tickData) {
        applyControllerLook();
    }

    @Override
    public void tickEnd(EnumSet<TickType> type, Object... tickData) {
        if (mc.currentScreen == null) return;

        // If the stick moved, move the REAL OS cursor to the virtual position
        if (state.stickMovedThisTick) {
            warpOsCursor();
            state.stickMovedThisTick = false;
        }

        // Recipe browser overlay (Now rendered alongside the normal cursor)
        if (tickHandler != null && tickHandler.recipeBrowser.isOpen) {
            ScaledResolution sr = new ScaledResolution(
                mc.gameSettings, mc.displayWidth, mc.displayHeight);
            tickHandler.recipeBrowser.render(sr.getScaledWidth(), sr.getScaledHeight());
        }
    }

    @Override
    public String getLabel() { return "XInputGuiRenderer"; }

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

    private void warpOsCursor() {
        try {
            ScaledResolution sr = new ScaledResolution(
                mc.gameSettings, mc.displayWidth, mc.displayHeight);
            int scale = sr.getScaleFactor();
            
            // This physically moves your Windows/Mac/Linux cursor
            int px = (int)(state.cursorGuiX * scale);
            int py = (int)((sr.getScaledHeight() - state.cursorGuiY) * scale);
            Mouse.setCursorPosition(px, py);
        } catch (Throwable ignored) {}
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