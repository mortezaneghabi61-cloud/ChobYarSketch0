package ir.chobyar.sketch.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Single owner for sketch model state.
 *
 * Geometry is stored by stable id, selection stores ids rather than object
 * identity, and undo/redo snapshots are model-only. The class deliberately has
 * no Android dependency so solver/snapping/persistence can share the same state.
 */
public final class SketchDocument {
    public static final int DEFAULT_MAX_HISTORY = 80;

    private final int maxHistory;
    private final LinkedHashMap<String, SketchEntity> entities = new LinkedHashMap<>();
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private long revision;

    public SketchDocument() {
        this(DEFAULT_MAX_HISTORY);
    }

    public SketchDocument(int maxHistory) {
        if (maxHistory < 1) throw new IllegalArgumentException("maxHistory must be positive");
        this.maxHistory = maxHistory;
    }

    public synchronized long revision() { return revision; }
    public synchronized int size() { return entities.size(); }
    public synchronized boolean isEmpty() { return entities.isEmpty(); }
    public synchronized boolean canUndo() { return !undo.isEmpty(); }
    public synchronized boolean canRedo() { return !redo.isEmpty(); }

    public synchronized boolean contains(String id) {
        return entities.containsKey(normalizeId(id));
    }

    public synchronized SketchEntity entity(String id) {
        SketchEntity entity = entities.get(normalizeId(id));
        return entity == null ? null : entity.copy();
    }

    public synchronized List<SketchEntity> entities() {
        ArrayList<SketchEntity> out = new ArrayList<>(entities.size());
        for (SketchEntity entity : entities.values()) out.add(entity.copy());
        return Collections.unmodifiableList(out);
    }

    public synchronized Set<String> selectionIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(selection));
    }

    public synchronized List<SketchEntity> selectedEntities() {
        ArrayList<SketchEntity> out = new ArrayList<>(selection.size());
        for (String id : selection) {
            SketchEntity entity = entities.get(id);
            if (entity != null) out.add(entity.copy());
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized void add(SketchEntity entity) {
        requireValid(entity);
        if (entities.containsKey(entity.id())) {
            throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
        }
        pushUndo();
        entities.put(entity.id(), entity.copy());
        changed();
    }

    public synchronized void addAll(Collection<? extends SketchEntity> values) {
        if (values == null || values.isEmpty()) return;
        LinkedHashMap<String, SketchEntity> incoming = new LinkedHashMap<>();
        for (SketchEntity entity : values) {
            requireValid(entity);
            if (entities.containsKey(entity.id()) || incoming.containsKey(entity.id())) {
                throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
            }
            incoming.put(entity.id(), entity.copy());
        }
        pushUndo();
        entities.putAll(incoming);
        changed();
    }

    public synchronized void replace(SketchEntity entity) {
        requireValid(entity);
        if (!entities.containsKey(entity.id())) {
            throw new IllegalArgumentException("Sketch entity does not exist: " + entity.id());
        }
        pushUndo();
        entities.put(entity.id(), entity.copy());
        changed();
    }

    public synchronized boolean remove(String id) {
        String normalized = normalizeId(id);
        if (!entities.containsKey(normalized)) return false;
        pushUndo();
        entities.remove(normalized);
        selection.remove(normalized);
        changed();
        return true;
    }

    public synchronized int removeSelected() {
        if (selection.isEmpty()) return 0;
        pushUndo();
        int removed = 0;
        for (String id : new ArrayList<>(selection)) {
            if (entities.remove(id) != null) removed++;
        }
        selection.clear();
        if (removed > 0) changed();
        return removed;
    }

    public synchronized void clear() {
        if (entities.isEmpty()) return;
        pushUndo();
        entities.clear();
        selection.clear();
        changed();
    }

    /** Selection changes are UI state and intentionally do not create undo entries. */
    public synchronized void selectOnly(String id) {
        selection.clear();
        if (id != null) {
            String normalized = normalizeId(id);
            if (entities.containsKey(normalized)) selection.add(normalized);
        }
    }

    public synchronized void setSelection(Collection<String> ids) {
        selection.clear();
        if (ids == null) return;
        for (String id : ids) {
            String normalized = normalizeId(id);
            if (entities.containsKey(normalized)) selection.add(normalized);
        }
    }

    public synchronized void clearSelection() {
        selection.clear();
    }

    /**
     * Translates all selected geometry as one transaction. Every candidate copy
     * is validated before undo/redo or live model state changes, so a failed
     * numeric operation cannot corrupt history or partially move a selection.
     */
    public synchronized boolean translateSelection(double dxMm, double dyMm) {
        if (!SketchGeometry.finite(dxMm) || !SketchGeometry.finite(dyMm)) {
            throw new IllegalArgumentException("Translation must be finite");
        }
        if (selection.isEmpty() || (Math.abs(dxMm) < 1.0e-12 && Math.abs(dyMm) < 1.0e-12)) return false;

        LinkedHashMap<String, SketchEntity> moved = new LinkedHashMap<>();
        for (String id : selection) {
            SketchEntity current = entities.get(id);
            if (current == null) continue;
            SketchEntity candidate = current.translated(dxMm, dyMm);
            requireValid(candidate);
            moved.put(id, candidate);
        }
        if (moved.isEmpty()) return false;

        pushUndo();
        for (Map.Entry<String, SketchEntity> entry : moved.entrySet()) {
            entities.put(entry.getKey(), entry.getValue());
        }
        changed();
        return true;
    }

    public synchronized boolean undo() {
        if (undo.isEmpty()) return false;
        redo.addLast(snapshot());
        trim(redo);
        restore(undo.removeLast());
        revision++;
        return true;
    }

    public synchronized boolean redo() {
        if (redo.isEmpty()) return false;
        undo.addLast(snapshot());
        trim(undo);
        restore(redo.removeLast());
        revision++;
        return true;
    }

    /** Replaces the complete model without creating an undo entry; intended for project loading. */
    public synchronized void restoreExternal(Collection<? extends SketchEntity> values, Collection<String> selectedIds) {
        LinkedHashMap<String, SketchEntity> next = new LinkedHashMap<>();
        if (values != null) {
            for (SketchEntity entity : values) {
                requireValid(entity);
                if (next.put(entity.id(), entity.copy()) != null) {
                    throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
                }
            }
        }
        entities.clear();
        entities.putAll(next);
        selection.clear();
        if (selectedIds != null) {
            for (String id : selectedIds) {
                String normalized = normalizeId(id);
                if (entities.containsKey(normalized)) selection.add(normalized);
            }
        }
        undo.clear();
        redo.clear();
        revision++;
    }

    private void pushUndo() {
        undo.addLast(snapshot());
        trim(undo);
        redo.clear();
    }

    private void changed() {
        redo.clear();
        revision++;
    }

    private void trim(ArrayDeque<Snapshot> history) {
        while (history.size() > maxHistory) history.removeFirst();
    }

    private Snapshot snapshot() {
        LinkedHashMap<String, SketchEntity> copied = new LinkedHashMap<>(entities.size());
        for (Map.Entry<String, SketchEntity> entry : entities.entrySet()) {
            copied.put(entry.getKey(), entry.getValue().copy());
        }
        return new Snapshot(copied, new LinkedHashSet<>(selection));
    }

    private void restore(Snapshot snapshot) {
        entities.clear();
        for (Map.Entry<String, SketchEntity> entry : snapshot.entities.entrySet()) {
            entities.put(entry.getKey(), entry.getValue().copy());
        }
        selection.clear();
        for (String id : snapshot.selection) if (entities.containsKey(id)) selection.add(id);
    }

    private static void requireValid(SketchEntity entity) {
        if (entity == null) throw new NullPointerException("entity");
        normalizeId(entity.id());
        if (!entity.isValid()) throw new IllegalArgumentException("Invalid sketch geometry: " + entity.id());
    }

    private static String normalizeId(String id) {
        String normalized = id == null ? "" : id.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Sketch entity id is empty");
        return normalized;
    }

    private static final class Snapshot {
        final LinkedHashMap<String, SketchEntity> entities;
        final LinkedHashSet<String> selection;

        Snapshot(LinkedHashMap<String, SketchEntity> entities, LinkedHashSet<String> selection) {
            this.entities = entities;
            this.selection = selection;
        }
    }
}
