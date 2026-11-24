package dev.mepatterndedupe;

import appeng.api.crafting.*;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import appeng.util.inv.AppEngInternalInventory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.*;
import java.util.*;

/** Real AE2 fixtures, shared production index, grouping and wire builder. No player worlds touched. */
public final class ScanBenchmark {
    public static void run(){
        final int size=50_000;
        List<ItemStack> patterns=new ArrayList<>();List<IPatternDetails> cached=new ArrayList<>();
        var output=new GenericStack(AEItemKey.of(Items.IRON_INGOT),1);
        for(int i=0;i<size;i++){
            var stack=PatternDetailsHelper.encodeProcessingPattern(new GenericStack[]{new GenericStack(AEItemKey.of(Items.IRON_ORE),i+1),new GenericStack(AEFluidKey.of(net.minecraft.world.level.material.Fluids.WATER),1000)},new GenericStack[]{output});
            patterns.add(stack);cached.add(AEItems.PROCESSING_PATTERN.asItem().decode(stack,null,false));
        }
        // 80% empty slots reproduces the sparse large-buffer case that suffered most from the old cap.
        var inventory=new AppEngInternalInventory(size*5);
        for(int i=0;i<size;i++)inventory.setItemDirect(i*5,patterns.get(i));
        measure("50000_unique_provider_cache",inventory,cached,true);
        measure("50000_unique_decoder_fallback",inventory,cached,false);
        for(int i=0;i<size;i++)inventory.setItemDirect(i*5,patterns.get(i%100));
        measure("50000_slots_100_recipes",inventory,cached.subList(0,100),true);
        // Exact signatures and per-slot identities survive cache reuse and removal of a whole variant.
        var index=new ScanIndex();for(var p:cached.subList(0,2))index.offer(p);
        var a=index.read(patterns.get(0),s->{throw new AssertionError("cache missed");});
        var b=index.read(patterns.get(1),s->{throw new AssertionError("cache missed");});
        var rows=List.of(new Sessions.Entry(0,null,0,1,a),new Sessions.Entry(1,null,1,1,b),new Sessions.Entry(2,null,2,1,a));
        if(Sessions.group(rows).values().iterator().next().variants.size()!=2)throw new AssertionError("variants");
        rows.get(1).count=0;
        var remaining=Sessions.group(rows).values().iterator().next();
        if(remaining.rows.size()!=2||remaining.variants.size()!=1)throw new AssertionError("incremental removal");
        ItemStack changed=patterns.get(0).copy();changed.getOrCreateTag().putInt("test",1);
        if(a.key().matches(changed))throw new AssertionError("mutable inventory snapshot");
    }
    private static void measure(String name,AppEngInternalInventory inventory,List<IPatternDetails> cached,boolean useCache){
        long start=System.nanoTime();ScanIndex index=new ScanIndex();if(useCache)for(var p:cached)index.offer(p);
        List<Sessions.Entry> rows=new ArrayList<>();
        for(int slot=0;slot<inventory.size();slot++){
            ItemStack stack=inventory.getStackInSlot(slot);if(stack.isEmpty())continue;
            var decoded=index.read(stack,s->AEItems.PROCESSING_PATTERN.asItem().decode(s,null,false));
            if(decoded==null)throw new AssertionError("decode");
            rows.add(new Sessions.Entry(rows.size(),null,slot,stack.getCount(),decoded));
        }
        var groups=Sessions.group(rows);List<Sessions.Entry> conflicts=new ArrayList<>();for(var g:groups.values())if(g.variants.size()>1)conflicts.addAll(g.rows);
        if(conflicts.size()!=50_000)throw new AssertionError("lost physical slots");
        long scan=System.nanoTime();
        var snapshot=Sessions.makeSnapshot("benchmark",conflicts,List.of(),e->Component.literal("benchmark provider"));byte[] encoded=snapshot.encode();
        long encodedAt=System.nanoTime();var decoded=Wire.Snapshot.decode(encoded);long end=System.nanoTime();
        if(decoded.rows().size()!=50_000||decoded.keys().size()!=3||!decoded.equals(snapshot))throw new AssertionError("complete results roundtrip");
        System.out.printf(Locale.ROOT,"ME_DEDUPE_BENCH %s: slots=%d scan_group_ms=%.1f presentation_encode_ms=%.1f client_decode_ms=%.1f total_cpu_ms=%.1f bytes=%d hits=%d fallback=%d%n",name,inventory.size(),(scan-start)/1e6,(encodedAt-scan)/1e6,(end-encodedAt)/1e6,(end-start)/1e6,encoded.length,index.cacheHits,index.fallbackDecodes);
    }
}
