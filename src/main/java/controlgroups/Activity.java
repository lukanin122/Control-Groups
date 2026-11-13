package controlgroups;

import arc.graphics.Color;

/** Priority order follows the functional plan. BLOCKED is a movement presentation variant. */
public enum Activity {
    IDLE(0, "888888", null),
    LOGIC(1, "a387ea", "logic"),
    MOVE(2, "FFBC00", "block-omega-mech-pad"),
    BLOCKED(2, "B28743", null),
    DROWNING(3, "BD3D4C", "waves"),
    PATROL(4, "CEC47B", "refresh"),
    PATROL_BLOCKED(4, "B28743", null),
    MINE(5, "B45B0D", "production"),
    BUILD(6, "8EFA9D", "hammer"),
    REPAIR(7, "57FA6D", "wrench"),
    PURSUE(8, "FF9200", "distribution"),
    ATTACK(9, "FF6600", "mode-attack"),
    DAMAGED(10, "CC0000", "warning"),
    COMBAT(11, "FF0000", "mode-pvp");

    public final int priority;
    public final Color color;
    public final String icon;

    Activity(int priority, String color, String icon) {
        this.priority = priority;
        this.color = Color.valueOf(color);
        this.icon = icon;
    }
}
