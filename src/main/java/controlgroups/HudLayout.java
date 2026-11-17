package controlgroups;

import arc.graphics.g2d.TextureRegion;
import arc.math.Mathf;
import arc.math.geom.Rect;
import controlgroups.GroupModel.Bucket;
import controlgroups.GroupModel.Group;

/** Geometry works on prepared buckets, never on live unit collections. P = longitudinal, Q = transverse. */
public final class HudLayout {
    public static final float BORDER = 4f;
    private static final float RASTER_MARGIN = 1f;
    public enum Side { LEFT, RIGHT, BOTTOM, TOP }
    @FunctionalInterface public interface CounterMeasure { float width(String text, float fontScale); }

    public static final class Cell {
        public final Rect rect = new Rect();
        public final Rect icon = new Rect();
        public Bucket bucket;
        public float iconSize, fontScale, countWidth, naturalWidth, naturalHeight;
        public boolean showCount;
        public TextureRegion region;
        public float counterRight;
    }

    public static final class Box {
        public final Group group;
        public final Rect full = new Rect(), shown = new Rect(), guide = new Rect();
        public final Rect projection = new Rect(), content = new Rect(), contentFull = new Rect();
        public final Rect projectionFull = new Rect(), projectionUsable = new Rect();
        public Cell[] cells = new Cell[0];
        public int cellCount;
        public float primary, secondary, structurePrimary, reservePrimary, needPrimary, needSecondary;
        public float trackingCross, projectionFitSpan, expansion = 1f;
        public float largestHitSize;
        public double projectionScale, projectionSafety;
        public boolean visible;
        private double projectionExtent;
        private Box(Group group) { this.group = group; }
    }

    public final Box[] boxes = new Box[GroupModel.GROUPS];
    public final Rect zero = new Rect();
    public final int[] order = new int[GroupModel.GROUPS];
    private final int[] active = new int[GroupModel.GROUPS];
    private final float[] needs = new float[GroupModel.GROUPS], floors = new float[GroupModel.GROUPS];
    private final float[] allocated = new float[GroupModel.GROUPS];
    private final CounterMeasure measure;
    public int orderCount, activeCount;
    public float width, height, length, breadth, maxPrimary, scale, zeroSize;
    public float availablePrimary, projectionReserved, structuralBudget, usedPrimary;
    public float positionX, positionY, minX, minY, maxX, maxY, pixelRatio = 1;
    public Side centerSide = Side.RIGHT, projectionSide = Side.RIGHT, dock;
    public long rebuilds;
    public boolean telemetry;
    private boolean vertical, reverse, crossLow, alongLow;
    private float pad, typeGap, tierGap, currentCounterGap;

    public HudLayout(GroupModel model) { this(model, (text, size) -> text.length() * 12f * size); }

    public HudLayout(GroupModel model, CounterMeasure measure) {
        this.measure = measure;
        for (int i = 0; i < boxes.length; i++) boxes[i] = new Box(model.groups[i]);
    }

    public void rebuild(ModSettings s, float sw, float sh, float scale, boolean editor) {
        this.scale = scale; vertical = s.vertical; reverse = s.reverse;
        pad = 4 * scale; typeGap = s.typeGap * scale; tierGap = s.tierGap * scale;
        currentCounterGap = s.counterGap * scale;
        length = Math.max(1, Math.min(s.length * scale, vertical ? sh : sw));
        breadth = Math.max(1, Math.min(s.breadth * scale, vertical ? sw : sh));
        width = vertical ? breadth : length; height = vertical ? length : breadth;
        positionX = s.xFraction * Math.max(0, sw - width);
        positionY = s.yFraction * Math.max(0, sh - height);
        centerSide = centerSide(positionX, positionY, width, height, sw, sh);
        dock = dock(positionX, positionY, width, height, sw, sh, scale);
        crossLow = vertical ? positionX + width / 2 >= sw / 2 : positionY + height / 2 >= sh / 2;
        alongLow = vertical ? positionY + height / 2 >= sh / 2 : positionX + width / 2 >= sw / 2;
        projectionSide = s.internalProjection ?
            (vertical ? (alongLow ? Side.BOTTOM : Side.TOP) : (alongLow ? Side.LEFT : Side.RIGHT)) :
            (vertical ? (crossLow ? Side.LEFT : Side.RIGHT) : (crossLow ? Side.BOTTOM : Side.TOP));
        zeroSize = Math.min(44 * scale, Math.min(length, breadth));
        orderCount = 0;
        if (s.unassigned && s.unassignedFirst) order[orderCount++] = GroupModel.UNASSIGNED;
        for (int i = 0; i < 10; i++) order[orderCount++] = i;
        if (s.unassigned && !s.unassignedFirst) order[orderCount++] = GroupModel.UNASSIGNED;
        activeCount = 0;
        for (Box box : boxes) {
            box.visible = !box.group.empty() && (box.group.index != GroupModel.UNASSIGNED || s.unassigned);
            box.primary = box.reservePrimary = box.trackingCross = box.projectionFitSpan = 0;
            box.projectionScale = box.projectionSafety = 0;
            prepareNatural(box, s);
        }
        for (int i = 0; i < orderCount; i++) if (boxes[order[i]].visible) active[activeCount++] = order[i];
        float zeroGap = Math.min(s.zeroGap * scale, Math.max(0, length - zeroSize) / 4);
        float gap = Math.min(s.groupGap * scale, Math.max(0, length - zeroSize - zeroGap) / Math.max(1, orderCount * 3));
        availablePrimary = Math.max(0, length - zeroSize - zeroGap - Math.max(0, activeCount - 1) * gap);
        maxPrimary = Math.max(0, (length - zeroSize - zeroGap - (orderCount - 1) * gap) / orderCount);
        Box free = boxes[GroupModel.UNASSIGNED];
        boolean residual = free.visible && s.freeSpaceMode == ModSettings.FreeSpaceMode.RESIDUAL;
        float nominal = availablePrimary / Math.max(1, activeCount);
        float minimumSum = 0;
        for (int i = 0; i < activeCount; i++) {
            int id = active[i];
            nominal = Math.min(nominal, fullCap(id, s, residual));
            minimumSum += Math.min(24 * scale, boxes[id].needPrimary);
        }
        // Geometry owns the reserve. No hitSize, compression, occupancy, manual Hmax or resize enters it.
        float reserve = s.projection && s.internalProjection ? Math.min(Math.max(0, breadth - 2 * pad), nominal / 2) : 0;
        reserve = Math.min(reserve, Math.max(0, availablePrimary - minimumSum) / Math.max(1, activeCount));
        if (residual && activeCount > 1) {
            float otherMinimum = minimumSum - Math.min(24 * scale, free.needPrimary);
            float freeMinimum = Math.min(s.freeMinimum * scale, length / 3);
            reserve = Math.min(reserve, Math.max(0, availablePrimary - otherMinimum - freeMinimum) / (activeCount - 1));
        }
        projectionReserved = reserve * activeCount;
        structuralBudget = Math.max(0, availablePrimary - projectionReserved);
        for (int i = 0; i < activeCount; i++) {
            int id = active[i]; Box box = boxes[id];
            box.reservePrimary = reserve;
            needs[id] = box.needPrimary;
            floors[id] = Math.min(24 * scale, needs[id]);
            float cap = fullCap(id, s, residual);
            needs[id] = Math.min(needs[id], Math.max(0, cap - reserve));
            floors[id] = Math.min(floors[id], needs[id]);
            allocated[id] = 0;
        }
        if (residual) {
            int id = GroupModel.UNASSIGNED;
            float totalMinimum = Math.min(s.freeMinimum * scale, length / 3);
            floors[id] = Math.max(floors[id], Math.max(0, totalMinimum - reserve));
            floors[id] = Math.min(floors[id], structuralBudget);
            needs[id] = floors[id];
        }
        allocate(structuralBudget, residual);
        float commonQ = zeroSize;
        float screenQ = vertical ? sw : sh;
        for (int i = 0; i < activeCount; i++) {
            Box box = boxes[active[i]];
            box.structurePrimary = allocated[box.group.index];
            box.primary = box.structurePrimary + reserve;
            float innerP = Math.max(0, box.primary - 2 * pad);
            float structureQ = Math.min(breadth, Math.max(zeroSize, box.needSecondary));
            float trackQ = 0;
            if (s.projection && !s.internalProjection) {
                trackQ = Math.min(innerP, Math.max(0, breadth - 2 * pad) / 2);
                structureQ = Math.max(0, Math.min(structureQ, breadth - trackQ));
                if (s.minTrackingSquare) trackQ = Math.max(trackQ, innerP);
                trackQ = Math.min(trackQ, Math.max(0, screenQ - structureQ));
            } else if (s.projection) {
                structureQ = Math.min(breadth, Math.max(structureQ, reserve + 2 * pad));
            }
            box.trackingCross = trackQ;
            box.secondary = structureQ + trackQ;
            commonQ = Math.max(commonQ, box.secondary);
        }
        place(zero, 0, outerCross(zeroSize), zeroSize, zeroSize);
        float cursor = zeroSize + zeroGap;
        for (int i = 0; i < orderCount; i++) {
            Box box = boxes[order[i]];
            place(box.guide, zeroSize + zeroGap + i * (maxPrimary + gap), 0, maxPrimary, breadth);
            if (!box.visible) {
                box.full.set(0, 0, 0, 0); box.shown.set(box.full);
                box.projectionFull.set(box.full); box.projection.set(box.full); box.projectionUsable.set(box.full);
                continue;
            }
            if (s.align) box.secondary = commonQ;
            place(box.full, cursor, outerCross(box.secondary), box.primary, box.secondary);
            cursor += box.primary + gap;
            setAreas(box.full, box.contentFull, box.projectionFull, box, s);
            usableProjection(box.full, box.projectionFull, box.projectionUsable);
            box.projectionFitSpan = Math.min(box.projectionUsable.width, box.projectionUsable.height);
            // Safety is relative to this group's automatic maximum; a shared manual denominator cannot cancel itself.
            double automatic = compressed(box.largestHitSize, s);
            double diameter = Math.max(1, 2 * box.projectionExtent / automatic);
            box.projectionSafety = diameter / (1 - 2 * UnitProjection.DRIFT_FRACTION);
            float largest = s.manualLargest ? s.manualLargestSize : box.largestHitSize;
            box.projectionScale = s.adapt ? box.projectionFitSpan * (double)ModSettings.finite(s.occupancy, 1, 0, 3) /
                box.projectionSafety / compressed(largest, s) : scale;
            if (editor) box.expansion = 1;
            prepareCells(box, s);
            animate(box, s, editor, false, 0);
        }
        usedPrimary = activeCount == 0 ? 0 : cursor - zeroSize - zeroGap - activeCount * gap;
        // The editable frame retains its configured size; only square tracking may extend beyond it.
        minX = 0; minY = 0; maxX = width; maxY = height;
        for (Box box : boxes) if (box.visible) {
            minX = Math.min(minX, box.full.x); minY = Math.min(minY, box.full.y);
            maxX = Math.max(maxX, box.full.x + box.full.width); maxY = Math.max(maxY, box.full.y + box.full.height);
        }
        positionX = Mathf.clamp(positionX, -minX, Math.max(-minX, sw - maxX));
        positionY = Mathf.clamp(positionY, -minY, Math.max(-minY, sh - maxY));
        if (telemetry) rebuilds++;
    }

    private float fullCap(int id, ModSettings s, boolean residual) {
        if (id == GroupModel.UNASSIGNED) {
            if (residual) return Float.MAX_VALUE;
            if (s.freeSpaceMode == ModSettings.FreeSpaceMode.LIMITED) return availablePrimary / orderCount;
        }
        return switch (s.spaceMode) {
            case LIMITED -> availablePrimary / orderCount;
            case EQUAL -> availablePrimary / Math.max(1, activeCount);
            case PROPORTIONAL -> Float.MAX_VALUE;
        };
    }

    /** Bounded water filling: at most 11 groups, no allocation, protected minima before proportional shortage. */
    private void allocate(float budget, boolean residual) {
        float sumFloors = 0;
        for (int i = 0; i < activeCount; i++) sumFloors += floors[active[i]];
        float minimumScale = sumFloors > budget && sumFloors > 0 ? budget / sumFloors : 1;
        float remaining = budget;
        for (int i = 0; i < activeCount; i++) {
            int id = active[i]; allocated[id] = floors[id] * minimumScale; remaining -= allocated[id];
        }
        if (minimumScale < 1) {
            if (residual) {
                float freeFloor = Math.min(budget, floors[GroupModel.UNASSIGNED]);
                float otherFloors = sumFloors - floors[GroupModel.UNASSIGNED];
                float otherScale = otherFloors > 0 ? Math.max(0, budget - freeFloor) / otherFloors : 0;
                for (int i = 0; i < activeCount; i++) {
                    int id = active[i];
                    allocated[id] = id == GroupModel.UNASSIGNED ? freeFloor : floors[id] * otherScale;
                }
            }
            return;
        }
        int fixed = residual ? 1 << GroupModel.UNASSIGNED : 0;
        remaining = budget - (residual ? allocated[GroupModel.UNASSIGNED] : 0);
        for (int pass = 0; pass <= activeCount; pass++) {
            float weight = 0;
            for (int i = 0; i < activeCount; i++) if ((fixed & (1 << active[i])) == 0) weight += needs[active[i]];
            if (weight <= 0.001f) break;
            float ratio = Math.min(1, remaining / weight);
            boolean clamped = false;
            for (int i = 0; i < activeCount; i++) {
                int id = active[i];
                if ((fixed & (1 << id)) != 0) continue;
                if (ratio * needs[id] < floors[id]) {
                    allocated[id] = floors[id]; remaining -= allocated[id]; fixed |= 1 << id; clamped = true;
                }
            }
            if (!clamped) {
                for (int i = 0; i < activeCount; i++) {
                    int id = active[i];
                    if ((fixed & (1 << id)) == 0) allocated[id] = needs[id] * ratio;
                }
                break;
            }
        }
        if (residual) {
            float consumed = 0;
            for (int i = 0; i < activeCount; i++) consumed += allocated[active[i]];
            allocated[GroupModel.UNASSIGNED] += Math.max(0, budget - consumed);
        }
    }

    private void prepareNatural(Box box, ModSettings s) {
        int count = box.group.types.size;
        if (box.cells.length < count) {
            Cell[] next = new Cell[count];
            System.arraycopy(box.cells, 0, next, 0, box.cells.length);
            for (int i = box.cells.length; i < count; i++) next[i] = new Cell();
            box.cells = next;
        }
        box.cellCount = count;
        box.projectionExtent = 0;
        box.largestHitSize = 0;
        float p = 0, q = 0, rowP = 0, rowQ = 0; int tier = -1;
        for (int i = 0; i < count; i++) {
            Cell cell = box.cells[i]; cell.bucket = box.group.types.get(i);
            var profile = cell.bucket.profile;
            double hit = referenceHit(profile.unit.hitSize, s.baseSize);
            box.largestHitSize = Math.max(box.largestHitSize, (float)hit);
            double radius = s.advanced ? profile.advancedProjectionRadius : profile.projectionRadius;
            // The normalized fallback and status strokes also cover types with invalid hitSize.
            double statusRadius = s.advanced && profile.unit.drawShields ? UnitProjection.SHIELD_RADIUS : UnitProjection.BURNING_RADIUS;
            double relativeRadius = Math.max(Math.sqrt(.5), Math.max(radius / hit, statusRadius + UnitProjection.STATUS_STROKE_RADIUS / hit));
            box.projectionExtent = Math.max(box.projectionExtent, relativeRadius * compressed(profile.unit.hitSize, s));
            cell.iconSize = iconPixels(cell.bucket.profile.unit.hitSize, s) * scale;
            cell.fontScale = 0.65f * cell.iconSize / 24f;
            cell.showCount = !(s.hideSingles && cell.bucket.count == 1);
            cell.countWidth = cell.showCount ? measure.width(cell.bucket.countText, cell.fontScale) : 0;
            cell.naturalWidth = cell.iconSize + (cell.showCount ? cell.countWidth + s.counterGap * scale : 0);
            cell.naturalHeight = cell.iconSize + 4 * scale;
            if (cell.bucket.profile.tier != tier) {
                p = Math.max(p, Math.max(0, rowP - typeGap));
                q += rowQ + (tier == -1 ? 0 : tierGap);
                tier = cell.bucket.profile.tier; rowP = rowQ = 0;
            }
            rowP += (vertical ? cell.naturalHeight : cell.naturalWidth) + typeGap;
            rowQ = Math.max(rowQ, vertical ? cell.naturalWidth : cell.naturalHeight);
        }
        box.needPrimary = Math.max(0, Math.max(p, Math.max(0, rowP - typeGap)) + 2 * pad);
        box.needSecondary = Math.max(0, q + rowQ + 2 * pad);
    }

    private void setAreas(Rect body, Rect content, Rect tracking, Box box, ModSettings s) {
        float inset = Math.min(pad, Math.min(body.width, body.height) / 2);
        content.set(body.x + inset, body.y + inset, Math.max(0, body.width - 2 * inset), Math.max(0, body.height - 2 * inset));
        tracking.set(0, 0, 0, 0);
        float reserve = !s.projection ? 0 : s.internalProjection ? box.reservePrimary : box.trackingCross;
        if (reserve <= 0) return;
        switch (projectionSide) {
            case LEFT -> {
                float size = Math.min(reserve, content.width);
                tracking.set(content.x, content.y, size, content.height); content.x += size; content.width -= size;
            }
            case RIGHT -> {
                float size = Math.min(reserve, content.width);
                tracking.set(content.x + content.width - size, content.y, size, content.height); content.width -= size;
            }
            case BOTTOM -> {
                float size = Math.min(reserve, content.height);
                tracking.set(content.x, content.y, content.width, size); content.y += size; content.height -= size;
            }
            case TOP -> {
                float size = Math.min(reserve, content.height);
                tracking.set(content.x, content.y + content.height - size, content.width, size); content.height -= size;
            }
        }
    }

    public void animate(Box box, ModSettings s, boolean editor, boolean hovered, float delta) {
        boolean collapse = s.collapse && dock != null && !editor;
        float target = !collapse || (s.hover && hovered) ? 1 : 0;
        box.expansion = Mathf.approach(box.expansion, target, delta / UpdatePolicy.SLIDE_SECONDS);
        box.shown.set(box.full);
        float fullQ = vertical ? box.full.width : box.full.height;
        float small = Math.min(fullQ, s.projection ?
            (s.internalProjection ? Math.max(box.reservePrimary, 12 * scale) : box.trackingCross) + 2 * pad : 12 * scale);
        float q = Mathf.lerp(small, fullQ, box.expansion);
        if (vertical) { box.shown.width = q; if (crossLow) box.shown.x += fullQ - q; }
        else { box.shown.height = q; if (crossLow) box.shown.y += fullQ - q; }
        setAreas(box.shown, box.content, box.projection, box, s);
        usableProjection(box.shown, box.projection, box.projectionUsable);
    }

    /** Exclude the real panel border and the divider once, then leave one raster pixel for edge filtering/strokes. */
    private void usableProjection(Rect body, Rect tracking, Rect out) {
        float border = Math.min(BORDER, Math.min(body.width, body.height) / 2);
        float divider = Math.min(BORDER, Math.min(tracking.width, tracking.height));
        float left = Math.max(tracking.x, body.x + border), bottom = Math.max(tracking.y, body.y + border);
        float right = Math.min(tracking.x + tracking.width, body.x + body.width - border);
        float top = Math.min(tracking.y + tracking.height, body.y + body.height - border);
        switch (projectionSide) {
            case LEFT -> right = Math.min(right, tracking.x + tracking.width - divider);
            case RIGHT -> left = Math.max(left, tracking.x + divider);
            case BOTTOM -> top = Math.min(top, tracking.y + tracking.height - divider);
            case TOP -> bottom = Math.max(bottom, tracking.y + divider);
        }
        float w = Math.max(0, right - left), h = Math.max(0, top - bottom);
        float ix = Math.min(RASTER_MARGIN, w / 2), iy = Math.min(RASTER_MARGIN, h / 2);
        out.set(left + ix, bottom + iy, w - 2 * ix, h - 2 * iy);
    }

    private void prepareCells(Box box, ModSettings settings) {
        Rect area = box.contentFull;
        float pSpace = vertical ? area.height : area.width, qSpace = vertical ? area.width : area.height;
        int rows = 0;
        float totalQ = 0;
        for (int start = 0; start < box.cellCount;) {
            int end = tierEnd(box, start); float q = 0;
            for (int i = start; i < end; i++) q = Math.max(q, cellQ(box.cells[i]));
            totalQ += Math.min(q, qSpace) + (rows == 0 ? 0 : tierGap); rows++; start = end;
        }
        float rowCursor = 0; int row = 0;
        for (int start = 0; start < box.cellCount; row++) {
            int end = tierEnd(box, start); float totalP = 0, rowQ = 0;
            for (int i = start; i < end; i++) {
                totalP += Math.min(cellP(box.cells[i]), pSpace) + (i == start ? 0 : typeGap);
                rowQ = Math.max(rowQ, cellQ(box.cells[i]));
            }
            rowQ = Math.min(rowQ, qSpace);
            float cursor = Math.max(0, (pSpace - totalP) / 2);
            float rowPosition = totalQ > qSpace && rows > 1 ? (qSpace - rowQ) * row / (rows - 1) : rowCursor;
            for (int i = start; i < end; i++) {
                Cell cell = box.cells[i]; float pSize = Math.min(pSpace, cellP(cell)), qSize = Math.min(rowQ, cellQ(cell));
                float p = totalP > pSpace && end - start > 1 ? (pSpace - pSize) * (i - start) / (end - start - 1) : cursor;
                if (vertical ? !reverse : reverse) p = pSpace - p - pSize;
                float q = crossLow ? rowPosition : qSpace - rowQ - rowPosition + rowQ - qSize;
                cell.rect.set(area.x + (vertical ? q : p), area.y + (vertical ? p : q),
                    vertical ? qSize : pSize, vertical ? pSize : qSize);
                float iconSize = Math.min(cell.iconSize, Math.min(cell.rect.width, cell.rect.height));
                cell.region = unitIcon(cell.bucket.profile.uiIcon, cell.bucket.profile.fullIcon,
                    iconSize * pixelRatio, settings.fullIcons);
                float ratio = cell.region == null ? 1 : (float)Math.max(1, cell.region.width) / Math.max(1, cell.region.height);
                float iw = ratio >= 1 ? iconSize : iconSize * ratio, ih = ratio >= 1 ? iconSize / ratio : iconSize;
                cell.icon.set(cell.rect.x + cell.rect.width - iconSize / 2 - iw / 2,
                    cell.rect.y + (cell.rect.height - ih) / 2, iw, ih);
                cell.counterRight = cell.icon.x - currentCounterGap;
                cursor += pSize + typeGap;
            }
            rowCursor += rowQ + tierGap; start = end;
        }
    }

    private int tierEnd(Box box, int start) {
        int end = start + 1;
        while (end < box.cellCount && box.cells[end].bucket.profile.tier == box.cells[start].bucket.profile.tier) end++;
        return end;
    }
    private float cellP(Cell cell) { return vertical ? cell.naturalHeight : cell.naturalWidth; }
    private float cellQ(Cell cell) { return vertical ? cell.naturalWidth : cell.naturalHeight; }
    private float outerCross(float size) { return crossLow ? breadth - size : 0; }
    private void place(Rect out, float p, float q, float dp, float dq) {
        if (vertical) out.set(q, reverse ? p : length - p - dp, dq, dp);
        else out.set(reverse ? length - p - dp : p, q, dp, dq);
    }

    public float projectionPixels(Box box, float hitSize, ModSettings settings) {
        return finitePixels(compressed(hitSize, settings) * box.projectionScale);
    }
    public static float compressed(float hitSize, ModSettings s) {
        float base = referenceHit(s.baseSize, 8);
        double hit = referenceHit(hitSize, base);
        double k = ModSettings.finite(s.compression, .2f, 0, 1);
        return (float)(base * (1 - k) + hit * k);
    }
    public static float referenceHit(float hitSize, float base) {
        return Float.isFinite(hitSize) && hitSize > 0 ? hitSize : Float.isFinite(base) && base > 0 ? base : 8;
    }
    public static float finitePixels(double pixels) { return pixels > 0 ? (float)Math.min(Float.MAX_VALUE, pixels) : 0; }
    public static float iconPixels(float hitSize, ModSettings s) {
        float size = !Float.isFinite(hitSize) || hitSize <= 0 ? s.baseSize : hitSize;
        double result = s.iconSize * (s.iconInfluence ? (s.baseSize + ((double)size - s.baseSize) * s.iconCoefficient) / s.baseSize : 1);
        // Extreme modded hit sizes must not overflow the sums/coordinates of UI cells.
        return (float)Math.min(1_000_000, result);
    }
    public static TextureRegion unitIcon(TextureRegion ui, TextureRegion full, float pixels, boolean enabled) {
        if (enabled && ui != null && full != null && full.texture != null && full.found() &&
            pixels > Math.max(ui.width, ui.height) && Math.max(full.width, full.height) > Math.max(ui.width, ui.height))
            return full;
        return ui;
    }
    public static Side centerSide(float x, float y, float w, float h, float sw, float sh) {
        float cx = sw / 2, cy = sh / 2;
        float left = distance2(x, y + h / 2, cx, cy), right = distance2(x + w, y + h / 2, cx, cy);
        float bottom = distance2(x + w / 2, y, cx, cy), top = distance2(x + w / 2, y + h, cx, cy);
        return Math.min(left, right) <= Math.min(bottom, top) ? (left <= right ? Side.LEFT : Side.RIGHT) :
            (bottom <= top ? Side.BOTTOM : Side.TOP);
    }
    public static Side dock(float x, float y, float w, float h, float sw, float sh, float tolerance) {
        if (x <= tolerance) return Side.LEFT; if (sw - x - w <= tolerance) return Side.RIGHT;
        if (y <= tolerance) return Side.BOTTOM; if (sh - y - h <= tolerance) return Side.TOP; return null;
    }
    private static float distance2(float x, float y, float cx, float cy) { return (x - cx) * (x - cx) + (y - cy) * (y - cy); }
}
