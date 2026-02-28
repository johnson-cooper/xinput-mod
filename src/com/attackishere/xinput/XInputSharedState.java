package com.attackishere.xinput;

/**
 * Shared between all XInput handlers. All run on the same client thread.
 */
public class XInputSharedState {

    public float rawRx = 0f;
    public float rawRy = 0f;


    public float  cursorGuiX       = 0f;
    public float  cursorGuiY       = 0f;
    public boolean cursorInitialised = false;


    public boolean stickMovedThisTick = false;
}