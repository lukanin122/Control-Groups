package controlgroups;

import arc.Events;
import mindustry.Vars;
import mindustry.game.EventType.ClientLoadEvent;
import mindustry.game.EventType.DisposeEvent;
import mindustry.game.EventType.ResetEvent;
import mindustry.game.EventType.UnitDamageEvent;
import mindustry.game.EventType.WorldLoadBeginEvent;
import mindustry.mod.Mod;

/** Client-only integration; the game remains the owner of saved groups and selection. */
public class ControlGroupsMod extends Mod {
    private GroupsHud hud;

    public ControlGroupsMod() {
        Events.on(ClientLoadEvent.class, event -> {
            if (Vars.headless || hud != null) return;
            hud = new GroupsHud(); hud.attach();
        });
        Events.on(WorldLoadBeginEvent.class, event -> reset());
        Events.on(ResetEvent.class, event -> reset());
        Events.on(UnitDamageEvent.class, event -> { if (hud != null) hud.damage(event.unit); });
        Events.on(DisposeEvent.class, event -> { if (hud != null) { hud.dispose(); hud = null; } });
    }

    private void reset() {
        if (hud != null) hud.reset();
    }
}
