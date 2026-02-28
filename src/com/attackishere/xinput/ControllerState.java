package com.attackishere.xinput;

/**
 * Normalized controller state filled by whichever polling backend is active.
 *
 * All axes are in the range [-1.0, 1.0].
 * Triggers (lt, rt) are in the range [0.0, 1.0].
 * Buttons are true when pressed.
 *
 * This struct is what XInputTickHandler reads. SDL2Controller and the legacy
 * JXInput path both write into this, so the rest of the mod is backend-agnostic.
 */
public class ControllerState {
    // Left stick
    public float lx, ly;
    // Right stick
    public float rx, ry;
    // Triggers
    public float lt, rt;
    // Face buttons
    public boolean a, b, x, y;
    // Shoulders
    public boolean lb, rb;
    // Thumbsticks
    public boolean lThumb, rThumb;
    // Menu
    public boolean start, back;
    // D-pad
    public boolean dpadUp, dpadDown, dpadLeft, dpadRight;

    public void zero() {
        lx = ly = rx = ry = lt = rt = 0f;
        a = b = x = y = false;
        lb = rb = false;
        lThumb = rThumb = false;
        start = back = false;
        dpadUp = dpadDown = dpadLeft = dpadRight = false;
    }
}