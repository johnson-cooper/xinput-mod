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
        TickRegistry.registerTickHandler(new XInputTickHandler(sharedState), Side.CLIENT);
        TickRegistry.registerTickHandler(new XInputGuiRenderer(mc, sharedState), Side.CLIENT);
    }
}