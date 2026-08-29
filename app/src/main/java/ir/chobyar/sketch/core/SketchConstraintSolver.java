package ir.chobyar.sketch.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Solver boundary for model-owned sketch constraints.
 *
 * The document owns stable ids and history; solver implementations only receive
 * immutable snapshots and return a solved geometry snapshot. This keeps Android
 * Views, object identity and a particular solver library out of persistence and
 * makes a future PlaneGCS/native adapter replaceable without changing the model.
 */
public interface SketchConstraintSolver {
    enum Status { SOLVED, CONFLICT, UNSUPPORTED }

    Result solve(Collection<? extends SketchEntity> entities,
                 Collection<SketchConstraint> constraints);

    final class Result {
        public final Status status;
        public final int iterations;
        public final double maxResidual;
        public final String message;
        private final List<SketchEntity> entities;

        public Result(Status status, int iterations, double maxResidual,
                      String message, Collection<? extends SketchEntity> entities) {
            this.status = status == null ? Status.CONFLICT : status;
            this.iterations = Math.max(0, iterations);
            this.maxResidual = maxResidual;
            this.message = message == null ? "" : message;
            ArrayList<SketchEntity> copy = new ArrayList<>();
            if (entities != null) {
                for (SketchEntity entity : entities) copy.add(entity.copy());
            }
            this.entities = Collections.unmodifiableList(copy);
        }

        public boolean solved() { return status == Status.SOLVED; }

        public List<SketchEntity> entities() {
            ArrayList<SketchEntity> out = new ArrayList<>(entities.size());
            for (SketchEntity entity : entities) out.add(entity.copy());
            return Collections.unmodifiableList(out);
        }
    }
}
