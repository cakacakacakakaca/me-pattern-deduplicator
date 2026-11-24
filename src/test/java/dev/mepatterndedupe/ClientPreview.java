package dev.mepatterndedupe;

import appeng.api.stacks.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.io.File;
import java.util.*;

/** Developer-only render check, never packaged in the distributable mod. */
@Mod.EventBusSubscriber(modid=DedupeMod.ID,value=Dist.CLIENT)
public final class ClientPreview {
    static int frame;static boolean shown;
    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event){
        if(!Boolean.getBoolean("mepatterndedupe.preview")||event.phase!=TickEvent.Phase.END)return;
        Minecraft mc=Minecraft.getInstance();
        if(!shown && mc.screen instanceof TitleScreen && mc.getOverlay()==null){
            shown=true;mc.options.guiScale().set(2);mc.resizeDisplay();
            List<AEKey> keys=List.of(AEItemKey.of(Items.IRON_INGOT),AEItemKey.of(Items.IRON_ORE),AEItemKey.of(Items.RAW_IRON),AEFluidKey.of(Fluids.WATER),AEItemKey.of(Items.GOLD_INGOT),AEItemKey.of(Items.GOLD_ORE),AEItemKey.of(Items.RAW_GOLD));
            List<Wire.Recipe> recipes=List.of(
                new Wire.Recipe(0,1,"处理样板",List.of(input(new Wire.Option(1,1)),input(new Wire.Option(3,1000)))),
                new Wire.Recipe(0,2,"合成样板",List.of(input(new Wire.Option(2,2),new Wire.Option(1,3)))),
                new Wire.Recipe(4,1,"处理样板",List.of(input(new Wire.Option(5,1)))),
                new Wire.Recipe(4,1,"合成样板",List.of(input(new Wire.Option(6,1)))));
            List<Wire.Row> rows=new ArrayList<>();
            // Keep the first groups short so both headings are visible, then stress the same scroll list.
            rows.add(new Wire.Row(0,0,0,1,1,1));rows.add(new Wire.Row(1,1,1,33,1,2));
            for(int i=2;i<50000;i++)rows.add(new Wire.Row(i,2+i%2,i%2,i+1,1,1+i%2));
            var snapshot=new Wire.Snapshot("预览测试：50,000 个样板槽 · 物品 / 流体图标 · 多选 · 连续滚动",keys,recipes,List.of(Component.literal("ME样板供应器 | overworld 10, 64, 20"),Component.literal("GregTech样板总成 | overworld 32, 64, 20")),rows,List.of());
            UUID id=UUID.randomUUID();long t=System.nanoTime();DedupeScreen.accept(new Wire.State(id,0,"预览"));byte[] bytes=snapshot.encode();int total=(bytes.length+Wire.CHUNK-1)/Wire.CHUNK;
            for(int i=0;i<total;i++)DedupeScreen.accept(new Wire.Chunk(id,i,total,Arrays.copyOfRange(bytes,i*Wire.CHUNK,Math.min(bytes.length,(i+1)*Wire.CHUNK))));
            System.out.printf(Locale.ROOT,"ME_DEDUPE_UI_50000_LOAD_MS=%.1f%n",(System.nanoTime()-t)/1e6);
        }
        if(shown){
            frame++;
            if(frame==20){mc.screen.mouseClicked(20,90,0);mc.screen.mouseClicked(20,116,0);assertSelected(mc,2);}
            if(frame==25)capture(mc,"dedupe-groups.png");
            if(frame==30)mc.screen.mouseScrolled(200,120,-100000);
            if(frame==35)capture(mc,"dedupe-scroll-end.png");
            if(frame==45){System.out.println("ME_DEDUPE_UI_PASS: rendered groups, icons, multi-selection and end of 50000-row list");mc.stop();}
        }
    }
    static void assertSelected(Minecraft mc,int expected){
        try{var f=DedupeScreen.class.getDeclaredField("selected");f.setAccessible(true);if(((Set<?>)f.get(mc.screen)).size()!=expected)throw new AssertionError("multi-selection");}
        catch(ReflectiveOperationException e){throw new AssertionError(e);}
    }
    static Wire.Ingredient input(Wire.Option... opts){return new Wire.Ingredient(1,List.of(opts));}
    static void capture(Minecraft mc,String name){Screenshot.grab(new File("."),name,mc.getMainRenderTarget(),c->System.out.println("ME_DEDUPE_SCREENSHOT "+c.getString()));}
}



