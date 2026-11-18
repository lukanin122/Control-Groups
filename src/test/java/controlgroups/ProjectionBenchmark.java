package controlgroups;

import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Locale;

/** Prepared multi-part fixture and a no-GL batch: CPU/allocations only, not a game/GPU benchmark. */
public final class ProjectionBenchmark {
    public static void main(String[] args) {
        try (var f = new ProjectionColorTest.Fixture()) {
            f.trace.capture = false;
            var bean = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
            long thread = Thread.currentThread().getId();
            System.out.println("No-GL prepared fixture, mirrored/nested parts; Java " + System.getProperty("java.version"));
            for (boolean advanced : new boolean[]{false, true}) for (int groups : new int[]{1, 11}) {
                long until = System.nanoTime() + 2_000_000_000L;
                for (int batch = 0; batch < 1000 || System.nanoTime() < until; batch++) draw(f, advanced, groups);
                long[] times = new long[1000];
                long before = bean.getThreadAllocatedBytes(thread);
                for (int batch = 0; batch < times.length; batch++) {
                    long started = System.nanoTime(); draw(f, advanced, groups); times[batch] = System.nanoTime() - started;
                }
                long allocated = bean.getThreadAllocatedBytes(thread) - before;
                Arrays.sort(times);
                System.out.printf(Locale.ROOT, "advanced=%s groups=%d median/p95 %.2f/%.2f us/batch, %.1f B/batch%n",
                    advanced, groups, times[500] / 1000d, times[950] / 1000d, allocated / 1000d);
                if (allocated > 1024) throw new AssertionError("Unexpected frame allocations: " + allocated);
            }
        }
    }

    private static void draw(ProjectionColorTest.Fixture f, boolean advanced, int groups) {
        for (int group = 0; group < groups; group++)
            f.projection.draw(f.unit, group, 100, 100, 80, 180, 8, 1f / 60, advanced, .8f);
    }
}
