package dev.mepatterndedupe;

import appeng.api.client.AEKeyRendering;
import appeng.api.stacks.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.*;
import net.minecraft.network.chat.Component;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;

public final class DedupeScreen extends Screen {
    private static DedupeScreen active;
    private UUID session;
    private Wire.Snapshot data;
    private final Set<Integer> selected=new HashSet<>();
    private final List<Line> lines=new ArrayList<>();
    private final Map<Integer,List<Wire.Row>> groups=new LinkedHashMap<>();
    private List<List<Cell>> recipeCells=List.of();
    private List<String> keySearch=List.of(),providerSearch=List.of();
    private EditBox search;
    private Button destroy,take,selectAll,clear,rescan;
    private String filter="",message="";
    private boolean busy=true,showWarnings,dragging;
    private double scroll;
    private int left,span,top=54,bottom,columns,contentHeight;
    private ByteArrayOutputStream incoming;
    private int nextChunk,totalChunks;
    private record Cell(Wire.Option option,long multiplier,int input,int alternative) {}
    private record Line(int top,int height,int output,Wire.Row row,Component warning) {}
    private DedupeScreen(Wire.State state){super(Component.literal("ME网络样板去重器"));session=state.session();message=state.status();}
    static void accept(Wire.State state){
        var mc=Minecraft.getInstance();
        if(state.mode()==0 && (active==null||!active.session.equals(state.session()))){
            active=new DedupeScreen(state);mc.setScreen(active);
        }
        if(active==null||!active.session.equals(state.session()))return;
        active.message=state.status();active.busy=state.mode()!=3;
        active.updateButtons();
    }
    static void accept(Wire.Chunk chunk){
        var s=active;if(s==null||!s.session.equals(chunk.session()))return;
        try{
            if(chunk.total()<1||chunk.total()>(Wire.MAX_BYTES+Wire.CHUNK-1)/Wire.CHUNK)throw new IllegalArgumentException("无效数据长度");
            if(chunk.index()==0){s.incoming=new ByteArrayOutputStream();s.nextChunk=0;s.totalChunks=chunk.total();}
            if(s.incoming==null||chunk.index()!=s.nextChunk||chunk.total()!=s.totalChunks||s.incoming.size()+chunk.data().length>Wire.MAX_BYTES)throw new IllegalArgumentException("结果数据不完整");
            s.incoming.writeBytes(chunk.data());s.nextChunk++;
            if(s.nextChunk==s.totalChunks){
                s.data=Wire.Snapshot.decode(s.incoming.toByteArray());s.incoming=null;s.message=s.data.status();s.busy=false;s.selected.clear();s.indexData();s.layout();s.updateButtons();
            }else s.message="正在接收全部结果 "+s.nextChunk+" / "+s.totalChunks;
        }catch(Exception e){s.incoming=null;s.busy=false;s.message="接收失败："+e.getMessage()+"；请重扫";s.updateButtons();}
    }
    private void indexData(){
        groups.clear();
        for(var row:data.rows())groups.computeIfAbsent(data.recipes().get(row.recipe()).output(),k->new ArrayList<>()).add(row);
        keySearch=data.keys().stream().map(k->(k.getDisplayName().getString()+" "+k.getId()).toLowerCase(Locale.ROOT)).toList();
        providerSearch=data.providers().stream().map(p->p.getString().toLowerCase(Locale.ROOT)).toList();
        recipeCells=new ArrayList<>();
        for(var recipe:data.recipes()){
            List<Cell> cells=new ArrayList<>();int input=0;
            for(var ingredient:recipe.inputs()){int alt=0;input++;for(var option:ingredient.options())cells.add(new Cell(option,ingredient.multiplier(),input,alt++));}
            recipeCells.add(cells);
        }
    }
    @Override protected void init(){
        left=10;span=width-20;bottom=height-72;columns=Math.max(1,(span-110)/24);
        search=addRenderableWidget(new EditBox(font,left,28,Math.max(60,span-128),20,Component.literal("搜索主产物 / ID / 设备 / 坐标")));
        search.setMaxLength(80);search.setHint(Component.literal("搜索主产物 / ID / 设备 / 坐标"));search.setValue(filter);
        search.setResponder(value->{filter=value;scroll=0;layout();});
        addRenderableWidget(Button.builder(Component.literal("异常"),b->{showWarnings=!showWarnings;scroll=0;layout();}).bounds(left+span-122,28,58,20).build());
        rescan=addRenderableWidget(Button.builder(Component.literal("重扫"),b->request(4,-1)).bounds(left+span-58,28,58,20).build());
        int w=Math.max(35,(span-16)/5),y=height-64;
        selectAll=addRenderableWidget(Button.builder(Component.literal("全选结果"),b->{for(Line line:lines)if(line.row!=null)selected.add(line.row.id());updateButtons();}).bounds(left,y,w,20).build());
        clear=addRenderableWidget(Button.builder(Component.literal("清空选择"),b->{selected.clear();updateButtons();}).bounds(left+w+4,y,w,20).build());
        take=addRenderableWidget(Button.builder(Component.literal("取出一个"),b->{if(selected.size()==1)request(2,selected.iterator().next());}).bounds(left+2*(w+4),y,w,20).build());
        destroy=addRenderableWidget(Button.builder(Component.literal("批量销毁"),b->confirmDestroy()).bounds(left+3*(w+4),y,w,20).build());
        addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(left+4*(w+4),y,span-4*(w+4),20).build());
        layout();updateButtons();
    }
    private void layout(){
        lines.clear();contentHeight=0;if(data==null)return;
        if(showWarnings){
            List<Component> warnings=data.warnings().isEmpty()?List.of(Component.literal("未发现异常")):data.warnings();
            for(var warning:warnings){int h=Math.max(20,font.split(warning,span-20).size()*10+6);lines.add(new Line(contentHeight,h,-1,null,warning));contentHeight+=h;}
        }
        String q=filter.strip().toLowerCase(Locale.ROOT);
        for(var group:groups.entrySet()){
            boolean outputMatches=q.isEmpty()||keySearch.get(group.getKey()).contains(q);boolean added=false;
            for(var row:group.getValue()){
                if(!outputMatches&&!providerSearch.get(row.provider()).contains(q))continue;
                if(!added){lines.add(new Line(contentHeight,24,group.getKey(),null,null));contentHeight+=24;added=true;}
                int h=2+Math.max(1,(recipeCells.get(row.recipe()).size()+columns-1)/columns)*24;
                lines.add(new Line(contentHeight,h,group.getKey(),row,null));contentHeight+=h;
            }
            if(added)contentHeight+=4;
        }
        clamp();updateButtons();
    }
    private void clamp(){scroll=Math.max(0,Math.min(scroll,Math.max(0,contentHeight-(bottom-top))));}
    private int firstVisible(){
        int lo=0,hi=lines.size();while(lo<hi){int mid=(lo+hi)>>>1;Line l=lines.get(mid);if(l.top+l.height<=scroll)lo=mid+1;else hi=mid;}return lo;
    }
    private void updateButtons(){
        if(destroy==null)return;
        destroy.active=!busy&&!selected.isEmpty();take.active=!busy&&selected.size()==1;
        clear.active=!busy&&!selected.isEmpty();selectAll.active=!busy&&data!=null&&!data.rows().isEmpty();rescan.active=!busy;
    }
    private void request(int operation,int entry){busy=true;updateButtons();Wire.request(new Wire.Request(session,operation,entry,new int[0]));}
    private void confirmDestroy(){
        if(busy||selected.isEmpty())return;int[] ids=selected.stream().mapToInt(Integer::intValue).toArray();
        minecraft.setScreen(new ConfirmScreen(yes->{minecraft.setScreen(this);if(yes){
            busy=true;updateButtons();Wire.request(new Wire.Request(session,3,ids.length,new int[0]));
            for(int i=0;i<ids.length;i+=1024)Wire.request(new Wire.Request(session,6,-1,Arrays.copyOfRange(ids,i,Math.min(i+1024,ids.length))));
            Wire.request(new Wire.Request(session,7,-1,new int[0]));
        }},Component.literal("销毁选中的 "+ids.length+" 个样板？"),Component.literal("每个选中槽销毁 1 个编码样板，空白样板直接存入 ME。可以清空整组，请检查选择。槽位已变化、设备离线或 ME 无法接收时跳过该项；不会将空白样板放入玩家背包。")));
    }
    @Override public void render(GuiGraphics g,int mx,int my,float delta){
        renderBackground(g);g.drawCenteredString(font,title,width/2,9,0xe0d4ff);
        List<Component> tooltip=null;
        g.fill(left,top,left+span,bottom,0xc0121420);g.enableScissor(left,top,left+span-8,bottom);
        for(int index=firstVisible();index<lines.size();index++){
            Line line=lines.get(index);int y=top+line.top-(int)scroll;if(y>=bottom)break;
            boolean hover=mx>=left&&mx<left+span-8&&my>=Math.max(top,y)&&my<Math.min(bottom,y+line.height);
            if(line.warning!=null){g.drawWordWrap(font,line.warning,left+4,y+3,span-20,0xffba88);continue;}
            if(line.row==null){
                g.fill(left,y,left+span-8,y+23,0xff34334b);
                icon(g,data.keys().get(line.output),left+5,y+3);
                String heading=data.keys().get(line.output).getDisplayName().getString()+"  （"+groups.get(line.output).size()+" 个样板）";
                g.drawString(font,font.plainSubstrByWidth(heading,span-42),left+27,y+8,0xf3dc9b);
                if(hover)tooltip=List.of(data.keys().get(line.output).getDisplayName(),Component.literal(data.keys().get(line.output).getId()+" · 点击标题切换本组筛选结果的选择"));
                continue;
            }
            Wire.Row row=line.row;Wire.Recipe recipe=data.recipes().get(row.recipe());boolean checked=selected.contains(row.id());
            g.fill(left+1,y,left+span-9,y+line.height-1,checked?0xff243e48:hover?0xff2b2e3c:0xff1c202c);
            g.fill(left+6,y+8,left+17,y+19,0xff9aa7b5);g.fill(left+7,y+9,left+16,y+18,checked?0xff59d6b0:0xff20232d);
            if(checked)g.drawString(font,"✓",left+7,y+10,0xffffff);
            String metadata=recipe.type()+" · 方案 "+row.variant()+" · 产出 ×"+recipe.amount()+" · "+data.providers().get(row.provider()).getString()+" · 槽 "+row.slot()+(row.count()>1?" ×"+row.count():"");
            g.drawString(font,font.plainSubstrByWidth(recipe.type(),62),left+24,y+3,0xc7cbe0);
            g.drawString(font,font.plainSubstrByWidth("产出 ×"+recipe.amount(),62),left+24,y+14,0x8e9bad);
            if(hover)tooltip=List.of(Component.literal(metadata),Component.literal(checked?"点击取消选择":"点击选择，可多选；取出需仅选一个槽"));
            List<Cell> cells=recipeCells.get(row.recipe());
            if(cells.size()<columns){
                int sx=left+94+cells.size()*24,remaining=left+span-12-sx;
                if(remaining>60){
                    g.drawString(font,font.plainSubstrByWidth(data.providers().get(row.provider()).getString(),remaining),sx,y+3,0xaeb5c8);
                    g.drawString(font,font.plainSubstrByWidth("槽 "+row.slot()+" · 输入方案 "+row.variant()+(row.count()>1?" · 数量 "+row.count():""),remaining),sx,y+14,0x90a899);
                }
            }
            for(int i=0;i<cells.size();i++){
                Cell cell=cells.get(i);int cx=left+90+i%columns*24,cy=y+1+i/columns*24;
                if(cy+24<top||cy>=bottom)continue;
                AEKey key=data.keys().get(cell.option.key());icon(g,key,cx,cy);
                if(cell.alternative>0)g.fill(cx,cy,cx+3,cy+3,0xff64c9ff);
                BigInteger amount=BigInteger.valueOf(cell.option.amount()).multiply(BigInteger.valueOf(cell.multiplier));
                g.pose().pushPose();g.pose().translate(cx,cy+17,0);g.pose().scale(.65f,.65f,1);g.drawString(font,compact(amount),0,0,0xffffff);g.pose().popPose();
                if(hover&&mx>=cx&&mx<cx+23&&my>=cy&&my<cy+24){
                    tooltip=new ArrayList<>(AEKeyRendering.getTooltip(key));tooltip.add(Component.literal("数量："+amount+(key instanceof AEFluidKey?" mB":"")));
                    tooltip.add(Component.literal("输入 "+cell.input+ (cell.alternative==0?" · 首选材料":" · 替代材料 "+cell.alternative)));
                    tooltip.add(Component.literal(key.getId().toString()));
                }
            }
        }
        g.disableScissor();
        if(lines.isEmpty())g.drawCenteredString(font,busy?"正在扫描 / 接收样板…":"没有匹配的不同输入样板",width/2,top+20,0xc4c8dd);
        if(contentHeight>bottom-top){int h=Math.max(16,(bottom-top)*(bottom-top)/contentHeight);int y=top+(int)(scroll/Math.max(1,contentHeight-(bottom-top))*(bottom-top-h));g.fill(left+span-6,top,left+span-2,bottom,0xff262936);g.fill(left+span-6,y,left+span-2,y+h,0xff8c90b4);}
        super.render(g,mx,my,delta);
        String selection="已选 "+selected.size()+" 个槽"+(busy?" · 处理中":" · 滚轮浏览，点击行多选，点击标题选择该组");
        g.drawString(font,font.plainSubstrByWidth(selection,span),left,height-39,0xd6e7dd);
        g.drawString(font,font.plainSubstrByWidth(message,span),left,height-24,0xb3b9d0);
        if(my>=height-26&&my<height-12)tooltip=List.of(Component.literal(message));
        if(tooltip!=null){List<net.minecraft.util.FormattedCharSequence> wrapped=new ArrayList<>();for(var c:tooltip)wrapped.addAll(font.split(c,Math.min(440,width-24)));g.renderTooltip(font,wrapped,mx,my);}
    }
    private void icon(GuiGraphics g,AEKey key,int x,int y){try{AEKeyRendering.drawInGui(minecraft,g,x,y,key);}catch(Exception e){g.drawString(font,"?",x+4,y+4,0xff8888);}}
    private static String compact(BigInteger n){
        if(n.compareTo(BigInteger.valueOf(1000))<0)return n.toString();
        if(n.compareTo(BigInteger.valueOf(1_000_000))<0)return n.divide(BigInteger.valueOf(1000))+"k";
        if(n.compareTo(BigInteger.valueOf(1_000_000_000))<0)return n.divide(BigInteger.valueOf(1_000_000))+"M";
        return n.divide(BigInteger.valueOf(1_000_000_000))+"G";
    }
    @Override public boolean mouseClicked(double x,double y,int button){
        if(button==0&&x>=left+span-8&&x<=left+span&&y>=top&&y<bottom){dragging=true;dragScroll(y);return true;}
        if(button==0&&!busy&&x>=left&&x<left+span-8&&y>=top&&y<bottom){
            double target=y-top+scroll;
            for(int i=firstVisible();i<lines.size();i++){Line l=lines.get(i);if(l.top>target)break;if(target>=l.top+l.height)continue;
                if(l.row!=null){if(!selected.remove(l.row.id()))selected.add(l.row.id());}
                else if(l.warning==null){
                    List<Integer> ids=new ArrayList<>();for(int j=i+1;j<lines.size()&&lines.get(j).row!=null;j++)ids.add(lines.get(j).row.id());
                    if(selected.containsAll(ids))selected.removeAll(ids);else selected.addAll(ids);
                }
                updateButtons();return true;
            }
        }
        return super.mouseClicked(x,y,button);
    }
    private void dragScroll(double y){scroll=(y-top)/(double)Math.max(1,bottom-top)*contentHeight-(bottom-top)/2.0;clamp();}
    @Override public boolean mouseDragged(double x,double y,int button,double dx,double dy){if(dragging){dragScroll(y);return true;}return super.mouseDragged(x,y,button,dx,dy);}
    @Override public boolean mouseReleased(double x,double y,int button){dragging=false;return super.mouseReleased(x,y,button);}
    @Override public boolean mouseScrolled(double x,double y,double amount){if(y>=top&&y<bottom){scroll-=amount*48;clamp();return true;}return super.mouseScrolled(x,y,amount);}
    @Override public boolean keyPressed(int key,int scan,int modifiers){
        if(!search.isFocused()){
            if(key==268){scroll=0;return true;}if(key==269){scroll=contentHeight;clamp();return true;}
            if(key==266||key==267){scroll+=(key==266?-1:1)*(bottom-top-24);clamp();return true;}
        }return super.keyPressed(key,scan,modifiers);
    }
    @Override public void onClose(){Wire.request(new Wire.Request(session,5,-1,new int[0]));if(active==this)active=null;super.onClose();}
    @Override public boolean isPauseScreen(){return false;}
}

