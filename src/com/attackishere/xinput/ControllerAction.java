package com.attackishere.xinput;

/**
 * Every remappable controller action.
 * The ordinal is used as an index into XInputConfig's binding array,
 * so never reorder  only append new entries at the end.
 */
public enum ControllerAction {

    JUMP        ("Jump"),
    ATTACK      ("Attack / RT"),
    USE_ITEM    ("Use Item / LT"),
    SNEAK       ("Sneak"),
    SPRINT      ("Sprint"),
    INVENTORY   ("Open Inventory"),
    DROP_ITEM   ("Drop Item"),
    HOTBAR_PREV ("Hotbar Prev"),
    HOTBAR_NEXT ("Hotbar Next"),
    RECIPE_BROWSER("Recipe Browser"),
    PAUSE       ("Pause"),
    CHAT        ("Chat"),
    THIRD_PERSON("Third Person"),
    HIDE_HUD    ("Hide HUD");

    public final String displayName;

    ControllerAction(String displayName) {
        this.displayName = displayName;
    }
}