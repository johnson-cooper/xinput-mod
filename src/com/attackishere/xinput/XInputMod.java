package com.attackishere.xinput;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;

@Mod(modid = "xinputmod", name = "XInput Mod", version = "1.0")
public class XInputMod {

    private final XInputSharedState sharedState = new XInputSharedState();

    @Init
    public void init(FMLInitializationEvent event) {
        Minecraft mc = Minecraft.getMinecraft();

        XInputTickHandler tickHandler   = new XInputTickHandler(sharedState);
        XInputGuiRenderer guiRenderer   = new XInputGuiRenderer(mc, sharedState);

        // Give the renderer a reference to the tick handler so it can
        // call recipeBrowser.render() each frame.
        guiRenderer.tickHandler = tickHandler;

        TickRegistry.registerTickHandler(tickHandler, Side.CLIENT);
        TickRegistry.registerTickHandler(guiRenderer, Side.CLIENT);
    }
}