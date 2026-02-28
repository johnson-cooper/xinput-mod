package com.attackishere.xinput;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.Init;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.registry.TickRegistry;
import cpw.mods.fml.relauncher.Side;
import net.minecraft.client.Minecraft;
import net.minecraftforge.common.Configuration;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
@Mod(modid = "xinputmod", name = "XInput Mod", version = "1.0")
public class XInputMod {

    private final XInputSharedState sharedState = new XInputSharedState();

    public static boolean modEnabled;
    private Configuration config;

    @Mod.PreInit
    public void preInit(FMLPreInitializationEvent event) {
        // This line tells Forge where to put the file
        config = new Configuration(event.getSuggestedConfigurationFile());
        
        try {
            config.load();
            
            // This retrieves the value. If the file doesn't exist, it creates it with 'true'.
            modEnabled = config.get("General", "EnableController", true).getBoolean(true);
            
        } catch (Exception e) {
            System.out.println("[XInputMod] Failed to load config!");
        } finally {
            // IMPORTANT: If you don't call save(), the file is never created on disk.
            config.save();
        }
    }
    
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