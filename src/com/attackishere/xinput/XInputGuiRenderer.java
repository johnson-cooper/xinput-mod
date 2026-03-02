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

public class XInputGuiRenderer implements ITickHandler {

    private final Minecraft mc;
    private final XInputSharedState state;

    private static final float SMOOTH_ALPHA   = 0.18f;
    private static final float LOOK_SCALE     = 6.0f;
    private static final float LOOK_MAX_DELTA = 12.0f;
    private static final float LOOK_DEADZONE  = 0.10f;
    private static final float CURSOR_SPEED   = 10.0f;

    private float smoothRx = 0f;
    private float smoothRy = 0f;

    // Blank 11 cursor  hides OS cursor while leaving mouse ungrabbed
    private Cursor blankCursor  = null;
    private boolean blankCursorFailed = false;

    // Track whether we installed the blank cursor this GUI session
    private boolean blankInstalled = false;

    // Previous OS cursor position in physical pixels (LWJGL bottom-left origin)
    // Used to detect physical mouse movement and apply it to virtual cursor.
    private int prevMousePx = -1;
    private int prevMousePy = -1;

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
            //  Gameplay 
            // Restore normal cursor and regrab
            if (blankInstalled) {
                try { Mouse.setNativeCursor(null); } catch (Throwable ignored) {}
                blankInstalled = false;
            }
            try { if (!Mouse.isGrabbed() && mc.inGameHasFocus) Mouse.setGrabbed(true); }
            catch (Throwable ignored) {}
            prevMousePx = -1;
            prevMousePy = -1;
            return;
        }

        //  GUI is open 
        // 1. Ungrab so Minecraft stops processing mouse input for camera
        try { if (Mouse.isGrabbed()) Mouse.setGrabbed(false); }
        catch (Throwable ignored) {}

        // 2. Install blank cursor to hide the OS cursor (we draw our own)
        if (!blankInstalled) {
            installBlankCursor();
            // Initialise virtual cursor to screen centre on first open
            ScaledResolution sr = getScaledResolution();
            state.cursorGuiX    = sr.getScaledWidth()  / 2f;
            state.cursorGuiY    = sr.getScaledHeight() / 2f;
            state.cursorInitialised = true;
            // Warp OS cursor to match so getCursorPosition reports a sane value
            warpOsCursorToVirtual(sr);
            prevMousePx = Mouse.getX();
            prevMousePy = Mouse.getY();
        }

        // 3. Apply physical mouse movement (delta from last frame)
        applyPhysicalMouseDelta();

        // 4. Apply controller stick movement
        applyStickDelta();

        // 5. When stick moves, warp OS cursor to our virtual position so that
        //    Minecraft's own slot-hover and button-hover logic (which reads
        //    the real OS cursor) stays in sync.
        if (state.stickMovedThisTick) {
            ScaledResolution sr = getScaledResolution();
            warpOsCursorToVirtual(sr);
        }

        state.stickMovedThisTick = false;

        // 6. Recipe browser overlay
        if (tickHandler != null && tickHandler.recipeBrowser.isOpen) {
            ScaledResolution sr = getScaledResolution();
            tickHandler.recipeBrowser.render(sr.getScaledWidth(), sr.getScaledHeight());
        }

        // 7. Draw our crosshair at the virtual position
        drawCrosshair();
    }

    // =========================================================================
    // Blank cursor
    // =========================================================================

    private void installBlankCursor() {
        if (blankCursorFailed) return;
        try {
            if (blankCursor == null) {
                // 11 transparent cursor
                IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(1);
                buf.put(0, 0x00000000);
                blankCursor = new Cursor(1, 1, 0, 0, 1, buf, null);
            }
            Mouse.setNativeCursor(blankCursor);
            blankInstalled = true;
        } catch (Throwable t) {
            System.out.println("[XInputMod] Blank cursor failed: " + t + "  falling back to plain ungrab.");
            blankCursorFailed = true;
            blankInstalled = true; // don't retry
        }
    }

    // =========================================================================
    // Cursor movement
    // =========================================================================

    /**
     * Read how far the real OS cursor moved since last frame and apply that
     * delta to our virtual cursor.  This way a physical mouse still works
     * normally while a GUI is open.
     */
    private void applyPhysicalMouseDelta() {
        try {
            int mx = Mouse.getX();
            int my = Mouse.getY();
            if (prevMousePx < 0) { prevMousePx = mx; prevMousePy = my; return; }

            int dpx = mx - prevMousePx;
            int dpy = my - prevMousePy;
            prevMousePx = mx;
            prevMousePy = my;

            if (dpx == 0 && dpy == 0) return;

            ScaledResolution sr = getScaledResolution();
            int scale = Math.max(1, sr.getScaleFactor());
            // LWJGL Y is bottom-up; GUI Y is top-down, so flip dpy
            state.cursorGuiX = clamp(state.cursorGuiX + dpx / (float) scale, 0, sr.getScaledWidth()  - 1);
            state.cursorGuiY = clamp(state.cursorGuiY - dpy / (float) scale, 0, sr.getScaledHeight() - 1);
        } catch (Throwable ignored) {}
    }

    /**
     * Move virtual cursor using controller left stick, then warp the OS cursor
     * to match so Minecraft's hover logic stays in sync.
     */
    private void applyStickDelta() {
        float dx = processAxis(state.rawLx, 0.15f);
        float dy = processAxis(state.rawLy, 0.15f);
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return;

        float speed = CURSOR_SPEED;
        if (XInputMod.config != null) speed *= (0.5f + XInputMod.config.lookSpeedX);

        try {
            ScaledResolution sr = getScaledResolution();
            state.cursorGuiX = clamp(state.cursorGuiX + dx * speed, 0, sr.getScaledWidth()  - 1);
            state.cursorGuiY = clamp(state.cursorGuiY - dy * speed, 0, sr.getScaledHeight() - 1);
            state.stickMovedThisTick = true;
        } catch (Throwable ignored) {}
    }

    /**
     * Move the real OS cursor to match our virtual GUI position.
     * Only called when the stick actually moved  avoids the Mac "snap-back"
     * issue that occurs when setCursorPosition is called every frame.
     *
     * LWJGL setCursorPosition uses window-space pixels, origin bottom-left.
     * Virtual cursor is in scaled GUI coords, origin top-left.
     */
    private void warpOsCursorToVirtual(ScaledResolution sr) {
        try {
            int scale = Math.max(1, sr.getScaleFactor());
            int px = (int)(state.cursorGuiX * scale);
            // Flip Y: LWJGL y=0 is bottom of window
            int py = mc.displayHeight - (int)(state.cursorGuiY * scale) - 1;
            px = Math.max(0, Math.min(mc.displayWidth  - 1, px));
            py = Math.max(0, Math.min(mc.displayHeight - 1, py));
            Mouse.setCursorPosition(px, py);
            // Update our "previous" position so applyPhysicalMouseDelta
            // doesn't see a spurious jump next frame
            prevMousePx = Mouse.getX();
            prevMousePy = Mouse.getY();
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // Crosshair
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
        // Shadow
        GL11.glColor4f(0f, 0f, 0f, 0.6f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - ARM - 1, cy); GL11.glVertex2f(cx + ARM + 1, cy);
        GL11.glVertex2f(cx, cy - ARM - 1); GL11.glVertex2f(cx, cy + ARM + 1);
        GL11.glEnd();
        // Foreground
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glBegin(GL11.GL_LINES);
        GL11.glVertex2f(cx - ARM, cy); GL11.glVertex2f(cx + ARM, cy);
        GL11.glVertex2f(cx, cy - ARM); GL11.glVertex2f(cx, cy + ARM);
        GL11.glEnd();

        GL11.glEnable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        GL11.glDisable(GL11.GL_BLEND);
        GL11.glColor4f(1f, 1f, 1f, 1f);
        GL11.glLineWidth(1f);
        GL11.glPopMatrix();
    }

    // =========================================================================
    // Camera look (gameplay only)
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

        float speedModX = XInputMod.config != null ? XInputMod.config.lookSpeedX * 2.0f : 1.0f;
        float speedModY = XInputMod.config != null ? XInputMod.config.lookSpeedY * 2.0f : 1.0f;
        float sens  = mc.gameSettings.mouseSensitivity;
        float scale = LOOK_SCALE * (0.5f + sens);

        player.rotationYaw   += clamp(smoothRx * scale * speedModX, -LOOK_MAX_DELTA, LOOK_MAX_DELTA);
        player.rotationPitch  = clamp(player.rotationPitch + (-smoothRy * scale * speedModY), -90f, 90f);
        player.prevRotationYaw   = player.rotationYaw;
        player.prevRotationPitch = player.rotationPitch;
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private ScaledResolution getScaledResolution() {
        return new ScaledResolution(mc.gameSettings, mc.displayWidth, mc.displayHeight);
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