package dev.mepatterndedupe;

/** One-item transaction using the inventory APIs' simulate/commit contract. */
public final class PatternActions {
    public interface Port {
        boolean canRemove();
        boolean canReceive();
        boolean remove();
        boolean receive();
        void restore();
    }
    public static String transfer(Port port) {
        if(!port.canRemove())return "设备拒绝取出样板（可能正在使用、锁定或内容已改变）";
        if(!port.canReceive())return "目标没有可用空间，样板保持原位";
        if(!port.remove())return "样板已改变，未执行操作";
        boolean committed=false;
        try {
            committed=port.receive();
            return committed?null:"目标未接收，已恢复原样板";
        } finally {
            if(!committed)port.restore();
        }
    }
}
