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
 * Geometry and constraints are stored by stable id, selection stores entity ids
 * rather than object identity, and undo/redo snapshots are model-only. The class
 * deliberately has no Android dependency so solver/snapping/persistence can
 * share the same state.
 */
public final class SketchDocument {
    public static final int DEFAULT_MAX_HISTORY = 80;

    private final int maxHistory;
    private final LinkedHashMap<String, SketchEntity> entities = new LinkedHashMap<>();
    private final LinkedHashMap<String, SketchConstraint> constraints = new LinkedHashMap<>();
    private final LinkedHashSet<String> selection = new LinkedHashSet<>();
    private final ArrayDeque<Snapshot> undo = new ArrayDeque<>();
    private final ArrayDeque<Snapshot> redo = new ArrayDeque<>();
    private long revision;

    public SketchDocument() { this(DEFAULT_MAX_HISTORY); }

    public SketchDocument(int maxHistory) {
        if (maxHistory < 1) throw new IllegalArgumentException("maxHistory must be positive");
        this.maxHistory = maxHistory;
    }

    public synchronized long revision() { return revision; }
    public synchronized int size() { return entities.size(); }
    public synchronized int constraintCount() { return constraints.size(); }
    public synchronized boolean isEmpty() { return entities.isEmpty(); }
    public synchronized boolean canUndo() { return !undo.isEmpty(); }
    public synchronized boolean canRedo() { return !redo.isEmpty(); }

    public synchronized boolean contains(String id) { return entities.containsKey(normalizeId(id)); }
    public synchronized boolean containsConstraint(String id) { return constraints.containsKey(normalizeId(id)); }

    public synchronized SketchEntity entity(String id) {
        SketchEntity entity = entities.get(normalizeId(id));
        return entity == null ? null : entity.copy();
    }

    public synchronized SketchConstraint constraint(String id) {
        SketchConstraint constraint = constraints.get(normalizeId(id));
        return constraint == null ? null : constraint.copy();
    }

    public synchronized List<SketchEntity> entities() {
        ArrayList<SketchEntity> out = new ArrayList<>(entities.size());
        for (SketchEntity entity : entities.values()) out.add(entity.copy());
        return Collections.unmodifiableList(out);
    }

    public synchronized List<SketchConstraint> constraints() {
        ArrayList<SketchConstraint> out = new ArrayList<>(constraints.size());
        for (SketchConstraint constraint : constraints.values()) out.add(constraint.copy());
        return Collections.unmodifiableList(out);
    }

    public synchronized List<SketchConstraint> constraintsForEntity(String entityId) {
        String normalized = normalizeId(entityId);
        ArrayList<SketchConstraint> out = new ArrayList<>();
        for (SketchConstraint constraint : constraints.values()) {
            if (constraint.references(normalized)) out.add(constraint.copy());
        }
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
        if (entities.containsKey(entity.id())) throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
        pushUndo();
        entities.put(entity.id(), entity.copy());
        changed();
    }

    /** Adds one entity and any auto-generated constraints as one user transaction/Undo step. */
    public synchronized void addWithConstraints(SketchEntity entity, Collection<SketchConstraint> values) {
        requireValid(entity);
        if (entities.containsKey(entity.id())) throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());

        LinkedHashMap<String, SketchEntity> prospective = copyEntities();
        prospective.put(entity.id(), entity.copy());
        LinkedHashMap<String, SketchConstraint> incoming = validateIncomingConstraints(values, prospective, constraints.keySet());

        pushUndo();
        entities.put(entity.id(), entity.copy());
        constraints.putAll(copyConstraints(incoming));
        changed();
    }

    /**
     * Adds a newly-created entity plus generated constraints and their solved geometry
     * as one fail-closed user transaction. Validation and solving happen entirely on
     * prospective copies; a dangling reference, conflict or unsupported solve leaves
     * geometry, constraints and Undo history untouched.
     */
    public synchronized SketchConstraintSolver.Result addWithConstraintsAndSolve(
            SketchEntity entity, Collection<SketchConstraint> values, SketchConstraintSolver solver) {
        if (solver == null) throw new NullPointerException("solver");
        requireValid(entity);
        if (entities.containsKey(entity.id())) {
            throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
        }

        LinkedHashMap<String, SketchEntity> prospectiveEntities = copyEntities();
        prospectiveEntities.put(entity.id(), entity.copy());
        LinkedHashMap<String, SketchConstraint> incoming =
                validateIncomingConstraints(values, prospectiveEntities, constraints.keySet());
        LinkedHashMap<String, SketchConstraint> prospectiveConstraints = copyConstraints(constraints);
        prospectiveConstraints.putAll(copyConstraints(incoming));

        SketchConstraintSolver.Result solution = prospectiveConstraints.isEmpty()
                ? new SketchConstraintSolver.Result(SketchConstraintSolver.Status.SOLVED,
                        0, 0.0, "", prospectiveEntities.values())
                : solver.solve(prospectiveEntities.values(), prospectiveConstraints.values());
        LinkedHashMap<String, SketchEntity> solved = requireSolvedSnapshot(solution, prospectiveEntities);

        pushUndo();
        entities.clear();
        entities.putAll(solved);
        constraints.putAll(copyConstraints(incoming));
        changed();
        return solution;
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

    public synchronized void addConstraint(SketchConstraint constraint) {
        requireValidConstraint(constraint, entities);
        if (constraints.containsKey(constraint.id)) {
            throw new IllegalArgumentException("Duplicate sketch constraint id: " + constraint.id);
        }
        pushUndo();
        constraints.put(constraint.id, constraint.copy());
        changed();
    }

    public synchronized void addConstraints(Collection<SketchConstraint> values) {
        LinkedHashMap<String, SketchConstraint> incoming = validateIncomingConstraints(values, entities, constraints.keySet());
        if (incoming.isEmpty()) return;
        pushUndo();
        constraints.putAll(copyConstraints(incoming));
        changed();
    }

    /**
     * Adds constraints and commits their solved geometry as one fail-closed user
     * transaction. The solver operates on a prospective copy, so conflict or an
     * unsupported constraint never mutates geometry, constraint state or history.
     */
    public synchronized SketchConstraintSolver.Result addConstraintsAndSolve(
            Collection<SketchConstraint> values, SketchConstraintSolver solver) {
        if (solver == null) throw new NullPointerException("solver");
        LinkedHashMap<String, SketchConstraint> incoming =
                validateIncomingConstraints(values, entities, constraints.keySet());
        if (incoming.isEmpty()) {
            return new SketchConstraintSolver.Result(SketchConstraintSolver.Status.SOLVED,
                    0, 0.0, "", entities.values());
        }

        LinkedHashMap<String, SketchConstraint> prospectiveConstraints = copyConstraints(constraints);
        prospectiveConstraints.putAll(copyConstraints(incoming));
        SketchConstraintSolver.Result solution = solver.solve(entities.values(), prospectiveConstraints.values());
        LinkedHashMap<String, SketchEntity> solved = requireSolvedSnapshot(solution, entities);

        pushUndo();
        entities.clear();
        entities.putAll(solved);
        constraints.putAll(copyConstraints(incoming));
        changed();
        return solution;
    }

    public synchronized void replace(SketchEntity entity) {
        requireValid(entity);
        if (!entities.containsKey(entity.id())) throw new IllegalArgumentException("Sketch entity does not exist: " + entity.id());
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
        removeConstraintsReferencing(normalized);
        changed();
        return true;
    }

    public synchronized boolean removeConstraint(String id) {
        String normalized = normalizeId(id);
        if (!constraints.containsKey(normalized)) return false;
        pushUndo();
        constraints.remove(normalized);
        changed();
        return true;
    }

    public synchronized int removeSelected() {
        if (selection.isEmpty()) return 0;
        pushUndo();
        int removed = 0;
        LinkedHashSet<String> removedIds = new LinkedHashSet<>();
        for (String id : new ArrayList<>(selection)) {
            if (entities.remove(id) != null) {
                removed++;
                removedIds.add(id);
            }
        }
        selection.clear();
        if (removed > 0) {
            removeConstraintsReferencingAny(removedIds);
            changed();
        }
        return removed;
    }

    public synchronized void clear() {
        if (entities.isEmpty() && constraints.isEmpty()) return;
        pushUndo();
        entities.clear();
        constraints.clear();
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

    public synchronized void clearSelection() { selection.clear(); }

    /** Translates selected geometry as one model transaction. Constraint solving follows in K3.6b+. */
    public synchronized boolean translateSelection(double dxMm, double dyMm) {
        if (!SketchGeometry.finite(dxMm) || !SketchGeometry.finite(dyMm)) {
            throw new IllegalArgumentException("Translation must be finite");
        }
        if (selection.isEmpty() || (Math.abs(dxMm) < 1.0e-12 && Math.abs(dyMm) < 1.0e-12)) return false;

        LinkedHashMap<String, SketchEntity> moved = translatedSelectionSnapshot(dxMm, dyMm);
        if (moved.isEmpty()) return false;

        pushUndo();
        entities.clear();
        entities.putAll(moved);
        changed();
        return true;
    }

    /**
     * Moves the selected geometry and solves all model-owned constraints before
     * committing. This is the edit-propagation seam used by K3.6b+ and future
     * mature solver adapters.
     */
    public synchronized SketchConstraintSolver.Result translateSelectionAndSolve(
            double dxMm, double dyMm, SketchConstraintSolver solver) {
        if (solver == null) throw new NullPointerException("solver");
        if (!SketchGeometry.finite(dxMm) || !SketchGeometry.finite(dyMm)) {
            throw new IllegalArgumentException("Translation must be finite");
        }
        if (selection.isEmpty() || (Math.abs(dxMm) < 1.0e-12 && Math.abs(dyMm) < 1.0e-12)) {
            return new SketchConstraintSolver.Result(SketchConstraintSolver.Status.SOLVED,
                    0, 0.0, "", entities.values());
        }

        LinkedHashMap<String, SketchEntity> prospective = translatedSelectionSnapshot(dxMm, dyMm);
        if (prospective.isEmpty()) {
            return new SketchConstraintSolver.Result(SketchConstraintSolver.Status.SOLVED,
                    0, 0.0, "", entities.values());
        }

        SketchConstraintSolver.Result solution = constraints.isEmpty()
                ? new SketchConstraintSolver.Result(SketchConstraintSolver.Status.SOLVED,
                        0, 0.0, "", prospective.values())
                : solver.solve(prospective.values(), constraints.values());
        LinkedHashMap<String, SketchEntity> solved = requireSolvedSnapshot(solution, prospective);

        pushUndo();
        entities.clear();
        entities.putAll(solved);
        changed();
        return solution;
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

    /** Backward-compatible project loading path for geometry-only schemas. */
    public synchronized void restoreExternal(Collection<? extends SketchEntity> values, Collection<String> selectedIds) {
        restoreExternal(values, selectedIds, Collections.emptyList());
    }

    /** Replaces the complete model without creating an undo entry; intended for project loading. */
    public synchronized void restoreExternal(Collection<? extends SketchEntity> values, Collection<String> selectedIds,
                                             Collection<SketchConstraint> constraintValues) {
        LinkedHashMap<String, SketchEntity> next = new LinkedHashMap<>();
        if (values != null) {
            for (SketchEntity entity : values) {
                requireValid(entity);
                if (next.put(entity.id(), entity.copy()) != null) {
                    throw new IllegalArgumentException("Duplicate sketch entity id: " + entity.id());
                }
            }
        }
        LinkedHashMap<String, SketchConstraint> nextConstraints = validateIncomingConstraints(constraintValues, next, Collections.emptySet());

        entities.clear();
        entities.putAll(next);
        constraints.clear();
        constraints.putAll(copyConstraints(nextConstraints));
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

    private LinkedHashMap<String, SketchEntity> translatedSelectionSnapshot(double dxMm, double dyMm) {
        LinkedHashMap<String, SketchEntity> moved = copyEntities();
        boolean any = false;
        for (String id : selection) {
            SketchEntity current = moved.get(id);
            if (current == null) continue;
            SketchEntity candidate = current.translated(dxMm, dyMm);
            requireValid(candidate);
            moved.put(id, candidate);
            any = true;
        }
        return any ? moved : new LinkedHashMap<>();
    }

    private static LinkedHashMap<String, SketchEntity> requireSolvedSnapshot(
            SketchConstraintSolver.Result solution, Map<String, SketchEntity> expectedIds) {
        if (solution == null) throw new IllegalStateException("Sketch constraint solver returned null");
        if (!solution.solved()) {
            throw new IllegalStateException("Sketch constraint solve failed: " + solution.status
                    + (solution.message.isEmpty() ? "" : " — " + solution.message));
        }
        LinkedHashMap<String, SketchEntity> solved = new LinkedHashMap<>();
        for (SketchEntity entity : solution.entities()) {
            requireValid(entity);
            if (!expectedIds.containsKey(entity.id())) {
                throw new IllegalStateException("Solver returned unknown sketch entity: " + entity.id());
            }
            if (solved.put(entity.id(), entity.copy()) != null) {
                throw new IllegalStateException("Solver returned duplicate sketch entity: " + entity.id());
            }
        }
        if (solved.size() != expectedIds.size()) {
            throw new IllegalStateException("Solver changed sketch entity cardinality");
        }
        for (String id : expectedIds.keySet()) {
            if (!solved.containsKey(id)) throw new IllegalStateException("Solver dropped sketch entity: " + id);
        }
        return solved;
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
        return new Snapshot(copyEntities(), copyConstraints(constraints), new LinkedHashSet<>(selection));
    }

    private LinkedHashMap<String, SketchEntity> copyEntities() {
        LinkedHashMap<String, SketchEntity> copied = new LinkedHashMap<>(entities.size());
        for (Map.Entry<String, SketchEntity> entry : entities.entrySet()) copied.put(entry.getKey(), entry.getValue().copy());
        return copied;
    }

    private static LinkedHashMap<String, SketchConstraint> copyConstraints(Map<String, SketchConstraint> source) {
        LinkedHashMap<String, SketchConstraint> copied = new LinkedHashMap<>(source.size());
        for (Map.Entry<String, SketchConstraint> entry : source.entrySet()) copied.put(entry.getKey(), entry.getValue().copy());
        return copied;
    }

    private void restore(Snapshot snapshot) {
        entities.clear();
        for (Map.Entry<String, SketchEntity> entry : snapshot.entities.entrySet()) entities.put(entry.getKey(), entry.getValue().copy());
        constraints.clear();
        constraints.putAll(copyConstraints(snapshot.constraints));
        selection.clear();
        for (String id : snapshot.selection) if (entities.containsKey(id)) selection.add(id);
    }

    private void removeConstraintsReferencing(String entityId) {
        ArrayList<String> remove = new ArrayList<>();
        for (SketchConstraint constraint : constraints.values()) if (constraint.references(entityId)) remove.add(constraint.id);
        for (String id : remove) constraints.remove(id);
    }

    private void removeConstraintsReferencingAny(Set<String> entityIds) {
        if (entityIds == null || entityIds.isEmpty()) return;
        ArrayList<String> remove = new ArrayList<>();
        for (SketchConstraint constraint : constraints.values()) {
            for (String entityId : entityIds) {
                if (constraint.references(entityId)) { remove.add(constraint.id); break; }
            }
        }
        for (String id : remove) constraints.remove(id);
    }

    private static LinkedHashMap<String, SketchConstraint> validateIncomingConstraints(
            Collection<SketchConstraint> values, Map<String, SketchEntity> entityMap, Set<String> existingConstraintIds) {
        LinkedHashMap<String, SketchConstraint> incoming = new LinkedHashMap<>();
        if (values == null) return incoming;
        for (SketchConstraint constraint : values) {
            requireValidConstraint(constraint, entityMap);
            if (existingConstraintIds.contains(constraint.id) || incoming.containsKey(constraint.id)) {
                throw new IllegalArgumentException("Duplicate sketch constraint id: " + constraint.id);
            }
            incoming.put(constraint.id, constraint.copy());
        }
        return incoming;
    }

    private static void requireValidConstraint(SketchConstraint constraint, Map<String, SketchEntity> entityMap) {
        if (constraint == null) throw new NullPointerException("constraint");
        normalizeId(constraint.id);
        for (String entityId : constraint.referencedEntityIds()) {
            if (!entityMap.containsKey(entityId)) {
                throw new IllegalArgumentException("Constraint " + constraint.id + " references missing entity: " + entityId);
            }
        }
    }

    private static void requireValid(SketchEntity entity) {
        if (entity == null) throw new NullPointerException("entity");
        normalizeId(entity.id());
        if (!entity.isValid()) throw new IllegalArgumentException("Invalid sketch geometry: " + entity.id());
    }

    private static String normalizeId(String id) {
        String normalized = id == null ? "" : id.trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("Sketch id is empty");
        return normalized;
    }

    private static final class Snapshot {
        final LinkedHashMap<String, SketchEntity> entities;
        final LinkedHashMap<String, SketchConstraint> constraints;
        final LinkedHashSet<String> selection;

        Snapshot(LinkedHashMap<String, SketchEntity> entities,
                 LinkedHashMap<String, SketchConstraint> constraints,
                 LinkedHashSet<String> selection) {
            this.entities = entities;
            this.constraints = constraints;
            this.selection = selection;
        }
    }
}
