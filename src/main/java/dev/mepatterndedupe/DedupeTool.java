package dev.mepatterndedupe;

import appeng.api.networking.*;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import java.util.List;

public final class DedupeTool extends Item {
    public DedupeTool(Properties p) {super(p);}
    @Override public InteractionResult onItemUseFirst(ItemStack stack,UseOnContext ctx) {
        if(!(ctx.getPlayer() instanceof ServerPlayer player)) return InteractionResult.SUCCESS;
        try {
            if(ctx.getHand()!=InteractionHand.MAIN_HAND) throw new IllegalStateException("请主手持有去重器");
            if(player.isShiftKeyDown() || !stack.getOrCreateTag().contains("anchor")) {
                if(!player.level().mayInteract(player,ctx.getClickedPos())) throw new IllegalStateException("不能连接这个位置");
                IGridNode node=findNode(player.level(),ctx.getClickedPos(),ctx.getClickedFace());
                if(node==null) throw new IllegalStateException("请对 ME 电缆或网络设备使用 Shift + 右键连接");
                CompoundTag link=new CompoundTag();link.putString("dimension",player.level().dimension().location().toString());link.putLong("pos",ctx.getClickedPos().asLong());link.putInt("face",ctx.getClickedFace().ordinal());
                stack.getOrCreateTag().put("anchor",link);
                player.sendSystemMessage(Component.literal("已连接 ME 网络。右键打开样板检查。"));
            }
            if(!player.isShiftKeyDown()) Sessions.open(player);
        } catch(Exception e) {player.sendSystemMessage(Component.literal(e.getMessage()));}
        return InteractionResult.SUCCESS;
    }
    @Override public InteractionResultHolder<ItemStack> use(Level level,Player p,InteractionHand hand) {
        if(p instanceof ServerPlayer player && hand==InteractionHand.MAIN_HAND) Sessions.open(player);
        return InteractionResultHolder.sidedSuccess(p.getItemInHand(hand),level.isClientSide);
    }
    public static IGridNode findNode(Level level,BlockPos pos,Direction face) {
        var host=GridHelper.getNodeHost(level,pos);
        return host==null?null:host.getGridNode(face);
    }
    static IGridNode connected(ServerPlayer player) {
        ItemStack tool=player.getMainHandItem();
        if(!tool.is(DedupeMod.TOOL.get())) throw new IllegalStateException("请继续主手持有去重器");
        CompoundTag link=tool.getTagElement("anchor");
        if(link==null) throw new IllegalStateException("先 Shift + 右键 ME 电缆连接网络");
        if(!link.getString("dimension").equals(player.level().dimension().location().toString())) throw new IllegalStateException("请回到绑定设备所在维度");
        BlockPos pos=BlockPos.of(link.getLong("pos"));
        if(!player.level().hasChunkAt(pos) || player.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>64*64) throw new IllegalStateException("请保持在绑定设备 64 格内，且设备区块已加载");
        if(player.isSpectator() || !player.level().mayInteract(player,pos)) throw new IllegalStateException("无权操作绑定设备");
        IGridNode node=findNode(player.level(),pos,Direction.from3DDataValue(link.getInt("face")));
        if(node==null || !node.isActive()) throw new IllegalStateException("绑定设备未连接、缺少电力或频道");
        return node;
    }
    @Override public void appendHoverText(ItemStack stack,Level level,List<Component> lines,TooltipFlag flag) {
        lines.add(Component.literal("Shift + 右键 ME 电缆：绑定；右键：扫描与管理"));
        lines.add(Component.literal("按主产物检查不同输入的样板；销毁返还空白样板至 ME"));
        CompoundTag link=stack.getTagElement("anchor");
        if(link!=null) lines.add(Component.literal("已绑定："+link.getString("dimension")+" "+BlockPos.of(link.getLong("pos")).toShortString()));
    }
}
