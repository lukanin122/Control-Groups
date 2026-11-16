package controlgroups;

import org.junit.Test;
import static org.junit.Assert.*;

public class TelemetryTest {
    @Test
    public void closedTelemetryStopsAllDiagnosticCountersWhileWorkContinues() {
        TestWorld w = new TestWorld(1); w.add(1, 0); w.group(0, 1);
        w.model.telemetry = false;
        HudLayout layout = new HudLayout(w.model);
        w.reconcile(true); w.sample(0); layout.rebuild(w.settings, w.profiles, 800, 600, 1, false);
        assertEquals(1, w.model.observedUnits()); assertEquals(1, w.model.groups[0].members.size);
        assertEquals(0, w.model.reconciliations); assertEquals(0, w.model.stateUpdates);
        assertEquals(0, w.model.sorts); assertEquals(0, w.model.classifiedUnits);
        assertEquals(0, w.model.membershipNanos); assertEquals(0, w.model.stateNanos); assertEquals(0, layout.rebuilds);
        w.model.telemetry = layout.telemetry = true;
        w.reconcile(true); w.sample(.1); layout.rebuild(w.settings, w.profiles, 800, 600, 1, false);
        assertEquals(1, w.model.reconciliations); assertEquals(1, w.model.stateUpdates);
        assertEquals(1, w.model.classifiedUnits); assertEquals(1, layout.rebuilds);
        long membership = w.model.membershipNanos, state = w.model.stateNanos, sorts = w.model.sorts;
        w.model.telemetry = layout.telemetry = false;
        w.add(2, 0); w.saved[0].add(2);
        w.reconcile(true); w.sample(.2); layout.rebuild(w.settings, w.profiles, 800, 600, 1, false);
        assertEquals(2, w.model.groups[0].members.size);
        assertEquals(1, w.model.reconciliations); assertEquals(1, w.model.stateUpdates);
        assertEquals(sorts, w.model.sorts); assertEquals(1, w.model.classifiedUnits);
        assertEquals(membership, w.model.membershipNanos); assertEquals(state, w.model.stateNanos);
        assertEquals(1, layout.rebuilds);
    }
}
