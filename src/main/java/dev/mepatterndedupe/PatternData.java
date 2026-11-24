package dev.mepatterndedupe;

import appeng.api.crafting.IPatternDetails;
import appeng.api.stacks.*;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import java.math.BigInteger;
import java.util.*;

public final class PatternData {
    public static Map<Inputs.Choice<AEKey>,BigInteger> signature(IPatternDetails details) {
        List<Inputs.Ingredient<AEKey>> inputs=new ArrayList<>();
        for(var input:details.getInputs()) {
            List<Inputs.Option<AEKey>> alternatives=new ArrayList<>();
            for(var option:input.getPossibleInputs()) alternatives.add(new Inputs.Option<>(option.what(),option.amount()));
            inputs.add(new Inputs.Ingredient<>(alternatives,input.getMultiplier()));
        }
        return Inputs.signature(inputs);
    }
    static String type(ItemStack stack) {
        String id=Objects.toString(ForgeRegistries.ITEMS.getKey(stack.getItem()),"");
        if(id.equals("ae2:crafting_pattern"))return "合成样板";
        if(id.equals("ae2:processing_pattern"))return "处理样板";
        if(id.equals("ae2:stonecutting_pattern"))return "切石样板";
        if(id.equals("ae2:smithing_table_pattern"))return "锻造样板";
        return "扩展样板";
    }
    static Component quantity(GenericStack stack,long multiplier) {
        String amount=BigInteger.valueOf(stack.amount()).multiply(BigInteger.valueOf(multiplier)).toString();
        return stack.what().getDisplayName().copy().append(" × "+amount+(stack.what() instanceof AEFluidKey?" mB":""));
    }
    static List<Component> details(IPatternDetails pattern) {
        List<Component> lines=new ArrayList<>();
        var output=pattern.getPrimaryOutput();
        lines.add(Component.literal("主产物：").append(quantity(output,1)));
        lines.add(Component.literal("ID："+output.what().getId()));
        lines.add(Component.literal("主产物数据标识："+keyFingerprint(output.what())));
        lines.add(Component.literal("只比较主产物身份；产出数量和副产物不参与分组。"));
        int i=0;
        for(var input:pattern.getInputs()) {
            int j=0;++i;
            for(var option:input.getPossibleInputs()) {
                lines.add(Component.literal("输入 "+i+(j++==0?" [首选]：":" [替代]：")).append(quantity(option,input.getMultiplier())));
                lines.add(Component.literal("  "+option.what().getId()+"  数据："+keyFingerprint(option.what())));
            }
        }
        lines.add(Component.literal("输入数量、流体量、物品数据与可替代输入均参与比较；忽略槽位顺序。"));
        return lines;
    }
    private static String keyFingerprint(AEKey key) {
        // Identification for display only. Grouping always uses AEKey.equals, never this hash.
        return Integer.toUnsignedString(key.toTagGeneric().hashCode(),16);
    }
}
