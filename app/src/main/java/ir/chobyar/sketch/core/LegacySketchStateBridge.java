package ir.chobyar.sketch.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Migration seam from the persisted legacy canvas representation into
 * {@link SketchDocument}. Geometry keeps the stable-id schema-v2 contract and
 * K3.6c adds an optional modelConstraints array. Older geometry-only projects
 * remain valid and restore with no model-owned constraints.
 */
public final class LegacySketchStateBridge {
    private static final double EPS = 1.0e-6;
    private static final int MODEL_CONSTRAINT_SCHEMA_VERSION = 1;

    private LegacySketchStateBridge() {}

    public static final class Result {
        public final List<SketchEntity> entities;
        public final List<SketchConstraint> constraints;
        public final Set<String> ignoredAnnotationIds;

        Result(List<SketchEntity> entities, List<SketchConstraint> constraints,
               Set<String> ignoredAnnotationIds) {
            this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
            this.constraints = Collections.unmodifiableList(new ArrayList<>(constraints));
            this.ignoredAnnotationIds = Collections.unmodifiableSet(new LinkedHashSet<>(ignoredAnnotationIds));
        }
    }

    public static Result parse(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            int schema = root.optInt("schemaVersion", -1);
            if (schema != 2) throw new IllegalArgumentException("Sketch bridge requires stable-id schema v2");
            JSONArray rows = root.getJSONArray("entities");
            ArrayList<SketchEntity> modeled = new ArrayList<>();
            LinkedHashSet<String> ignored = new LinkedHashSet<>();
            LinkedHashSet<String> ids = new LinkedHashSet<>();
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                String id = normalizedId(row.optString("id", ""));
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate legacy sketch id: " + id);
                String type = row.getString("type").trim().toUpperCase(java.util.Locale.US);
                SketchEntity entity = toEntity(type, id, row);
                if (entity == null) ignored.add(id);
                else {
                    if (!entity.isValid()) throw new IllegalArgumentException("Invalid mirrored geometry: " + id);
                    modeled.add(entity);
                }
            }

            ArrayList<SketchConstraint> constraints = new ArrayList<>();
            LinkedHashSet<String> constraintIds = new LinkedHashSet<>();
            JSONArray constraintRows = root.optJSONArray("modelConstraints");
            if (constraintRows != null || root.has("modelConstraintSchemaVersion")) {
                int relationshipSchema = root.optInt("modelConstraintSchemaVersion", -1);
                if (relationshipSchema != MODEL_CONSTRAINT_SCHEMA_VERSION) {
                    throw new IllegalArgumentException("Unsupported model constraint schema version: " + relationshipSchema);
                }
            }
            if (constraintRows != null) {
                for (int i = 0; i < constraintRows.length(); i++) {
                    JSONObject row = constraintRows.getJSONObject(i);
                    String id = normalizedId(row.optString("id", ""));
                    if (!constraintIds.add(id)) {
                        throw new IllegalArgumentException("Duplicate sketch constraint id: " + id);
                    }
                    SketchConstraint.Kind kind = SketchConstraint.Kind.valueOf(
                            row.getString("kind").trim().toUpperCase(java.util.Locale.US));
                    String primary = normalizedId(row.getString("primaryEntityId"));
                    String secondary = normalizedOptionalId(row.optString("secondaryEntityId", null));
                    int primaryPoint = row.optInt("primaryPointIndex", -1);
                    int secondaryPoint = row.optInt("secondaryPointIndex", -1);
                    double value = row.has("value") ? row.getDouble("value") : Double.NaN;
                    boolean driving = row.optBoolean("driving", true);
                    constraints.add(new SketchConstraint(id, kind, primary, primaryPoint,
                            secondary, secondaryPoint, value, driving));
                }
            }
            return new Result(modeled, constraints, ignored);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot bridge sketch state", e);
        }
    }

    public static void restoreDocument(SketchDocument document, String raw) {
        if (document == null) throw new NullPointerException("document");
        Result result = parse(raw);
        document.restoreExternal(result.entities, Collections.emptySet(), result.constraints);
    }

    /** Restore modeled geometry except one in-flight candidate id, retaining all valid constraints. */
    public static void restoreDocumentExcluding(SketchDocument document, String raw, String excludedId) {
        if (document == null) throw new NullPointerException("document");
        String excluded = normalizedId(excludedId);
        Result result = parse(raw);
        ArrayList<SketchEntity> kept = new ArrayList<>();
        LinkedHashSet<String> keptIds = new LinkedHashSet<>();
        for (SketchEntity entity : result.entities) {
            if (excluded.equals(entity.id())) continue;
            kept.add(entity);
            keptIds.add(entity.id());
        }
        ArrayList<SketchConstraint> keptConstraints = new ArrayList<>();
        for (SketchConstraint constraint : result.constraints) {
            if (constraint.references(excluded)) continue;
            if (!keptIds.containsAll(constraint.referencedEntityIds())) continue;
            keptConstraints.add(constraint);
        }
        document.restoreExternal(kept, Collections.emptySet(), keptConstraints);
    }

    /** Returns one modeled legacy entity by stable id, or null for annotations/missing ids. */
    public static SketchEntity entity(String raw, String stableId) {
        String id = normalizedId(stableId);
        for (SketchEntity entity : parse(raw).entities) if (id.equals(entity.id())) return entity;
        return null;
    }

    public static boolean hasParity(SketchDocument document, String raw) {
        if (document == null) return false;
        Result legacy = parse(raw);
        if (document.size() != legacy.entities.size()) return false;
        if (document.constraintCount() != legacy.constraints.size()) return false;
        for (SketchEntity expected : legacy.entities) {
            SketchEntity actual = document.entity(expected.id());
            if (actual == null || !sameGeometry(expected, actual)) return false;
        }
        for (SketchConstraint expected : legacy.constraints) {
            SketchConstraint actual = document.constraint(expected.id);
            if (actual == null || !sameConstraint(expected, actual)) return false;
        }
        return true;
    }

    /** Compare existing model state while ignoring one just-created legacy candidate. */
    public static boolean hasParityExcluding(SketchDocument document, String raw, String excludedId) {
        if (document == null) return false;
        String excluded = normalizedId(excludedId);
        Result legacy = parse(raw);
        int expectedSize = 0;
        for (SketchEntity expected : legacy.entities) {
            if (excluded.equals(expected.id())) continue;
            expectedSize++;
            SketchEntity actual = document.entity(expected.id());
            if (actual == null || !sameGeometry(expected, actual)) return false;
        }
        if (document.size() != expectedSize) return false;
        int expectedConstraints = 0;
        for (SketchConstraint expected : legacy.constraints) {
            if (expected.references(excluded)) continue;
            expectedConstraints++;
            SketchConstraint actual = document.constraint(expected.id);
            if (actual == null || !sameConstraint(expected, actual)) return false;
        }
        return document.constraintCount() == expectedConstraints;
    }

    private static SketchEntity toEntity(String type, String id, JSONObject row) throws Exception {
        if ("POINT".equals(type)) {
            return new SketchPoint(id, point(row.getDouble("x"), row.getDouble("y")));
        }
        if ("LINE".equals(type)) {
            return new SketchGeometry.Line(id,
                    point(row.getDouble("x1"), row.getDouble("y1")),
                    point(row.getDouble("x2"), row.getDouble("y2")));
        }
        if ("CIRCLE".equals(type)) {
            return new SketchGeometry.Circle(id,
                    point(row.getDouble("x"), row.getDouble("y")), row.getDouble("r"));
        }
        if ("ARC".equals(type)) {
            return new SketchGeometry.Arc(id,
                    point(row.getDouble("x"), row.getDouble("y")), row.getDouble("r"),
                    row.getDouble("start"), row.getDouble("sweep"));
        }
        if ("RECT".equals(type)) {
            List<SketchGeometry.Point> p = points(row.getJSONArray("points"));
            if (p.size() != 4) throw new IllegalArgumentException("Rectangle requires four points");
            SketchGeometry.Point o = p.get(0);
            return new SketchGeometry.Rect(id, o,
                    new SketchGeometry.Vector(p.get(1).xMm - o.xMm, p.get(1).yMm - o.yMm),
                    new SketchGeometry.Vector(p.get(3).xMm - o.xMm, p.get(3).yMm - o.yMm));
        }
        if ("POLYGON".equals(type)) return new SketchPolygon(id, points(row.getJSONArray("points")));
        if ("POLYLINE".equals(type)) {
            return new SketchGeometry.Polyline(id, points(row.getJSONArray("points")), row.optBoolean("closed", false));
        }
        if ("MEASURE".equals(type) || "ANGLE".equals(type) || "GUIDE".equals(type)) return null;
        throw new IllegalArgumentException("Unsupported legacy sketch type: " + type);
    }

    private static List<SketchGeometry.Point> points(JSONArray array) throws Exception {
        ArrayList<SketchGeometry.Point> out = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONArray p = array.getJSONArray(i);
            if (p.length() != 2) throw new IllegalArgumentException("Invalid point tuple");
            out.add(point(p.getDouble(0), p.getDouble(1)));
        }
        return out;
    }

    private static SketchGeometry.Point point(double x, double y) {
        if (!finite(x) || !finite(y)) throw new IllegalArgumentException("Non-finite sketch coordinate");
        return new SketchGeometry.Point(x, y);
    }

    private static String normalizedId(String id) {
        String value = id == null ? "" : id.trim();
        if (value.isEmpty() || value.length() > 128) throw new IllegalArgumentException("Invalid stable sketch id");
        return value;
    }

    private static String normalizedOptionalId(String id) {
        if (id == null) return null;
        String value = id.trim();
        return value.isEmpty() ? null : normalizedId(value);
    }

    private static boolean sameConstraint(SketchConstraint a, SketchConstraint b) {
        if (!a.id.equals(b.id) || a.kind != b.kind) return false;
        if (!a.primaryEntityId.equals(b.primaryEntityId)) return false;
        if (a.primaryPointIndex != b.primaryPointIndex || a.secondaryPointIndex != b.secondaryPointIndex) return false;
        if (a.secondaryEntityId == null ? b.secondaryEntityId != null : !a.secondaryEntityId.equals(b.secondaryEntityId)) return false;
        if (a.driving != b.driving) return false;
        if (Double.isNaN(a.value) || Double.isNaN(b.value)) return Double.isNaN(a.value) && Double.isNaN(b.value);
        return near(a.value, b.value);
    }

    private static boolean sameGeometry(SketchEntity a, SketchEntity b) {
        if (a.kind() != b.kind()) return false;
        if (a instanceof SketchPoint && b instanceof SketchPoint) {
            return same(((SketchPoint) a).position, ((SketchPoint) b).position);
        }
        if (a instanceof SketchGeometry.Line && b instanceof SketchGeometry.Line) {
            SketchGeometry.Line x=(SketchGeometry.Line)a,y=(SketchGeometry.Line)b;
            return same(x.a,y.a)&&same(x.b,y.b);
        }
        if (a instanceof SketchGeometry.Circle && b instanceof SketchGeometry.Circle) {
            SketchGeometry.Circle x=(SketchGeometry.Circle)a,y=(SketchGeometry.Circle)b;
            return same(x.center,y.center)&&near(x.radiusMm,y.radiusMm);
        }
        if (a instanceof SketchGeometry.Arc && b instanceof SketchGeometry.Arc) {
            SketchGeometry.Arc x=(SketchGeometry.Arc)a,y=(SketchGeometry.Arc)b;
            return same(x.center,y.center)&&near(x.radiusMm,y.radiusMm)&&near(x.startDeg,y.startDeg)&&near(x.sweepDeg,y.sweepDeg);
        }
        if (a instanceof SketchGeometry.Rect && b instanceof SketchGeometry.Rect) {
            SketchGeometry.Rect x=(SketchGeometry.Rect)a,y=(SketchGeometry.Rect)b;
            return same(x.origin,y.origin)&&near(x.u.xMm,y.u.xMm)&&near(x.u.yMm,y.u.yMm)
                    &&near(x.v.xMm,y.v.xMm)&&near(x.v.yMm,y.v.yMm);
        }
        if (a instanceof SketchPolygon && b instanceof SketchPolygon) {
            return samePoints(((SketchPolygon)a).vertices(),((SketchPolygon)b).vertices());
        }
        if (a instanceof SketchGeometry.Polyline && b instanceof SketchGeometry.Polyline) {
            SketchGeometry.Polyline x=(SketchGeometry.Polyline)a,y=(SketchGeometry.Polyline)b;
            return x.closed==y.closed&&samePoints(x.points(),y.points());
        }
        return false;
    }

    private static boolean samePoints(List<SketchGeometry.Point> a,List<SketchGeometry.Point> b){
        if(a.size()!=b.size())return false;
        for(int i=0;i<a.size();i++)if(!same(a.get(i),b.get(i)))return false;
        return true;
    }
    private static boolean same(SketchGeometry.Point a,SketchGeometry.Point b){return near(a.xMm,b.xMm)&&near(a.yMm,b.yMm);}
    private static boolean near(double a,double b){
        double floatUlp=Math.max(Math.ulp((float)a),Math.ulp((float)b));
        return Math.abs(a-b)<=Math.max(EPS,floatUlp);
    }
    private static boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v)&&Math.abs(v)<=1.0e9;}
}
