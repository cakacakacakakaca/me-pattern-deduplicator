package dev.mepatterndedupe;

import net.minecraft.world.item.*;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.*;

@Mod(DedupeMod.ID)
public final class DedupeMod {
    public static final String ID="mepatterndedupe";
    public static final DeferredRegister<Item> ITEMS=DeferredRegister.create(ForgeRegistries.ITEMS,ID);
    public static final RegistryObject<Item> TOOL=ITEMS.register("me_pattern_deduplicator",()->new DedupeTool(new Item.Properties().stacksTo(1)));
    public DedupeMod() {
        var bus=FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        bus.addListener((BuildCreativeModeTabContentsEvent event)->{if(event.getTabKey()==CreativeModeTabs.TOOLS_AND_UTILITIES) event.accept(TOOL);});
        Wire.register();
        Sessions.register();
    }
}
