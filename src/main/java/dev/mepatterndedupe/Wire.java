package dev.mepatterndedupe;

import appeng.api.stacks.AEKey;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.*;
import net.minecraftforge.network.simple.SimpleChannel;
import java.util.*;

public final class Wire {
    static final int CHUNK=24*1024, MAX_BYTES=64*1024*1024;
    static final SimpleChannel CHANNEL=NetworkRegistry.newSimpleChannel(new ResourceLocation(DedupeMod.ID,"main"),()->"2","2"::equals,"2"::equals);
    // 2=take, 3=begin selection, 4=rescan, 5=close, 6=append selection, 7=commit recycle.
    public record Request(UUID session,int operation,int entry,int[] selection) {
        static Request read(FriendlyByteBuf b){return new Request(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readVarIntArray(1024));}
        void write(FriendlyByteBuf b){b.writeUUID(session);b.writeVarInt(operation);b.writeVarInt(entry);b.writeVarIntArray(selection);}
    }
    public record Option(int key,long amount) {}
    public record Ingredient(long multiplier,List<Option> options) {}
    public record Recipe(int output,long amount,String type,List<Ingredient> inputs) {}
    public record Row(int id,int recipe,int provider,int slot,int count,int variant) {}
    public record Snapshot(String status,List<AEKey> keys,List<Recipe> recipes,List<Component> providers,List<Row> rows,List<Component> warnings) {
        byte[] encode(){
            FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.buffer());
            try {
                b.writeUtf(status,2048);b.writeVarInt(keys.size());for(var k:keys)AEKey.writeKey(b,k);
                b.writeVarInt(recipes.size());for(var r:recipes){
                    b.writeVarInt(r.output);b.writeVarLong(r.amount);b.writeUtf(r.type,100);b.writeVarInt(r.inputs.size());
                    for(var i:r.inputs){b.writeVarLong(i.multiplier);b.writeVarInt(i.options.size());for(var o:i.options){b.writeVarInt(o.key);b.writeVarLong(o.amount);}}
                }
                b.writeVarInt(providers.size());for(var p:providers)b.writeComponent(p);
                b.writeVarInt(rows.size());for(var r:rows){b.writeVarInt(r.id);b.writeVarInt(r.recipe);b.writeVarInt(r.provider);b.writeVarInt(r.slot);b.writeVarInt(r.count);b.writeVarInt(r.variant);}
                b.writeVarInt(warnings.size());for(var w:warnings)b.writeComponent(w);
                if(b.readableBytes()>MAX_BYTES)throw new IllegalStateException("扫描结果超过 64 MiB，请分网络处理");
                byte[] result=new byte[b.readableBytes()];b.readBytes(result);return result;
            }finally{b.release();}
        }
        static Snapshot decode(byte[] bytes){
            FriendlyByteBuf b=new FriendlyByteBuf(Unpooled.wrappedBuffer(bytes));
            try {
                String status=b.readUtf(2048);List<AEKey> keys=new ArrayList<>();
                for(int n=count(b);n>0;n--)keys.add(Objects.requireNonNull(AEKey.readKey(b)));
                List<Recipe> recipes=new ArrayList<>();
                for(int n=count(b);n>0;n--){int output=b.readVarInt();long amount=b.readVarLong();String type=b.readUtf(100);List<Ingredient> inputs=new ArrayList<>();
                    for(int m=count(b);m>0;m--){long multiplier=b.readVarLong();List<Option> opts=new ArrayList<>();for(int o=count(b);o>0;o--)opts.add(new Option(b.readVarInt(),b.readVarLong()));inputs.add(new Ingredient(multiplier,List.copyOf(opts)));}
                    recipes.add(new Recipe(output,amount,type,List.copyOf(inputs)));
                }
                List<Component> providers=new ArrayList<>();for(int n=count(b);n>0;n--)providers.add(b.readComponent());
                List<Row> rows=new ArrayList<>();for(int n=count(b);n>0;n--)rows.add(new Row(b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt(),b.readVarInt()));
                List<Component> warnings=new ArrayList<>();for(int n=count(b);n>0;n--)warnings.add(b.readComponent());
                if(b.isReadable())throw new IllegalArgumentException("trailing data");
                return new Snapshot(status,List.copyOf(keys),List.copyOf(recipes),List.copyOf(providers),List.copyOf(rows),List.copyOf(warnings));
            }finally{b.release();}
        }
        private static int count(FriendlyByteBuf b){int n=b.readVarInt();if(n<0||n>1_000_000||n>b.readableBytes())throw new IllegalArgumentException("Invalid collection size");return n;}
    }
    // 0=open/progress, 1=data complete, 2=action progress, 3=error.
    public record State(UUID session,int mode,String status) {
        static State read(FriendlyByteBuf b){return new State(b.readUUID(),b.readVarInt(),b.readUtf(2048));}
        void write(FriendlyByteBuf b){b.writeUUID(session);b.writeVarInt(mode);b.writeUtf(status,2048);}
    }
    public record Chunk(UUID session,int index,int total,byte[] data) {
        static Chunk read(FriendlyByteBuf b){return new Chunk(b.readUUID(),b.readVarInt(),b.readVarInt(),b.readByteArray(CHUNK));}
        void write(FriendlyByteBuf b){b.writeUUID(session);b.writeVarInt(index);b.writeVarInt(total);b.writeByteArray(data);}
    }
    static void register(){
        CHANNEL.messageBuilder(Request.class,0,NetworkDirection.PLAY_TO_SERVER).encoder(Request::write).decoder(Request::read).consumerMainThread((m,c)->Sessions.request(c.get().getSender(),m)).add();
        CHANNEL.messageBuilder(State.class,1,NetworkDirection.PLAY_TO_CLIENT).encoder(State::write).decoder(State::read).consumerMainThread((m,c)->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->DedupeScreen.accept(m))).add();
        CHANNEL.messageBuilder(Chunk.class,2,NetworkDirection.PLAY_TO_CLIENT).encoder(Chunk::write).decoder(Chunk::read).consumerMainThread((m,c)->DistExecutor.unsafeRunWhenOn(Dist.CLIENT,()->()->DedupeScreen.accept(m))).add();
    }
    static void state(ServerPlayer p,UUID id,int mode,String text){CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new State(id,mode,text));}
    static void snapshot(ServerPlayer p,UUID id,Snapshot data){
        byte[] bytes=data.encode();int total=(bytes.length+CHUNK-1)/CHUNK;
        for(int i=0;i<total;i++)CHANNEL.send(PacketDistributor.PLAYER.with(()->p),new Chunk(id,i,total,Arrays.copyOfRange(bytes,i*CHUNK,Math.min(bytes.length,(i+1)*CHUNK))));
    }
    static void request(Request r){CHANNEL.sendToServer(r);}
}
