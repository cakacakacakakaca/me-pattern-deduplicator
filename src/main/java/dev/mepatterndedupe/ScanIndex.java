package dev.mepatterndedupe;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import net.minecraft.world.item.ItemStack;
import java.math.BigInteger;
import java.util.*;
import java.util.function.Function;

/** One scan, one level. Cache by complete encoded item identity, never by a lossy hash. */
final class ScanIndex {
    record Decoded(AEItemKey key, IPatternDetails details, Map<Inputs.Choice<AEKey>,BigInteger> signature) {}
    private final Map<AEItemKey,IPatternDetails> available=new HashMap<>();
    private final Map<AEItemKey,Decoded> decoded=new HashMap<>();
    int cacheHits, fallbackDecodes;
    void offer(IPatternDetails details) {
        if(details!=null)available.putIfAbsent(details.getDefinition(),details);
    }
    Decoded read(ItemStack stack,Function<ItemStack,IPatternDetails> decoder) {
        AEItemKey key=AEItemKey.of(stack);
        Decoded result=decoded.get(key);
        if(result!=null){cacheHits++;return result;}
        IPatternDetails details=available.get(key);
        if(details==null){fallbackDecodes++;details=decoder.apply(stack);}else cacheHits++;
        if(details==null || details.getPrimaryOutput()==null)return null;
        result=new Decoded(key,details,PatternData.signature(details));
        decoded.put(key,result);
        return result;
    }
}

