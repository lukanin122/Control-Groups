package controlgroups;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Synthetic client snapshots only. No claims about GPU cost, networking or real frame time. */
public final class CoreBenchmark {
    public static void main(String[] args) {
        System.out.println("Synthetic update benchmark, Java " + System.getProperty("java.version"));
        System.out.println("Times: microseconds per operation after warmup; allocations: bytes per operation.");
        for (int count : new int[]{500, 1000, 5000, 10000}) {
            measure(count, false); measure(count, true);
        }
    }

    private static void measure(int count, boolean overlap) {
        TestWorld w = new TestWorld(40);
        for (int i = 0; i < count; i++) {
            var unit = w.add(i + 1, i % 40); unit.vel.set(1, 0);
        }
        for (int g = 0; g < 10; g++) w.group(g);
        for (int i = 0; i < count; i++) {
            if (overlap) for (int g = 0; g < 10; g++) w.saved[g].add(i + 1);
            else w.saved[i % 10].add(i + 1);
        }
        w.reconcile(true); w.sample(1);
        for (int i = 0; i < 1000; i++) { w.reconcile(true); w.sample(1); }
        long[] memberships = new long[1000], states = new long[1000];
        com.sun.management.ThreadMXBean bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
        if (bean.isThreadAllocatedMemorySupported()) bean.setThreadAllocatedMemoryEnabled(true);
        long id = Thread.currentThread().getId();
        long before = bean.isThreadAllocatedMemoryEnabled() ? bean.getThreadAllocatedBytes(id) : 0;
        for (int i = 0; i < memberships.length; i++) {
            w.reconcile(true); memberships[i] = w.model.membershipNanos;
            w.sample(1); states[i] = w.model.stateNanos;
        }
        long allocated = bean.isThreadAllocatedMemoryEnabled() ? bean.getThreadAllocatedBytes(id) - before : -1;
        Arrays.sort(memberships); Arrays.sort(states);
        System.out.printf(Locale.ROOT, "%5d units, %2dx membership | reconcile median/p95 %.1f/%.1f us | state %.1f/%.1f us | %.1f B/pair | sorts %d%n",
            count, overlap ? 10 : 1, memberships[500] / 1000.0, memberships[950] / 1000.0,
            states[500] / 1000.0, states[950] / 1000.0, allocated < 0 ? -1 : allocated / 1000.0, w.model.sorts);
    }
}
