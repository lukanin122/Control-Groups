package controlgroups;

import arc.Core;
import arc.graphics.Color;
import arc.graphics.g2d.Draw;
import arc.graphics.g2d.Fill;
import arc.graphics.g2d.Font;
import arc.graphics.g2d.Lines;
import arc.graphics.g2d.TextureRegion;
import arc.input.KeyCode;
import arc.math.Mathf;
import arc.math.geom.Rect;
import arc.math.geom.Vec2;
import arc.scene.Element;
import arc.scene.event.InputEvent;
import arc.scene.event.InputListener;
import arc.scene.style.Drawable;
import arc.scene.ui.layout.Scl;
import arc.struct.Seq;
import arc.util.Align;
import mindustry.Vars;
import mindustry.game.Team;
import mindustry.gen.Groups;
import mindustry.gen.Icon;
import mindustry.gen.Tex;
import mindustry.gen.Unit;
import mindustry.input.InputHandler;
import mindustry.ui.Fonts;

import java.util.function.IntFunction;

/** One actor for all group buttons. Only actual controls and visible rectangles take input. */
public final class GroupsHud extends Element {
    private static final Color EDITOR_COLOR = Color.valueOf("ffd37f");
    private static final String[] GROUP_LABELS = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "*"};
    public final ModSettings settings;
    public final UnitProfiles profiles;
    public final GroupModel model;
    public final HudLayout layout;
    private final UnitProjection projection = new UnitProjection();
    private final TextureRegion settingsIcon = Icon.settings.getRegion();
    private final TextureRegion[] activityIcons = new TextureRegion[Activity.values().length];
    private final IntFunction<Unit> resolve = id -> Groups.unit.getByID(id);
    private final Vec2 pointer = new Vec2(), camera = new Vec2();
    private final Seq<Unit> selection = new Seq<>();
    private final HudEditor editorPanel;
    private final UnitSettingsPanel unitPanel;
    private InputHandler handler;
    private Team team;
    private boolean wasActive, dirty = true;
    private final long clockOrigin = System.nanoTime();
    public boolean editing;
    private double now, membershipAt, stateAt, lastClickAt;
    private int settingsRevision = -1, profileRevision = -1, modelRevision = -1;
    private float sceneWidth = -1, sceneHeight = -1, uiScale = -1;
    private float delta = 1f / 60f, pressX, pressY, startX, startY, startWidth, startHeight;
    private int resizeEdges, pressedGroup = -1, pressedType = -1, pressedUnit = -1;
    private boolean dragging, moveDrag, pressedZero;
    private long lastClickKey = Long.MIN_VALUE;
    public long drawNanos;

    public GroupsHud() {
        name = "control-groups-hud";
        settings = new ModSettings(Core.settings);
        profiles = new UnitProfiles(Core.settings, Vars.content.units());
        model = new GroupModel(profiles, Vars.content.units());
        layout = new HudLayout(model);
        for (Activity activity : Activity.values()) {
            if (activity.icon == null) continue;
            var icon = Icon.icons.get(activity.icon);
            TextureRegion region = icon == null ? null : icon.getRegion();
            if (activity == Activity.MOVE && region == null) {
                region = Core.atlas.find("block-omega-mech-pad-ui");
                // The legacy glyph constant survives in 160.7, but its texture is absent in the desktop atlas.
                if (!region.found()) region = Icon.left.getRegion();
            }
            activityIcons[activity.ordinal()] = region != null && region.found() ? region : Icon.cancel.getRegion();
        }
        editorPanel = new HudEditor(this);
        unitPanel = new UnitSettingsPanel(this);
        addListener(new InputListener() {
            @Override
            public boolean touchDown(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (pointer != 0 || button != KeyCode.mouseLeft || !interactive()) return false;
                pressX = x + GroupsHud.this.x; pressY = y + GroupsHud.this.y;
                startX = GroupsHud.this.x; startY = GroupsHud.this.y;
                startWidth = width; startHeight = height;
                pressedZero = layout.zero.contains(x, y);
                if (pressedZero) return true;
                if (editing) {
                    resizeEdges = edges(x, y);
                    dragging = resizeEdges != 0;
                    return dragging;
                }
                findTarget(x, y);
                return pressedGroup >= 0;
            }

            @Override
            public void touchDragged(InputEvent event, float x, float y, int pointer) {
                if (dragging && pointer == 0) drag(x + GroupsHud.this.x, y + GroupsHud.this.y);
            }

            @Override
            public void touchUp(InputEvent event, float x, float y, int pointer, KeyCode button) {
                if (pointer != 0 || button != KeyCode.mouseLeft) return;
                if (dragging) { finishDrag(); return; }
                float dx = x + GroupsHud.this.x - pressX, dy = y + GroupsHud.this.y - pressY;
                if (dx * dx + dy * dy <= 64 * layout.scale * layout.scale && interactive()) {
                    if (pressedZero && layout.zero.contains(x, y)) toggleEditor();
                    else if (!editing && pressedGroup >= 0 && layout.boxes[pressedGroup].shown.contains(x, y)) clickSelection();
                }
                pressedGroup = -1; pressedZero = false;
            }
        });
    }

    public void attach() {
        Vars.ui.hudGroup.addChild(this);
        Vars.ui.hudGroup.addChild(editorPanel);
        Vars.ui.hudGroup.addChild(unitPanel);
        editorPanel.visible = unitPanel.visible = false;
    }

    /** Arc calls this even for hidden children; hidden actors do not receive act(). */
    @Override
    public void updateVisibility() {
        now = clockSeconds();
        InputHandler current = Vars.control == null ? null : Vars.control.input;
        Team currentTeam = Vars.player == null ? null : Vars.player.team();
        if (handler != current || team != currentTeam) {
            reset(); handler = current; team = currentTeam;
        }
        boolean active = current != null && currentTeam != null && Vars.state.isGame() && current.commandMode &&
            Vars.ui.hudfrag.shown() && !current.logicHideHud;
        visible = active;
        if (!active) {
            editorPanel.visible = unitPanel.visible = false;
            if (wasActive) {
                dragging = false; pressedGroup = -1; lastClickKey = Long.MIN_VALUE;
                Core.scene.unfocus(editorPanel); Core.scene.unfocus(unitPanel);
            }
            wasActive = false;
        }
    }

    @Override
    public void act(float delta) {
        super.act(delta);
        this.delta = Math.min(Math.max(delta, 0f), 0.1f);
        if (!visible || handler == null || team == null) return;
        now = clockSeconds();
        InputHandler current = handler;
        Team currentTeam = team;
        if (!wasActive) { membershipAt = stateAt = 0; dirty = true; }
        wasActive = true;
        if (settingsRevision != settings.revision || profileRevision != profiles.revision) {
            dirty = true; membershipAt = stateAt = 0;
            settingsRevision = settings.revision; profileRevision = profiles.revision;
        }
        if (now >= membershipAt) {
            model.reconcile(current.controlGroups, resolve, currentTeam.data().units, currentTeam, settings.unassigned);
            membershipAt = now + UpdatePolicy.MEMBERSHIP_SECONDS;
        }
        if (now >= stateAt) {
            model.sample(currentTeam, settings, now);
            stateAt = now + UpdatePolicy.STATE_SECONDS;
        }
        float sw = Core.scene.getWidth(), sh = Core.scene.getHeight(), scale = Scl.scl(1f);
        if (sceneWidth != sw || sceneHeight != sh || uiScale != scale || modelRevision != model.revision) dirty = true;
        if (dirty) {
            layout.rebuild(settings, profiles, sw, sh, scale, editing);
            setBounds(settings.xFraction * Math.max(0, sw - layout.width), settings.yFraction * Math.max(0, sh - layout.height), layout.width, layout.height);
            if (settings.collapse && layout.dock == null) { settings.collapse = false; settings.changed(); }
            sceneWidth = sw; sceneHeight = sh; uiScale = scale; modelRevision = model.revision;
            dirty = false;
            editorPanel.positionPanel(); unitPanel.positionPanel();
        }
        Core.scene.screenToStageCoordinates(pointer.set(Core.input.mouseX(), Core.input.mouseY()));
        pointer.sub(x, y);
        for (int i = 0; i < layout.orderCount; i++) {
            HudLayout.Box box = layout.boxes[layout.order[i]];
            boolean hovered = box.shown.contains(pointer.x, pointer.y) && !Core.scene.hasDialog();
            layout.animate(box, settings, editing, hovered, this.delta);
        }
        editorPanel.visible = editing && !Core.scene.hasDialog();
        unitPanel.visible = editing && unitPanel.open && !Core.scene.hasDialog();
    }

    private boolean interactive() {
        return visible && handler != null && handler.commandMode && !handler.locked() && !Core.scene.hasDialog();
    }

    @Override
    public Element hit(float x, float y, boolean touchable) {
        if (!interactive()) return null;
        if (layout.zero.contains(x, y)) return this;
        if (editing) return edges(x, y) != 0 ? this : null;
        for (int i = 0; i < layout.orderCount; i++) {
            HudLayout.Box box = layout.boxes[layout.order[i]];
            if (box.visible && box.shown.contains(x, y)) return this;
        }
        return null;
    }

    @Override
    public void draw() {
        long started = System.nanoTime();
        float savedColor = Draw.getColorPacked(), savedMix = Draw.getMixColorPacked(), stroke = Lines.getStroke();
        try {
            Draw.color(); Draw.mixcol();
            if (editing) {
                Draw.color(Color.gray, 0.55f);
                Lines.stroke(layout.scale);
                Lines.rect(x, y, width, height);
            }
            panel(layout.zero, editing ? EDITOR_COLOR : Color.gray, 1f);
            icon(settingsIcon, x + layout.zero.x + layout.zero.width / 2f, y + layout.zero.y + layout.zero.height / 2f, 24f * layout.scale, 0, Color.white, 1f);
            for (int i = 0; i < layout.orderCount; i++) {
                HudLayout.Box box = layout.boxes[layout.order[i]];
                if (editing) {
                    Draw.color(Color.gray, 0.35f); Lines.stroke(layout.scale);
                    Lines.rect(x + box.full.x, y + box.full.y, box.full.width, box.full.height);
                }
                if (box.visible) drawBox(box, editing ? 0.32f : 1f);
            }
        } finally {
            Draw.color(savedColor); Draw.mixcol(savedMix); Lines.stroke(stroke);
            drawNanos = System.nanoTime() - started;
        }
    }

    private void drawBox(HudLayout.Box box, float alpha) {
        Color border = box.group.activity.color;
        panel(box.shown, border, alpha);
        float textSize = 0.55f * layout.scale;
        text(GROUP_LABELS[box.group.index],
            x + box.shown.x + 5 * layout.scale, y + box.shown.y + box.shown.height - 6 * layout.scale,
            Color.lightGray, textSize, Align.topLeft, alpha);
        if (box.expansion > 0.01f && box.content.width > 0 && box.content.height > 0) {
            Draw.flush();
            if (clipBegin(x + box.content.x, y + box.content.y, box.content.width, box.content.height)) {
                try {
                    // Low tiers first; within each tier low types first, so higher priorities cover them.
                    int end = box.cellCount;
                    while (end > 0) {
                        int start = end - 1;
                        int tier = box.cells[start].bucket.profile.tier;
                        while (start > 0 && box.cells[start - 1].bucket.profile.tier == tier) start--;
                        for (int i = start; i < end; i++) drawCell(box.cells[i], alpha * box.expansion);
                        end = start;
                    }
                } finally { Draw.flush(); clipEnd(); }
            }
        }
        if (settings.projection && box.projection.width > 0 && box.group.representative != null) {
            Unit unit = box.group.representative.unit;
            if (GroupModel.available(unit, team)) {
                var profile = profiles.get(unit.type);
                float pixels = layout.projectionPixels(unit.type.hitSize, settings) * profile.resize;
                projection.draw(unit, box.group.index, x + box.projection.x + box.projection.width / 2,
                    y + box.projection.y + box.projection.height / 2, pixels, box.projection.width, delta, settings.advanced, alpha);
            }
            Draw.color(border, alpha); Lines.stroke(layout.scale);
            Rect r = box.projection;
            switch (layout.centerSide) {
                case LEFT -> Lines.line(x + r.x + r.width, y + box.shown.y, x + r.x + r.width, y + box.shown.y + box.shown.height);
                case RIGHT -> Lines.line(x + r.x, y + box.shown.y, x + r.x, y + box.shown.y + box.shown.height);
                case BOTTOM -> Lines.line(x + box.shown.x, y + r.y + r.height, x + box.shown.x + box.shown.width, y + r.y + r.height);
                case TOP -> Lines.line(x + box.shown.x, y + r.y, x + box.shown.x + box.shown.width, y + r.y);
            }
        }
        if (box.group.activity.icon != null) {
            float cx = x + box.shown.x + box.shown.width / 2, cy = y + box.shown.y + box.shown.height / 2;
            switch (layout.centerSide) {
                case LEFT -> cx = x + box.shown.x;
                case RIGHT -> cx = x + box.shown.x + box.shown.width;
                case BOTTOM -> cy = y + box.shown.y;
                case TOP -> cy = y + box.shown.y + box.shown.height;
            }
            float size = Math.min(16f * layout.scale, Math.min(box.shown.width, box.shown.height));
            Draw.color(0.08f, 0.08f, 0.08f, alpha * 0.95f);
            Fill.rect(cx, cy, size + 2 * layout.scale, size + 2 * layout.scale);
            float rotation = box.group.activity == Activity.MOVE ? directionAngle() - 180f :
                box.group.activity == Activity.PURSUE ? directionAngle() - 90f : 0;
            icon(activityIcons[box.group.activity.ordinal()], cx, cy, size, rotation, border, alpha);
        }
    }

    private float directionAngle() {
        return switch (layout.centerSide) { case RIGHT -> 0f; case TOP -> 90f; case LEFT -> 180f; case BOTTOM -> 270f; };
    }

    private void drawCell(HudLayout.Cell cell, float alpha) {
        var region = cell.bucket.profile.unit.uiIcon;
        if (region == null || region.texture == null || region.width <= 0 || region.height <= 0) return;
        float iconSize = Math.min(24f * layout.scale, Math.min(cell.rect.height, cell.rect.width));
        float cx = x + cell.rect.x + cell.rect.width - iconSize / 2f;
        float cy = y + cell.rect.y + cell.rect.height / 2;
        Draw.color(Color.white, alpha);
        float ratio = iconSize / Math.max(region.width, region.height);
        Draw.rect(region, cx, cy, region.width * ratio, region.height * ratio);
        if (!cell.bucket.countText.isEmpty()) text(cell.bucket.countText,
            cx - iconSize / 2 - 2f * layout.scale, cy, cell.bucket.profile.color,
            0.65f * layout.scale, Align.right, alpha);
    }

    private void panel(Rect rect, Color border, float alpha) {
        Draw.color(Color.white, alpha);
        Tex.pane.draw(x + rect.x, y + rect.y, rect.width, rect.height);
        Draw.color(border, alpha); Lines.stroke(layout.scale);
        Lines.rect(x + rect.x, y + rect.y, rect.width, rect.height);
    }

    private static void icon(TextureRegion region, float x, float y, float size, float rotation, Color color, float alpha) {
        Draw.color(color, alpha);
        Draw.rect(region, x, y, size, size, rotation);
    }

    private static void text(String value, float x, float y, Color color, float scale, int align, float alpha) {
        Font font = Fonts.outline;
        Color previous = font.getColor();
        float oldR = previous.r, oldG = previous.g, oldB = previous.b, oldA = previous.a;
        float oldX = font.getData().scaleX, oldY = font.getData().scaleY;
        try {
            font.setColor(color.r, color.g, color.b, alpha);
            font.getData().setScale(scale);
            if ((align & Align.top) == 0) y += font.getCapHeight() / 2f;
            font.draw(value, x, y, 0, align, false);
        } finally {
            font.setColor(oldR, oldG, oldB, oldA); font.getData().setScale(oldX, oldY);
        }
    }

    private void findTarget(float x, float y) {
        pressedGroup = -1; pressedType = pressedUnit = -1;
        for (int i = 0; i < layout.orderCount; i++) {
            HudLayout.Box box = layout.boxes[layout.order[i]];
            if (!box.visible || !box.shown.contains(x, y)) continue;
            pressedGroup = box.group.index;
            if (settings.projection && box.projection.contains(x, y) && box.group.representative != null) {
                pressedUnit = box.group.representative.unit.id;
            } else if (box.expansion > 0.85f && box.content.contains(x, y)) {
                // Match the draw order in regions where cells overlap.
                int start = 0;
                while (start < box.cellCount) {
                    int end = start + 1, tier = box.cells[start].bucket.profile.tier;
                    while (end < box.cellCount && box.cells[end].bucket.profile.tier == tier) end++;
                    for (int j = end - 1; j >= start; j--) if (box.cells[j].rect.contains(x, y)) {
                        pressedType = box.cells[j].bucket.profile.unit.id; return;
                    }
                    start = end;
                }
            }
            return;
        }
    }

    private void clickSelection() {
        // Reconcile on an explicit action: group edits/deaths between scheduled updates cannot select stale members.
        model.reconcile(handler.controlGroups, resolve, team.data().units, team, settings.unassigned);
        selection.clear();
        var group = model.groups[pressedGroup];
        for (int i = 0; i < group.members.size; i++) {
            Unit unit = group.members.get(i).unit;
            if (!GroupModel.available(unit, team) || !unit.isCommandable() || !unit.allowCommand()) continue;
            if (pressedType >= 0 && unit.type.id != pressedType) continue;
            if (pressedUnit >= 0 && unit.id != pressedUnit) continue;
            selection.add(unit);
        }
        if (selection.isEmpty()) return;
        handler.selectedUnits.set(selection);
        handler.commandBuildings.clear();
        long key = pressedUnit >= 0 ? (1L << 62) | ((long)pressedUnit << 4) | pressedGroup :
            pressedType >= 0 ? (1L << 61) | ((long)pressedType << 4) | pressedGroup : pressedGroup;
        if (key == lastClickKey && now - lastClickAt <= UpdatePolicy.DOUBLE_CLICK_SECONDS) {
            camera.setZero();
            for (int i = 0; i < selection.size; i++) camera.add(selection.get(i));
            camera.scl(1f / selection.size);
            handler.panCamera(camera);
            lastClickKey = Long.MIN_VALUE;
        } else lastClickKey = key;
        lastClickAt = now;
        selection.clear();
    }

    public void toggleEditor() {
        editing = !editing; dirty = true;
        unitPanel.open = false;
        if (editing) editorPanel.refreshToolbar();
        Core.scene.unfocus(editorPanel); Core.scene.unfocus(unitPanel);
    }

    public void openUnits() {
        unitPanel.open = !unitPanel.open;
        unitPanel.positionPanel();
        if (unitPanel.open) unitPanel.toFront();
    }

    public void changed() {
        settings.changed(); dirty = true;
    }

    public void profileChanged(UnitProfiles.Profile profile) {
        profiles.save(profile); dirty = true;
    }

    public void rotate() {
        settings.vertical = !settings.vertical; changed(); editorPanel.refreshToolbar();
    }

    public void beginMove(float stageX, float stageY) {
        dragging = moveDrag = true; resizeEdges = 0;
        pressX = stageX; pressY = stageY; startX = x; startY = y; startWidth = width; startHeight = height;
    }

    public void drag(float stageX, float stageY) {
        float dx = stageX - pressX, dy = stageY - pressY;
        float nx = startX, ny = startY, nw = startWidth, nh = startHeight;
        float sw = Core.scene.getWidth(), sh = Core.scene.getHeight();
        if (moveDrag) { nx += dx; ny += dy; }
        else {
            float minWidth = Math.min(sw, (settings.vertical ? 48 : 160) * layout.scale);
            float minHeight = Math.min(sh, (settings.vertical ? 160 : 48) * layout.scale);
            if ((resizeEdges & 1) != 0) { nx = Mathf.clamp(startX + dx, 0, startX + startWidth - minWidth); nw = startX + startWidth - nx; }
            if ((resizeEdges & 2) != 0) nw = Mathf.clamp(startWidth + dx, minWidth, sw - startX);
            if ((resizeEdges & 4) != 0) { ny = Mathf.clamp(startY + dy, 0, startY + startHeight - minHeight); nh = startY + startHeight - ny; }
            if ((resizeEdges & 8) != 0) nh = Mathf.clamp(startHeight + dy, minHeight, sh - startY);
        }
        nx = Mathf.clamp(nx, 0, Math.max(0, sw - nw)); ny = Mathf.clamp(ny, 0, Math.max(0, sh - nh));
        settings.length = (settings.vertical ? nh : nw) / layout.scale;
        settings.breadth = (settings.vertical ? nw : nh) / layout.scale;
        settings.xFraction = sw > nw ? nx / (sw - nw) : 0;
        settings.yFraction = sh > nh ? ny / (sh - nh) : 0;
        settings.absoluteX = nx / layout.scale; settings.absoluteY = ny / layout.scale;
        // Dragging updates geometry but persists once on release.
        dirty = true;
    }

    public void finishDrag() {
        dragging = moveDrag = false; resizeEdges = 0; changed();
    }

    private int edges(float px, float py) {
        float margin = 6f * Math.max(1, layout.scale);
        if (px < -margin || py < -margin || px > width + margin || py > height + margin) return 0;
        int result = 0;
        if (Math.abs(px) <= margin) result |= 1;
        if (Math.abs(px - width) <= margin) result |= 2;
        if (Math.abs(py) <= margin) result |= 4;
        if (Math.abs(py - height) <= margin) result |= 8;
        return result;
    }

    boolean onEditorEdge(float px, float py) {
        return editing && edges(px, py) != 0;
    }

    public void damage(Unit unit) {
        model.damage(unit, clockSeconds(), settings.damageTicks);
    }

    private double clockSeconds() {
        return (System.nanoTime() - clockOrigin) / 1_000_000_000.0;
    }

    public void reset() {
        model.clear(); projection.clear(); selection.clear();
        dragging = moveDrag = wasActive = false;
        pressedGroup = -1; lastClickKey = Long.MIN_VALUE;
        membershipAt = stateAt = 0; dirty = true;
        unitPanel.open = false;
        editing = false; editorPanel.visible = unitPanel.visible = false;
        Core.scene.unfocus(editorPanel); Core.scene.unfocus(unitPanel);
    }

    public void dispose() {
        reset(); editorPanel.remove(); unitPanel.remove(); remove();
    }

    public static Drawable drawable(String name) {
        return Icon.icons.get(name, Icon.cancel);
    }
}
