package dev.mepatterndedupe;

import appeng.api.config.Actionable;
import appeng.api.crafting.*;
import appeng.api.inventories.InternalInventory;
import appeng.api.networking.*;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.core.definitions.AEItems;
import appeng.helpers.patternprovider.PatternContainer;
import appeng.me.InWorldGridNode;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import java.math.BigInteger;
import java.util.*;

public final class Sessions {
    private static final org.slf4j.Logger LOG=com.mojang.logging.LogUtils.getLogger();
    private static final Map<UUID,Session> ACTIVE=new HashMap<>();
    static final class Source {
        final PatternContainer container;final IGridNode node;final InternalInventory inventory;final Component name;final String position;
        Source(PatternContainer c,IGridNode n){
            container=c;node=n;inventory=c.getTerminalPatternInventory();name=c.getTerminalGroup().name().copy();
            position=n.getLevel().dimension().location()+" "+(n instanceof InWorldGridNode w?w.getLocation().toShortString():"无世界坐标");
        }
        Component label(){return name.copy().append(" | "+position);}
    }
    static final class Entry {
        final int id,slot;final Source source;final ScanIndex.Decoded decoded;final AEKey output;
        int count,variant;
        Entry(int id,Source source,int slot,int count,ScanIndex.Decoded decoded){
            this.id=id;this.source=source;this.slot=slot;this.count=count;this.decoded=decoded;output=decoded.details().getPrimaryOutput().what();
        }
    }
    static final class Group {
        final List<Entry> rows=new ArrayList<>();
        final Map<Map<Inputs.Choice<AEKey>,BigInteger>,Integer> variants=new HashMap<>();
    }
    static Map<AEKey,Group> group(List<Entry> all){
            Map<AEKey,Group> grouped=new LinkedHashMap<>();
            for(Entry e:all)if(e.count>0){Group g=grouped.computeIfAbsent(e.output,k->new Group());e.variant=g.variants.computeIfAbsent(e.decoded.signature(),k->g.variants.size()+1);g.rows.add(e);}
        return grouped;
    }
    static final class Session {
        final UUID id=UUID.randomUUID();final ServerPlayer player;final IGrid grid;
        final net.minecraft.nbt.CompoundTag binding;
        final List<Source> sources=new ArrayList<>();final List<Entry> all=new ArrayList<>();
        final List<Component> warnings=new ArrayList<>();final Map<Object,ScanIndex> indexes=new IdentityHashMap<>();
        final Map<Integer,Entry> selectable=new HashMap<>();
        final Set<Integer> pending=new LinkedHashSet<>();
        Set<IGridNode> actionNodes=Set.of();Iterator<Integer> actions;
        List<Entry> results=List.of();
        int sourceIndex,slotIndex,scanned,groups,unsupported,errors,ticks,expectedSelection,done,failed,totalActions;
        long expires=System.nanoTime()+600_000_000_000L,started=System.nanoTime(),scanMillis;
        boolean ready,prepared,recycle;
        String notice,lastFailure="";
        Session(ServerPlayer p,IGrid grid,String notice){
            player=p;this.grid=grid;this.notice=notice;binding=p.getMainHandItem().getTagElement("anchor").copy();
            Set<PatternContainer> seen=Collections.newSetFromMap(new IdentityHashMap<>());
            Set<InternalInventory> inventories=Collections.newSetFromMap(new IdentityHashMap<>());
            for(IGridNode node:grid.getNodes()){
                Object owner=node.getOwner();
                if(owner instanceof PatternContainer c){
                    if(!seen.add(c)||c.getGrid()!=grid)continue;
                    try{Source source=new Source(c,node);if(inventories.add(source.inventory))sources.add(source);}
                    catch(Exception e){warn("无法读取设备："+owner.getClass().getSimpleName());LOG.warn("Cannot inspect pattern container",e);}
                }else if(node.getService(ICraftingProvider.class)!=null||owner instanceof ICraftingProvider){unsupported++;warn("无可编辑样板槽："+owner.getClass().getName());}
            }
        }
        void warn(String message){errors++;if(warnings.size()<1000)warnings.add(Component.literal(message));}
        String status(){return notice+" | 设备 "+sources.size()+"，样板槽 "+scanned+"；冲突 "+groups+" 组 / "+results.size()+" 槽；扫描 "+scanMillis+" ms；异常 "+errors;}
        void state(int mode){Wire.state(player,id,mode,status());}
        void validate(){
            if(DedupeTool.connected(player).getGrid()!=grid||!binding.equals(player.getMainHandItem().getTagElement("anchor")))throw new IllegalStateException("连接已改变，请重新打开工具");
        }
        void scanTick(){
            validate();long end=System.nanoTime()+35_000_000L;
            // Empty slots are cheap: no arbitrary per-tick slot cap. Never read a world from a worker thread.
            scanning: while(sourceIndex<sources.size() && System.nanoTime()<end){
                Source source=sources.get(sourceIndex);
                ScanIndex index=indexes.computeIfAbsent(source.node.getLevel(),k->new ScanIndex());
                if(!prepared){
                    prepared=true;
                    ICraftingProvider provider=source.node.getService(ICraftingProvider.class);
                    if(provider==null && source.container instanceof ICraftingProvider p)provider=p;
                    if(provider!=null)try{for(var details:provider.getAvailablePatterns())index.offer(details);}catch(Exception e){LOG.debug("Provider cache unavailable; falling back to decoder",e);}
                }
                if(source.container.getGrid()!=grid){warn(source.position+"：设备离网，请重扫");sourceIndex++;slotIndex=0;prepared=false;continue;}
                int size=source.inventory.size();
                while(slotIndex<size){
                    if((slotIndex&31)==0 && System.nanoTime()>=end)break scanning;
                    int slot=slotIndex++;ItemStack stack=source.inventory.getStackInSlot(slot);
                    if(stack.isEmpty())continue;scanned++;
                    try{
                        var decoded=index.read(stack,item->PatternDetailsHelper.decodePattern(item,source.node.getLevel()));
                        if(decoded==null){warn(source.position+" 槽 "+(slot+1)+"：无法解码");continue;}
                        all.add(new Entry(all.size(),source,slot,stack.getCount(),decoded));
                    }catch(Exception e){warn(source.position+" 槽 "+(slot+1)+"："+e.getClass().getSimpleName());LOG.warn("Cannot decode pattern",e);}
                }
                sourceIndex++;slotIndex=0;prepared=false;
            }
            if(sourceIndex>=sources.size()){
                regroup();ready=true;scanMillis=(System.nanoTime()-started)/1_000_000;notice="扫描完成";
                LOG.info("ME dedupe: {} slots, {} providers, {} ms, {} cached / {} decoded",scanned,sources.size(),scanMillis,indexes.values().stream().mapToInt(i->i.cacheHits).sum(),indexes.values().stream().mapToInt(i->i.fallbackDecodes).sum());
                publish();indexes.clear();
            }else if(++ticks%10==0){scanMillis=(System.nanoTime()-started)/1_000_000;state(0);}
        }
        void regroup(){
            Map<AEKey,Group> grouped=group(all);
            results=new ArrayList<>();selectable.clear();groups=0;
            for(Group g:grouped.values())if(g.variants.size()>1){groups++;results.addAll(g.rows);for(Entry e:g.rows)selectable.put(e.id,e);}
        }
        void publish(){Wire.snapshot(player,id,makeSnapshot(status(),results,warnings,e->e.source.label()));}
        void beginActions(Collection<Integer> ids,boolean recycle){
            if(ids.isEmpty())throw new IllegalStateException("未选择样板");
            actionNodes=Collections.newSetFromMap(new IdentityHashMap<>());for(IGridNode node:grid.getNodes())actionNodes.add(node);
            this.recycle=recycle;done=failed=0;lastFailure="";totalActions=ids.size();actions=new ArrayList<>(ids).iterator();pending.clear();
            notice="正在处理 "+totalActions+" 个选中槽";state(2);
        }
        void actionTick(){
            validate();long end=System.nanoTime()+20_000_000L;
            while(actions.hasNext()&&System.nanoTime()<end){
                Entry e=selectable.get(actions.next());
                try{if(e==null)throw new IllegalStateException("记录已过期");act(this,e,recycle);done++;}
                catch(Exception ex){failed++;lastFailure=Objects.toString(ex.getMessage(),ex.getClass().getSimpleName());}
            }
            if(!actions.hasNext()){
                actions=null;actionNodes=Set.of();regroup();
                notice=(recycle?"已销毁 "+done+" 个，空白样板已入 ME":"已取出 "+done+" 个至物品栏")+"；失败 "+failed+(failed>0?"（"+lastFailure+"）":"");publish();
            }else if(++ticks%10==0){notice="已处理 "+(done+failed)+" / "+totalActions;state(2);}
        }
    }
    static Wire.Snapshot makeSnapshot(String status,List<Entry> results,List<Component> warnings,java.util.function.Function<Entry,Component> label){
            Map<AEKey,Integer> keyIds=new LinkedHashMap<>();List<Wire.Recipe> recipes=new ArrayList<>();List<Component> providers=new ArrayList<>();List<Wire.Row> rows=new ArrayList<>();
            Map<ScanIndex.Decoded,Integer> recipeIds=new IdentityHashMap<>();Map<Source,Integer> providerIds=new IdentityHashMap<>();
            for(Entry e:results){
                Integer recipe=recipeIds.get(e.decoded);
                if(recipe==null){
                    recipe=recipes.size();recipeIds.put(e.decoded,recipe);var details=e.decoded.details();
                    int output=keyIds.computeIfAbsent(e.output,k->keyIds.size());List<Wire.Ingredient> inputs=new ArrayList<>();
                    for(var input:details.getInputs()){
                        List<Wire.Option> options=new ArrayList<>();for(var option:input.getPossibleInputs())options.add(new Wire.Option(keyIds.computeIfAbsent(option.what(),k->keyIds.size()),option.amount()));
                        inputs.add(new Wire.Ingredient(input.getMultiplier(),List.copyOf(options)));
                    }
                    recipes.add(new Wire.Recipe(output,details.getPrimaryOutput().amount(),PatternData.type(e.decoded.key().getReadOnlyStack()),List.copyOf(inputs)));
                }
                Integer provider=providerIds.get(e.source);
                if(provider==null){provider=providers.size();providerIds.put(e.source,provider);providers.add(label.apply(e));}
                rows.add(new Wire.Row(e.id,recipe,provider,e.slot+1,e.count,e.variant));
            }
            return new Wire.Snapshot(status,List.copyOf(keyIds.keySet()),recipes,providers,rows,List.copyOf(warnings));
        }
    static void register(){
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent event)->{
            if(event.phase!=TickEvent.Phase.END)return;
            for(var it=ACTIVE.values().iterator();it.hasNext();){Session s=it.next();
                if(System.nanoTime()>s.expires||s.player.isRemoved()){it.remove();continue;}
                try{if(!s.ready)s.scanTick();else if(s.actions!=null)s.actionTick();}
                catch(Exception e){s.notice="操作中断："+e.getMessage();s.state(3);LOG.warn("Deduplicator stopped",e);it.remove();}
            }
        });
        MinecraftForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedOutEvent e)->ACTIVE.remove(e.getEntity().getUUID()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppedEvent e)->ACTIVE.clear());
    }
    static void open(ServerPlayer p){start(p,"扫描已启动");}
    static void start(ServerPlayer p,String notice){
        try{IGridNode node=DedupeTool.connected(p);Session s=new Session(p,node.getGrid(),notice);ACTIVE.put(p.getUUID(),s);s.state(0);}
        catch(Exception e){p.sendSystemMessage(Component.literal("无法扫描："+e.getMessage()));}
    }
    static void request(ServerPlayer player,Wire.Request r){
        if(player==null)return;Session s=ACTIVE.get(player.getUUID());
        if(s==null||!s.id.equals(r.session())){Wire.state(player,r.session(),3,"结果已过期，请重新打开工具");return;}
        try{
            if(r.operation()==5){ACTIVE.remove(player.getUUID());return;}
            s.validate();s.expires=System.nanoTime()+600_000_000_000L;
            if(s.actions!=null)throw new IllegalStateException("批量操作尚未结束");
            if(r.operation()==4){start(player,"重新扫描");return;}
            if(!s.ready)throw new IllegalStateException("扫描尚未完成");
            switch(r.operation()){
                case 2 -> {if(!s.selectable.containsKey(r.entry()))throw new IllegalStateException("记录已过期");s.beginActions(List.of(r.entry()),false);}
                case 3 -> {if(r.entry()<1||r.entry()>s.selectable.size())throw new IllegalStateException("无效选择数量");s.pending.clear();s.expectedSelection=r.entry();}
                case 6 -> {for(int id:r.selection()){if(!s.selectable.containsKey(id)||s.pending.size()>=s.expectedSelection)throw new IllegalStateException("选择已过期");s.pending.add(id);}}
                case 7 -> {if(s.pending.size()!=s.expectedSelection)throw new IllegalStateException("选择数据不完整，请重试");s.beginActions(s.pending,true);}
                default -> throw new IllegalStateException("未知操作");
            }
        }catch(Exception e){s.pending.clear();s.expectedSelection=0;Wire.state(player,s.id,3,"操作失败："+e.getMessage());LOG.debug("Request rejected",e);}
    }
    private static boolean same(ItemStack a,ItemStack b){return ItemStack.matches(a,b);}
    private static boolean hasSpace(ServerPlayer p,ItemStack stack) {
        for(int i=0;i<36;i++){ItemStack current=p.getInventory().getItem(i);if(current.isEmpty() || ItemStack.isSameItemSameTags(current,stack)&&current.getCount()<current.getMaxStackSize())return true;}
        return false;
    }
    private static void act(Session s,Entry entry,boolean recycle) {
        Source source=entry.source;InternalInventory inv=source.inventory;ItemStack expected=entry.decoded.key().toStack(entry.count);
        if(source.container.getGrid()!=s.grid || source.node.getGrid()!=s.grid || !source.node.isActive())throw new IllegalStateException("样板设备已离线或更换网络");
        boolean present=s.actionNodes.contains(source.node);
        if(!present)throw new IllegalStateException("样板设备已移除");
        if(!(source.node instanceof InWorldGridNode worldNode))throw new IllegalStateException("此设备无世界位置，不能安全编辑");
        BlockPos pos=worldNode.getLocation();
        if(!source.node.getLevel().hasChunkAt(pos) || !source.node.getLevel().mayInteract(s.player,pos))throw new IllegalStateException("无权编辑设备或区块未加载");
        if(inv!=source.container.getTerminalPatternInventory() || entry.slot>=inv.size() || !same(inv.getStackInSlot(entry.slot),expected))throw new IllegalStateException("槽位内容已改变，请刷新后操作");
        var storage=s.grid.getStorageService().getInventory();
        var blank=AEItemKey.of(AEItems.BLANK_PATTERN.asItem());
        var actionSource=IActionSource.ofPlayer(s.player);
        String failure=PatternActions.transfer(new PatternActions.Port(){
            ItemStack removed=ItemStack.EMPTY;
            public boolean canRemove(){ItemStack simulated=inv.extractItem(entry.slot,1,true);return simulated.getCount()==1 && ItemStack.isSameItemSameTags(simulated,expected);}
            public boolean canReceive(){return recycle?storage.insert(blank,1,Actionable.SIMULATE,actionSource)==1:hasSpace(s.player,expected);}
            public boolean remove(){
                removed=inv.extractItem(entry.slot,1,false);
                if(removed.getCount()==1 && ItemStack.isSameItemSameTags(removed,expected))return true;
                if(!removed.isEmpty())restore();return false;
            }
            public boolean receive(){return recycle?storage.insert(blank,1,Actionable.MODULATE,actionSource)==1:s.player.getInventory().add(removed.copy());}
            public void restore(){
                if(removed.isEmpty())return;
                ItemStack left=inv.insertItem(entry.slot,removed,false);
                if(!left.isEmpty()) {
                    ItemStack current=inv.getStackInSlot(entry.slot);
                    if(current.isEmpty())inv.setItemDirect(entry.slot,left);
                    else if(ItemStack.isSameItemSameTags(current,left)){ItemStack restored=current.copy();restored.grow(left.getCount());inv.setItemDirect(entry.slot,restored);}
                    else {if(!s.player.getInventory().add(left))s.player.drop(left,false);LOG.error("Provider changed synchronously during rollback; original pattern returned to player");}
                }
                removed=ItemStack.EMPTY;
            }
        });
        if(failure!=null)throw new IllegalStateException(failure);
        source.node.getLevel().getChunkAt(pos).setUnsaved(true);
        s.player.getInventory().setChanged();s.player.containerMenu.broadcastChanges();
        entry.count--;
    }
}



