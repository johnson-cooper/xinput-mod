package com.attackishere.xinput;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EntityRenderer;

/**
 * Replaces mc.entityRenderer to inject controller camera every rendered frame,
 * eliminating the 20hz tick-rate stutter.
 */
public class XInputEntityRenderer extends EntityRenderer {

    private final Minecraft mc;
    private final XInputSharedState state;

    private static final float SMOOTH_ALPHA   = 0.18f;
    private static final float LOOK_SCALE     = 6.0f;
    private static final float LOOK_MAX_DELTA = 12.0f;
    private static final float LOOK_DEADZONE  = 0.10f;

    private float smoothRx = 0f;
    private float smoothRy = 0f;

    public XInputEntityRenderer(Minecraft mc, XInputSharedState state) {
        super(mc);
        this.mc    = mc;
        this.state = state;
    }

    @Override
    public void updateCameraAndRender(float partialTick) {
        super.updateCameraAndRender(partialTick);
        applyControllerLook();
    }

    private void applyControllerLook() {
        if (mc.thePlayer == null || mc.currentScreen != null) {
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

        float newYaw = mc.thePlayer.rotationYaw + yawDelta;
        mc.thePlayer.rotationYaw     = newYaw;
        mc.thePlayer.prevRotationYaw = newYaw;

        float newPitch = clamp(mc.thePlayer.rotationPitch + pitchDelta, -90f, 90f);
        mc.thePlayer.rotationPitch     = newPitch;
        mc.thePlayer.prevRotationPitch = newPitch;
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