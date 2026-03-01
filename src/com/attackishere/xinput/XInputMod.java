package com.attackishere.xinput;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.MinecraftForge;

@Mod(modid = "xinputmod", name = "XInput Mod", version = "1.0")
public class XInputMod {

    private final XInputSharedState sharedState = new XInputSharedState();

    public static boolean modEnabled;
    public static XInputConfig config;

    
    @Mod.PreInit
    public void preInit(FMLPreInitializationEvent event) {
        config = new XInputConfig(event.getSuggestedConfigurationFile());
        modEnabled = config.enableController;

      
    }

    @Init
    public void init(FMLInitializationEvent event) {
        Minecraft mc = Minecraft.getMinecraft();

        XInputTickHandler tickHandler = new XInputTickHandler(sharedState);
        XInputGuiRenderer guiRenderer = new XInputGuiRenderer(mc, sharedState);

        guiRenderer.tickHandler = tickHandler;

        TickRegistry.registerTickHandler(tickHandler, Side.CLIENT);
        TickRegistry.registerTickHandler(guiRenderer, Side.CLIENT);
    }
}