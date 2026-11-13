package controlgroups;

import arc.math.Mathf;
import arc.math.geom.Rect;
import controlgroups.GroupModel.Bucket;
import controlgroups.GroupModel.Group;

/** Pure geometry. No scene, renderer, game scan or allocation in animation updates. */
public final class HudLayout {
    public enum Side { LEFT, RIGHT, BOTTOM, TOP }

    public static final class Cell {
        public final Rect rect = new Rect();
        public Bucket bucket;
    }

    public static final class Box {
        public final Group group;
        public final Rect full = new Rect(), shown = new Rect(), projection = new Rect(), content = new Rect();
        public Cell[] cells = new Cell[0];
        public int cellCount;
        public float primary, secondary, projectionSize, expansion = 1f;
        public boolean visible;

        private Box(Group group) {
            this.group = group;
        }
    }

    public final Box[] boxes = new Box[GroupModel.GROUPS];
    public final Rect zero = new Rect();
    public final int[] order = new int[GroupModel.GROUPS];
    public int orderCount;
    public float width, height, length, breadth, maxPrimary, scale, zeroSize, projectionScale;
    public Side centerSide = Side.RIGHT, dock;
    public long rebuilds;
    private boolean vertical, reverse, crossFromLow;
    private float pad, typeGap, tierGap;

    public HudLayout(GroupModel model) {
        for (int i = 0; i < boxes.length; i++) boxes[i] = new Box(model.groups[i]);
    }

    public void rebuild(ModSettings settings, UnitProfiles profiles, float screenWidth, float screenHeight,
                        float scale, boolean editor) {
        this.scale = scale;
        vertical = settings.vertical; reverse = settings.reverse;
        pad = 4f * scale; typeGap = settings.typeGap * scale; tierGap = settings.tierGap * scale;
        length = Math.min(settings.length * scale, vertical ? screenHeight : screenWidth);
        breadth = Math.min(settings.breadth * scale, vertical ? screenWidth : screenHeight);
        length = Math.max(1f, length); breadth = Math.max(1f, breadth);
        width = vertical ? breadth : length; height = vertical ? length : breadth;
        float x = settings.xFraction * Math.max(0, screenWidth - width);
        float y = settings.yFraction * Math.max(0, screenHeight - height);
        centerSide = centerSide(x, y, width, height, screenWidth, screenHeight);
        dock = dock(x, y, width, height, screenWidth, screenHeight, scale);
        crossFromLow = vertical ? (x + width / 2f >= screenWidth / 2f) : (y + height / 2f >= screenHeight / 2f);
        zeroSize = Math.min(44f * scale, Math.min(length, breadth));
        int slots = settings.unassigned ? 11 : 10;
        float gap = Math.min(settings.groupGap * scale, length / (slots * 3f));
        float zeroGap = Math.min(settings.zeroGap * scale, length / 10f);
        maxPrimary = Math.max(1f, (length - zeroSize - zeroGap - gap * (slots - 1)) / slots);
        float maxCompressed = compressed(profiles.largestHitSize, settings);
        float maxSlot = Math.max(1f, Math.min(maxPrimary - 2 * pad, breadth - 2 * pad));
        if (settings.maxProjection > 0) maxSlot = Math.min(maxSlot, settings.maxProjection * scale / settings.occupancy);
        float adaptation = settings.adapt && maxCompressed > 0 ?
            Math.min(1f, maxSlot * settings.occupancy / (maxCompressed * scale)) : 1f;
        projectionScale = scale * adaptation;
        float commonSecondary = zeroSize;
        for (Box box : boxes) {
            box.visible = !box.group.empty() && (box.group.index != GroupModel.UNASSIGNED || settings.unassigned);
            box.projectionSize = 0;
            float idealPrimary = 0, rowWidth = 0, rowHeight = 0, idealSecondary = 0;
            int rows = 0, lastTier = -1;
            for (int i = 0; i < box.group.types.size; i++) {
                Bucket bucket = box.group.types.get(i);
                if (bucket.profile.tier != lastTier) {
                    idealPrimary = Math.max(idealPrimary, Math.max(0, rowWidth - typeGap));
                    idealSecondary += rowHeight + (rows > 0 ? tierGap : 0);
                    rowWidth = rowHeight = 0; rows++; lastTier = bucket.profile.tier;
                }
                rowWidth += cellPrimary(bucket) + typeGap;
                rowHeight = Math.max(rowHeight, cellSecondary(bucket));
                if (settings.projection) box.projectionSize = Math.max(box.projectionSize,
                    compressed(bucket.profile.unit.hitSize, settings) * scale * adaptation / settings.occupancy);
            }
            idealPrimary = Math.max(idealPrimary, Math.max(0, rowWidth - typeGap));
            idealSecondary += rowHeight;
            // A manual resize never participates in these bounds.
            box.projectionSize = Math.min(box.projectionSize, Math.max(1f, Math.min(maxPrimary, breadth) - 2 * pad));
            boolean along = projectionAlongPrimary();
            box.primary = Math.min(maxPrimary, Math.max(24f * scale, idealPrimary + 2 * pad + (along ? box.projectionSize : 0)));
            box.secondary = Math.min(breadth, Math.max(zeroSize, idealSecondary + 2 * pad + (!along ? box.projectionSize : 0)));
            if (box.visible) commonSecondary = Math.max(commonSecondary, box.secondary);
        }
        orderCount = 0;
        if (settings.unassigned && settings.unassignedFirst) order[orderCount++] = GroupModel.UNASSIGNED;
        for (int i = 0; i < 10; i++) order[orderCount++] = i;
        if (settings.unassigned && !settings.unassignedFirst) order[orderCount++] = GroupModel.UNASSIGNED;
        place(zero, 0, outerCross(zeroSize), zeroSize, zeroSize);
        float position = zeroSize + zeroGap;
        for (int i = 0; i < orderCount; i++) {
            Box box = boxes[order[i]];
            if (settings.align) box.secondary = commonSecondary;
            place(box.full, editor ? zeroSize + zeroGap + i * (maxPrimary + gap) : position,
                outerCross(box.secondary), editor ? maxPrimary : box.primary, box.secondary);
            if (box.visible) position += box.primary + gap;
            if (editor) box.expansion = 1;
            animate(box, settings, editor, false, 1f);
            prepareCells(box);
        }
        rebuilds++;
    }

    public void animate(Box box, ModSettings settings, boolean editor, boolean hovered, float delta) {
        boolean collapse = settings.collapse && dock != null && !editor;
        float target = !collapse || (settings.hover && hovered) ? 1f : 0f;
        box.expansion = Mathf.approach(box.expansion, target, delta / UpdatePolicy.SLIDE_SECONDS);
        box.shown.set(box.full);
        float fullCross = vertical ? box.full.width : box.full.height;
        float small = Math.min(fullCross, settings.projection ? box.projectionSize + 2 * pad : 12f * scale);
        float size = Mathf.lerp(small, fullCross, box.expansion);
        if (vertical) {
            box.shown.width = size;
            if (crossFromLow) box.shown.x += fullCross - size;
        } else {
            box.shown.height = size;
            if (crossFromLow) box.shown.y += fullCross - size;
        }
        box.content.set(box.shown).grow(-pad);
        box.content.width = Math.max(0, box.content.width); box.content.height = Math.max(0, box.content.height);
        float square = Math.min(box.projectionSize, Math.min(box.content.width, box.content.height));
        box.projection.set(0, 0, 0, 0);
        if (square > 0 && settings.projection) {
            float cx = box.content.x + box.content.width / 2f, cy = box.content.y + box.content.height / 2f;
            switch (centerSide) {
                case LEFT -> { box.projection.set(box.content.x, cy - square / 2, square, square); box.content.x += square; box.content.width -= square; }
                case RIGHT -> { box.projection.set(box.content.x + box.content.width - square, cy - square / 2, square, square); box.content.width -= square; }
                case BOTTOM -> { box.projection.set(cx - square / 2, box.content.y, square, square); box.content.y += square; box.content.height -= square; }
                case TOP -> { box.projection.set(cx - square / 2, box.content.y + box.content.height - square, square, square); box.content.height -= square; }
            }
        }
    }

    private void prepareCells(Box box) {
        int count = box.group.types.size;
        if (box.cells.length < count) {
            Cell[] enlarged = new Cell[count];
            System.arraycopy(box.cells, 0, enlarged, 0, box.cells.length);
            for (int i = box.cells.length; i < enlarged.length; i++) enlarged[i] = new Cell();
            box.cells = enlarged;
        }
        box.cellCount = count;
        // Cells are prepared against the expanded geometry; animation only clips/reveals them.
        float contentP = vertical ? box.full.height : box.full.width;
        float contentQ = vertical ? box.full.width : box.full.height;
        contentP = Math.max(1, contentP - 2 * pad - (projectionAlongPrimary() ? box.projectionSize : 0));
        contentQ = Math.max(1, contentQ - 2 * pad - (!projectionAlongPrimary() ? box.projectionSize : 0));
        int rows = 0;
        float totalSecondary = 0;
        for (int start = 0; start < count;) {
            int end = start + 1;
            while (end < count && box.group.types.get(end).profile.tier == box.group.types.get(start).profile.tier) end++;
            float size = 0;
            for (int i = start; i < end; i++) size = Math.max(size, cellSecondary(box.group.types.get(i)));
            totalSecondary += Math.min(size, contentQ) + (rows == 0 ? 0 : tierGap);
            rows++; start = end;
        }
        float rowCursor = 0;
        int rowIndex = 0;
        for (int start = 0; start < count; rowIndex++) {
            int end = start + 1;
            while (end < count && box.group.types.get(end).profile.tier == box.group.types.get(start).profile.tier) end++;
            float total = 0, rowHeight = 0;
            for (int i = start; i < end; i++) {
                total += Math.min(contentP, cellPrimary(box.group.types.get(i))) + (i == start ? 0 : typeGap);
                rowHeight = Math.max(rowHeight, cellSecondary(box.group.types.get(i)));
            }
            rowHeight = Math.min(rowHeight, contentQ);
            float available = contentP;
            float cursor = Math.max(0, (available - total) / 2f);
            for (int i = start; i < end; i++) {
                Cell cell = box.cells[i]; cell.bucket = box.group.types.get(i);
                float pSize = Math.min(contentP, cellPrimary(cell.bucket));
                float qSize = Math.min(rowHeight, cellSecondary(cell.bucket));
                float rowPosition = totalSecondary > contentQ && rows > 1 ?
                    (contentQ - rowHeight) * rowIndex / (rows - 1) : rowCursor;
                float q = crossFromLow ? rowPosition : contentQ - rowHeight - rowPosition;
                if (!crossFromLow) q += rowHeight - qSize;
                float p = total > available && end - start > 1 ?
                    (available - pSize) * (i - start) / (end - start - 1) : cursor;
                if (vertical ? !reverse : reverse) p = contentP - p - pSize;
                // Store offsets in physical coordinates, independent of drawing state.
                float px = vertical ? q : p, py = vertical ? p : q;
                float ox = box.full.x + pad, oy = box.full.y + pad;
                if (centerSide == Side.LEFT) ox += box.projectionSize;
                if (centerSide == Side.BOTTOM) oy += box.projectionSize;
                cell.rect.set(ox + px, oy + py, vertical ? qSize : pSize, vertical ? pSize : qSize);
                cursor += pSize + typeGap;
            }
            rowCursor += rowHeight + tierGap;
            start = end;
        }
    }

    private float cellWidth(Bucket bucket) {
        return (26f + bucket.countText.length() * 8f) * scale;
    }

    private float cellPrimary(Bucket bucket) { return vertical ? 28f * scale : cellWidth(bucket); }
    private float cellSecondary(Bucket bucket) { return vertical ? cellWidth(bucket) : 28f * scale; }

    public float projectionPixels(float hitSize, ModSettings settings) {
        return compressed(hitSize, settings) * projectionScale;
    }

    private boolean projectionAlongPrimary() {
        return vertical ? centerSide == Side.TOP || centerSide == Side.BOTTOM : centerSide == Side.LEFT || centerSide == Side.RIGHT;
    }

    private float outerCross(float size) {
        return crossFromLow ? breadth - size : 0;
    }

    private void place(Rect out, float primary, float secondary, float primarySize, float secondarySize) {
        if (vertical) out.set(secondary, reverse ? primary : length - primary - primarySize, secondarySize, primarySize);
        else out.set(reverse ? length - primary - primarySize : primary, secondary, primarySize, secondarySize);
    }

    public static float compressed(float hitSize, ModSettings settings) {
        if (!Float.isFinite(hitSize) || hitSize <= 0) hitSize = settings.baseSize;
        return Math.max(0.01f, settings.baseSize + (hitSize - settings.baseSize) * settings.compression);
    }

    public static Side centerSide(float x, float y, float w, float h, float sw, float sh) {
        float cx = sw / 2, cy = sh / 2;
        float left = distance2(x, y + h / 2, cx, cy), right = distance2(x + w, y + h / 2, cx, cy);
        float bottom = distance2(x + w / 2, y, cx, cy), top = distance2(x + w / 2, y + h, cx, cy);
        float lateral = Math.min(left, right), longitudinal = Math.min(bottom, top);
        if (lateral <= longitudinal) return left <= right ? Side.LEFT : Side.RIGHT;
        return bottom <= top ? Side.BOTTOM : Side.TOP;
    }

    public static Side dock(float x, float y, float w, float h, float sw, float sh, float tolerance) {
        if (x <= tolerance) return Side.LEFT;
        if (sw - x - w <= tolerance) return Side.RIGHT;
        if (y <= tolerance) return Side.BOTTOM;
        if (sh - y - h <= tolerance) return Side.TOP;
        return null;
    }

    private static float distance2(float x, float y, float cx, float cy) {
        return (x - cx) * (x - cx) + (y - cy) * (y - cy);
    }
}
