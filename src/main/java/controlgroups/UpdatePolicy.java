package controlgroups;

/** Real-time seconds: UI remains responsive while the simulation is paused. */
public final class UpdatePolicy {
    public static final float MEMBERSHIP_SECONDS = 0.20f;
    public static final float STATE_SECONDS = 0.10f;
    public static final float DOUBLE_CLICK_SECONDS = 0.40f;
    public static final float SLIDE_SECONDS = 0.13f;

    private UpdatePolicy() {
    }
}
