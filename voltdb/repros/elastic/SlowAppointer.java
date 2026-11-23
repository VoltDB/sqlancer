import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.event.*;
import com.sun.jdi.request.*;
import java.util.*;

// SlowAppointer PORT DELAY_MS SECONDS HOST_ID (ENG-30758): attaches to a VoltDB server's JDWP port and, for SECONDS,
// holds the LeaderAppointer-Babysitters thread for DELAY_MS whenever a partition's replica-change callback
// (LeaderAppointer.PartitionCallback) whose current leader is on host HOST_ID enters LeaderAppointer.isClusterKSafe,
// then detaches. Only that thread is suspended: the server's ZooKeeper, its Failed Host monitor and its MP repairs run
// on. This stands in for the slow LeaderAppointer of the sighting (an instrumented build under load): the callback has
// read the partition's replicas, and isClusterKSafe, which may clean up the partition, reads ZooKeeper DELAY_MS later.
public class SlowAppointer {
    public static void main(String[] a) throws Exception {
        int port = Integer.parseInt(a[0]);
        long delay = Long.parseLong(a[1]);
        long end = System.currentTimeMillis() + 1000L * Long.parseLong(a[2]);
        int onlyHost = Integer.parseInt(a[3]);
        AttachingConnector c = Bootstrap.virtualMachineManager().attachingConnectors().stream()
                .filter(x -> x.name().equals("com.sun.jdi.SocketAttach")).findFirst().get();
        Map<String, Connector.Argument> args = c.defaultArguments();
        args.get("hostname").setValue("localhost");
        args.get("port").setValue(String.valueOf(port));
        VirtualMachine vm = c.attach(args);
        ReferenceType t = vm.classesByName("org.voltdb.iv2.LeaderAppointer").get(0);
        Method m = t.methodsByName("isClusterKSafe").get(0);
        BreakpointRequest bp = vm.eventRequestManager().createBreakpointRequest(m.location());
        bp.setSuspendPolicy(EventRequest.SUSPEND_EVENT_THREAD);
        bp.enable();
        System.out.println("attached; holding callbacks led by host " + onlyHost + " for " + delay + " ms");
        EventQueue q = vm.eventQueue();
        while (System.currentTimeMillis() < end) {
            EventSet es = q.remove(200);
            if (es == null) {
                continue;
            }
            for (Event e : es) {
                if (!(e instanceof BreakpointEvent be) || !be.thread().name().startsWith("LeaderAppointer-Babysitters")) {
                    continue;
                }
                // the caller, PartitionCallback.runInterruptibly: its partition and current leader (host id: low 32 bits)
                String what;
                int leaderHost = -1;
                try {
                    ObjectReference cb = be.thread().frame(1).thisObject();
                    ReferenceType ct = cb.referenceType();
                    long leader = ((LongValue) cb.getValue(ct.fieldByName("m_currentLeader"))).value();
                    leaderHost = (int) leader;
                    what = "partition " + cb.getValue(ct.fieldByName("m_partitionId")) + ", leader " + (int) leader
                            + ":" + (leader >> 32);
                } catch (Exception x) {
                    what = "not a partition callback (" + x.getClass().getSimpleName() + ")";
                }
                boolean hold = leaderHost == onlyHost;
                System.out.println(System.currentTimeMillis() + (hold ? " holding " : " passing ") + what);
                if (hold) {
                    Thread.sleep(delay);
                }
            }
            es.resume();
        }
        bp.disable();
        vm.dispose();
        System.out.println("detached");
    }
}
