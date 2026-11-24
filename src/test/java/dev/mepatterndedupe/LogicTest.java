package dev.mepatterndedupe;
import java.util.*;
import java.math.BigInteger;

public final class LogicTest {
    static int checks;
    static void check(boolean b){if(!b)throw new AssertionError("check "+checks);checks++;}
    static Inputs.Ingredient<String> one(String name,long count){return new Inputs.Ingredient<>(List.of(new Inputs.Option<>(name,1)),count);}
    static Inputs.Ingredient<String> alternatives(String first,String second,long count){return new Inputs.Ingredient<>(List.of(new Inputs.Option<>(first,1),new Inputs.Option<>(second,1)),count);}
    record Row(int id,String output,String provider,Object signature,String kind,String byproduct,int outputCount){}
    static final class Port implements PatternActions.Port {
        boolean removeAllowed=true,space=true,commit=true,throwsCommit=false,removeSucceeds=true;
        int patterns=1,blankInMe=0,playerItems=0,restores,removes;
        public boolean canRemove(){return removeAllowed;}
        public boolean canReceive(){return space;}
        public boolean remove(){removes++;if(!removeSucceeds)return false;patterns--;return true;}
        public boolean receive(){if(throwsCommit)throw new IllegalStateException("network vanished");if(commit)blankInMe++;return commit;}
        public void restore(){restores++;patterns++;}
    }
    public static void main(String[] args) {
        var ab=Inputs.signature(List.of(one("iron",2),one("water",1000)));
        check(ab.equals(Inputs.signature(List.of(one("water",1000),one("iron",1),one("iron",1)))));
        check(!ab.equals(Inputs.signature(List.of(one("water",999),one("iron",2)))));
        check(!Inputs.signature(List.of(one("item:iron",1))).equals(Inputs.signature(List.of(one("fluid:iron",1)))));
        check(!Inputs.signature(List.of(one("item:chip{mode:1}",1))).equals(Inputs.signature(List.of(one("item:chip{mode:2}",1)))));
        var options=Inputs.signature(List.of(alternatives("iron","copper",2)));
        check(options.equals(Inputs.signature(List.of(alternatives("iron","copper",1),alternatives("iron","copper",1)))));
        check(!options.equals(Inputs.signature(List.of(one("iron",2)))));
        check(!options.equals(Inputs.signature(List.of(alternatives("copper","iron",2)))));
        check(options.equals(Inputs.signature(List.of(new Inputs.Ingredient<>(List.of(new Inputs.Option<>("iron",2),new Inputs.Option<>("copper",2)),1)))));
        check(Inputs.signature(List.of(new Inputs.Ingredient<>(List.of(new Inputs.Option<>("x",Long.MAX_VALUE)),Long.MAX_VALUE))).values().iterator().next().equals(BigInteger.valueOf(Long.MAX_VALUE).pow(2)));
        var a=Inputs.signature(List.of(one("iron",1)));
        var b=Inputs.signature(List.of(one("copper",1)));
        List<Row> rows=List.of(new Row(0,"plate","providerA",a,"crafting","slag",1),new Row(1,"plate","providerA",b,"processing","dust",2),new Row(2,"plate","providerB",a,"processing","none",9),new Row(3,"wire","providerC",a,"processing","ash",1),new Row(4,"wire","providerD",a,"crafting","dust",10),new Row(5,"plate{nbt:2}","providerA",a,"processing","none",1));
        check(Inputs.conflicts(rows,Row::output,Row::signature).stream().map(Row::id).toList().equals(List.of(0,1,2)));
        check(Inputs.conflicts(List.of(rows.get(0),rows.get(2)),Row::output,Row::signature).isEmpty());
        check(Inputs.conflicts(List.of(rows.get(0),rows.get(5)),Row::output,Row::signature).isEmpty());
        Random random=new Random(7);
        for(int i=0;i<1000;i++) {
            int x=1+random.nextInt(100000),y=1+random.nextInt(100000);
            var left=Inputs.signature(List.of(one("x",x),one("y",y)));
            var reordered=Inputs.signature(List.of(one("y",y),one("x",x)));
            check(left.equals(reordered));
            check(!left.equals(Inputs.signature(List.of(one("x",x+1),one("y",y)))));
        }
        Port p=new Port();check(PatternActions.transfer(p)==null);check(p.patterns==0&&p.blankInMe==1&&p.playerItems==0&&p.restores==0);
        p=new Port();p.space=false;check(PatternActions.transfer(p)!=null);check(p.patterns==1&&p.blankInMe==0&&p.removes==0);
        p=new Port();p.removeAllowed=false;check(PatternActions.transfer(p)!=null);check(p.removes==0&&p.blankInMe==0);
        p=new Port();p.commit=false;check(PatternActions.transfer(p)!=null);check(p.patterns==1&&p.blankInMe==0&&p.restores==1);
        p=new Port();p.throwsCommit=true;try{PatternActions.transfer(p);throw new AssertionError();}catch(IllegalStateException expected){check(p.patterns==1&&p.restores==1&&p.blankInMe==0);}
        p=new Port();p.removeSucceeds=false;check(PatternActions.transfer(p)!=null);check(p.patterns==1&&p.blankInMe==0&&p.restores==0);
        // A batch must preserve each failed source independently, including a later commit failure.
        int recycled=0,remaining=0,returned=0;
        for(int i=0;i<5000;i++){
            Port member=new Port();member.space=i%7!=0;member.commit=i%11!=0;member.removeAllowed=i%13!=0;
            String failure=PatternActions.transfer(member);
            check(failure==null?member.patterns==0&&member.blankInMe==1:member.patterns==1&&member.blankInMe==0);
            check(member.playerItems==0);
            if(failure==null)recycled++;remaining+=member.patterns;returned+=member.blankInMe;
        }
        check(remaining+recycled==5000&&returned==recycled);
        System.out.println("PASS: "+checks+" input/grouping/transaction assertions");
    }
}

