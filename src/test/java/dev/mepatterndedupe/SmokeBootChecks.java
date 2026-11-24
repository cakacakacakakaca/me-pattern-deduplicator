package dev.mepatterndedupe;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import net.minecraftforge.data.event.GatherDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import java.util.*;

@Mod.EventBusSubscriber(modid=DedupeMod.ID,bus=Mod.EventBusSubscriber.Bus.MOD)
public class SmokeBootChecks {
    @SubscribeEvent public static void check(GatherDataEvent event) {
        if(!Boolean.getBoolean("mepatterndedupe.smoke"))return;
        if(DedupeMod.TOOL.get()==Items.AIR || DedupeMod.TOOL.get().getMaxStackSize()!=1)throw new AssertionError("tool registration");
        var output=new GenericStack(AEItemKey.of(Items.IRON_INGOT),1);
        var first=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE),1)},new GenericStack[]{output,new GenericStack(AEItemKey.of(Items.STONE),1)});
        var second=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(AEItemKey.of(Items.RAW_IRON),1)},new GenericStack[]{output});
        var sameInputs=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE),1)},new GenericStack[]{output,new GenericStack(AEItemKey.of(Items.DIRT),1)});
        // Datagen has no Level; call AE2's processing decoder directly (the public dispatcher requires a Level).
        var a=AEItems.PROCESSING_PATTERN.asItem().decode(first,null,false);var b=AEItems.PROCESSING_PATTERN.asItem().decode(second,null,false);var c=AEItems.PROCESSING_PATTERN.asItem().decode(sameInputs,null,false);
        if(a==null||b==null||c==null)throw new AssertionError("AE2 decode");
        if(!a.getPrimaryOutput().what().equals(b.getPrimaryOutput().what())||PatternData.signature(a).equals(PatternData.signature(b))||!PatternData.signature(a).equals(PatternData.signature(c)))throw new AssertionError("real processing patterns");
        var row=new Wire.Row(37,0,0,2,1,2);
        var recipe=new Wire.Recipe(0,1,"处理样板",List.of(new Wire.Ingredient(1,List.of(new Wire.Option(1,1)))));
        var view=new Wire.Snapshot("测试",List.of(output.what(),AEItemKey.of(Items.IRON_ORE)),List.of(recipe),List.of(Component.literal("Provider A | 1, 2, 3")),List.of(row),List.of());
        if(!Wire.Snapshot.decode(view.encode()).equals(view))throw new AssertionError("snapshot roundtrip");
        var request=new Wire.Request(UUID.randomUUID(),6,-1,java.util.stream.IntStream.range(0,1024).toArray());
        var buffer=new FriendlyByteBuf(Unpooled.buffer());
        try{request.write(buffer);if(buffer.readableBytes()>=32767)throw new AssertionError("C2S size");var restored=Wire.Request.read(buffer);if(!Arrays.equals(restored.selection(),request.selection())||!restored.session().equals(request.session())||buffer.isReadable())throw new AssertionError("selection roundtrip");}finally{buffer.release();}
        if(!AEItems.BLANK_PATTERN.stack().is(AEItems.BLANK_PATTERN.asItem()))throw new AssertionError("blank pattern");
        var inv=new appeng.util.inv.AppEngInternalInventory(3);
        inv.setItemDirect(0,first.copy());inv.setItemDirect(1,second.copy());
        ItemStack simulated=inv.extractItem(0,1,true);
        if(!ItemStack.matches(simulated,first)||!ItemStack.matches(inv.getStackInSlot(0),first))throw new AssertionError("inventory simulation mutated source");
        ItemStack extracted=inv.extractItem(0,1,false);
        if(!ItemStack.matches(extracted,first)||!inv.getStackInSlot(0).isEmpty()||!ItemStack.matches(inv.getStackInSlot(1),second))throw new AssertionError("physical slot isolation");
        if(!inv.insertItem(0,extracted,false).isEmpty()||!ItemStack.matches(inv.getStackInSlot(0),first))throw new AssertionError("inventory rollback");
        ScanBenchmark.run();
        System.out.println("ME_DEDUPE_SMOKE_PASS: Forge boot, item registration, real AE2 processing decode, byproduct exclusion, different inputs, packet roundtrip");
    }
}

