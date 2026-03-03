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

    // Blank 11 cursor  hides OS cursor while leaving mouse ungrabbed (Windows/Linux)
    private Cursor blankCursor      = null;
    private boolean blankCursorFailed = false; // true if platform doesn't support native cursors (Mac)

    // Whether we have set up this GUI session yet
    private boolean guiSessionActive = false;

    // Previous OS cursor position in physical pixels (LWJGL bottom-left origin).
    // Set to -1 when invalid / needs reinitialisation.
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

        //  Controller disabled: full vanilla restore 
        if (XInputMod.config != null && !XInputMod.config.enableController) {
            teardownGuiSession();
            try {
                if (!Mouse.isGrabbed() && mc.currentScreen == null && mc.inGameHasFocus)
                    Mouse.setGrabbed(true);
            } catch (Throwable ignored) {}
            return;
        }

        //  No GUI open: gameplay mode 
        if (mc.currentScreen == null) {
            teardownGuiSession();
            try {
                if (!Mouse.isGrabbed() && mc.inGameHasFocus)
                    Mouse.setGrabbed(true);
            } catch (Throwable ignored) {}
            return;
        }

        //  GUI is open 

        // Step 1: Ungrab mouse so Minecraft stops consuming events for camera
        try { if (Mouse.isGrabbed()) Mouse.setGrabbed(false); }
        catch (Throwable ignored) {}

        // Step 2: One-time session setup when GUI first opens
        if (!guiSessionActive) {
            setupGuiSession();
        }

        // Step 3: Apply physical mouse movement (delta from last OS cursor position)
        applyPhysicalMouseDelta();

        // Step 4: Apply controller left-stick movement to virtual cursor
        applyStickDelta();

        // Step 5: When stick moves, sync OS cursor to virtual position so that
        //         Minecraft's slot-hover / button-hover logic (reads real OS cursor)
        //         stays in sync. Only on stick movement to avoid Mac snap-back.
        if (state.stickMovedThisTick) {
            warpOsCursorToVirtual();
        }
        state.stickMovedThisTick = false;

        // Step 6: Recipe browser overlay
        if (tickHandler != null && tickHandler.recipeBrowser.isOpen) {
            ScaledResolution sr = getScaledResolution();
            tickHandler.recipeBrowser.render(sr.getScaledWidth(), sr.getScaledHeight());
        }

        // Step 7: Draw our crosshair at the virtual cursor position
        drawCrosshair();
    }

    // =========================================================================
    // Session setup / teardown
    // =========================================================================

    /**
     * Called once when a GUI opens.  Initialises the virtual cursor to screen
     * centre, installs blank cursor (if supported), and records OS cursor
     * position so the first delta is zero.
     */
    private void setupGuiSession() {
        guiSessionActive = true;

        ScaledResolution sr = getScaledResolution();
        // Place virtual cursor at screen centre
        state.cursorGuiX = sr.getScaledWidth()  / 2f;
        state.cursorGuiY = sr.getScaledHeight() / 2f;
        state.cursorInitialised = true;

        // Try to install a blank native cursor (hides OS cursor on Windows/Linux).
        // On Mac this throws "Native cursors not supported"  we catch it and
        // leave blankCursorFailed=true so we never retry.
        if (!blankCursorFailed) {
            tryInstallBlankCursor();
        }

        // Warp the OS cursor to our virtual position.
        // On Mac (ungrabbed, no blank cursor): this moves the visible OS cursor
        // to screen centre, which is where we want it to start.
        // On Windows/Linux (blank cursor): keeps hover logic in sync.
        warpOsCursorToVirtual();

        // Record OS cursor position AFTER the warp so first delta is zero.
        prevMousePx = Mouse.getX();
        prevMousePy = Mouse.getY();
    }

    /**
     * Called when leaving GUI (game or disabled).  Removes blank cursor and
     * resets session state.
     */
    private void teardownGuiSession() {
        if (!guiSessionActive) return;
        guiSessionActive = false;

        // Remove blank cursor  restore OS cursor to normal
        if (!blankCursorFailed) {
            try { Mouse.setNativeCursor(null); } catch (Throwable ignored) {}
        }

        prevMousePx = -1;
        prevMousePy = -1;
    }

    // =========================================================================
    // Blank cursor
    // =========================================================================

    private void tryInstallBlankCursor() {
        try {
            if (blankCursor == null) {
                IntBuffer buf = org.lwjgl.BufferUtils.createIntBuffer(1);
                buf.put(0, 0x00000000);
                blankCursor = new Cursor(1, 1, 0, 0, 1, buf, null);
            }
            Mouse.setNativeCursor(blankCursor);
            System.out.println("[XInputMod] Blank cursor installed.");
        } catch (Throwable t) {
            System.out.println("[XInputMod] Blank cursor not supported (" + t.getMessage()
                + ")  OS cursor will be visible in GUIs.");
            blankCursorFailed = true;
            // Don't treat this as an error  Mac works fine with the OS cursor
            // visible; the virtual crosshair is still drawn and warping still
            // keeps them in sync.
        }
    }

    // =========================================================================
    // Cursor movement
    // =========================================================================

    /**
     * Read how far the real OS cursor moved since last frame and apply that
     * delta to the virtual cursor so physical mouse movement still works
     * normally while a GUI is open.
     *
     * On Mac (ungrabbed, no blank cursor): Mouse.getX()/getY() returns the
     * actual OS cursor position in window pixels.  Delta tracking works
     * correctly because we record prevMousePx/Py after every warp.
     *
     * On Windows (blank cursor): same approach.
     */
    private void applyPhysicalMouseDelta() {
        try {
            int mx = Mouse.getX();
            int my = Mouse.getY();

            // Guard: if prev is invalid, just record and return (no delta yet)
            if (prevMousePx < 0) {
                prevMousePx = mx;
                prevMousePy = my;
                return;
            }

            int dpx = mx - prevMousePx;
            int dpy = my - prevMousePy;
            prevMousePx = mx;
            prevMousePy = my;

            if (dpx == 0 && dpy == 0) return;

            ScaledResolution sr = getScaledResolution();
            int scale = Math.max(1, sr.getScaleFactor());
            // LWJGL Y is bottom-up; GUI Y is top-down  flip dpy
            state.cursorGuiX = clamp(state.cursorGuiX + dpx / (float) scale,
                0, sr.getScaledWidth()  - 1);
            state.cursorGuiY = clamp(state.cursorGuiY - dpy / (float) scale,
                0, sr.getScaledHeight() - 1);
        } catch (Throwable ignored) {}
    }

    /**
     * Move the virtual cursor using the controller left stick.
     * Sets stickMovedThisTick=true so tickEnd knows to warp the OS cursor.
     */
    private void applyStickDelta() {
        float dx = processAxis(state.rawLx, 0.15f);
        float dy = processAxis(state.rawLy, 0.15f);
        if (Math.abs(dx) < 0.001f && Math.abs(dy) < 0.001f) return;

        float speed = CURSOR_SPEED;
        if (XInputMod.config != null) speed *= (0.5f + XInputMod.config.lookSpeedX);

        try {
            ScaledResolution sr = getScaledResolution();
            state.cursorGuiX = clamp(state.cursorGuiX + dx * speed,
                0, sr.getScaledWidth()  - 1);
            // Left stick Y: positive = up on stick = up on screen (invert)
            state.cursorGuiY = clamp(state.cursorGuiY - dy * speed,
                0, sr.getScaledHeight() - 1);
            state.stickMovedThisTick = true;
        } catch (Throwable ignored) {}
    }

    /**
     * Move the real OS cursor to match our virtual GUI position.
     *
     * LWJGL setCursorPosition uses window-pixel coordinates, origin bottom-left.
     * Virtual cursor is in scaled GUI coords, origin top-left.
     *
     * After warping we update prevMousePx/Py so applyPhysicalMouseDelta does
     * not see a spurious jump next frame.
     *
     * On Mac: called only on stick movement (not every frame) to avoid the
     * OS "snap-back" that occurs when setCursorPosition is called continuously
     * while the OS cursor is also being driven by the trackpad/physical mouse.
     */
    private void warpOsCursorToVirtual() {
        try {
            ScaledResolution sr = getScaledResolution();
            int scale = Math.max(1, sr.getScaleFactor());

            int px = (int)(state.cursorGuiX * scale);
            // Flip Y: LWJGL y=0 is bottom of window
            int py = mc.displayHeight - (int)(state.cursorGuiY * scale) - 1;
            px = Math.max(0, Math.min(mc.displayWidth  - 1, px));
            py = Math.max(0, Math.min(mc.displayHeight - 1, py));

            Mouse.setCursorPosition(px, py);

            // Re-read actual position after warp (may be clamped by OS)
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
        if (XInputMod.config != null && !XInputMod.config.enableController) return;
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