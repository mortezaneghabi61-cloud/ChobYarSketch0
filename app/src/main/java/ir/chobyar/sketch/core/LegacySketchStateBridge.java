package ir.chobyar.sketch.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * K3.3 migration seam from the persisted legacy canvas representation into
 * {@link SketchDocument}. This deliberately mirrors geometry + stable identity
 * only; constraints/snapping remain legacy-owned until a later authority move.
 */
public final class LegacySketchStateBridge {
    private static final double EPS = 1.0e-6;

    private LegacySketchStateBridge() {}

    public static final class Result {
        public final List<SketchEntity> entities;
        public final Set<String> ignoredAnnotationIds;

        Result(List<SketchEntity> entities, Set<String> ignoredAnnotationIds) {
            this.entities = Collections.unmodifiableList(new ArrayList<>(entities));
            this.ignoredAnnotationIds = Collections.unmodifiableSet(new LinkedHashSet<>(ignoredAnnotationIds));
        }
    }

    public static Result parse(String raw) {
        try {
            JSONObject root = new JSONObject(raw);
            int schema = root.optInt("schemaVersion", -1);
            if (schema != 2) throw new IllegalArgumentException("K3.3 mirror requires stable-id schema v2");
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
            return new Result(modeled, ignored);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot bridge legacy sketch state", e);
        }
    }

    public static void restoreDocument(SketchDocument document, String raw) {
        if (document == null) throw new NullPointerException("document");
        Result result = parse(raw);
        document.restoreExternal(result.entities, Collections.emptySet());
    }

    /** Restore modeled legacy geometry except one in-flight candidate id. */
    public static void restoreDocumentExcluding(SketchDocument document, String raw, String excludedId) {
        if (document == null) throw new NullPointerException("document");
        String excluded = normalizedId(excludedId);
        Result result = parse(raw);
        ArrayList<SketchEntity> kept = new ArrayList<>();
        for (SketchEntity entity : result.entities) if (!excluded.equals(entity.id())) kept.add(entity);
        document.restoreExternal(kept, Collections.emptySet());
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
        for (SketchEntity expected : legacy.entities) {
            SketchEntity actual = document.entity(expected.id());
            if (actual == null || !sameGeometry(expected, actual)) return false;
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
        return document.size() == expectedSize;
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
        // Dimensions and construction guides are still legacy-owned in K3.3.
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
    private static boolean near(double a,double b){return Math.abs(a-b)<=EPS;}
    private static boolean finite(double v){return !Double.isNaN(v)&&!Double.isInfinite(v)&&Math.abs(v)<=1.0e9;}
}
