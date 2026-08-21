package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Renderer-only section view state and triangle clipping.
 *
 * The controller never changes OCCT geometry, topology references, History,
 * dimensions, sketches or export. It clips only the triangle snapshot that is
 * sent to Filament so the CAD model remains exact and untouched.
 */
final class SectionViewController {
    enum Axis { X, Y, Z }

    private boolean enabled;
    private Axis axis = Axis.Z;
    private double offsetMm;
    private boolean flipped;

    boolean isEnabled() { return enabled; }
    Axis axis() { return axis; }
    double offsetMm() { return offsetMm; }
    boolean isFlipped() { return flipped; }

    void enable(Axis nextAxis) {
        axis = nextAxis == null ? Axis.Z : nextAxis;
        enabled = true;
    }

    void disable() { enabled = false; }

    void setOffsetMm(double value) {
        if (Double.isFinite(value)) offsetMm = value;
    }

    void flip() { flipped = !flipped; }

    int selectedIndex() {
        if (!enabled) return 0;
        if (axis == Axis.Z) return 1;
        if (axis == Axis.X) return 2;
        return 3;
    }

    String summary() {
        if (!enabled) return "Section View • خاموش";
        return String.format(Locale.US, "Section %s • %.1f mm%s", axis.name(), offsetMm, flipped ? " • Flip" : "");
    }

    double[] apply(double[] xyz) {
        if (xyz == null || xyz.length < 9 || !enabled) return xyz == null ? new double[0] : xyz;
        ArrayList<Double> out = new ArrayList<>();
        for (int i = 0; i + 8 < xyz.length; i += 9) {
            Vertex a = new Vertex(xyz[i], xyz[i + 1], xyz[i + 2]);
            Vertex b = new Vertex(xyz[i + 3], xyz[i + 4], xyz[i + 5]);
            Vertex c = new Vertex(xyz[i + 6], xyz[i + 7], xyz[i + 8]);
            List<Vertex> polygon = new ArrayList<>(3);
            polygon.add(a); polygon.add(b); polygon.add(c);
            polygon = clipPolygon(polygon);
            if (polygon.size() < 3) continue;
            Vertex root = polygon.get(0);
            for (int p = 1; p + 1 < polygon.size(); p++) {
                append(out, root); append(out, polygon.get(p)); append(out, polygon.get(p + 1));
            }
        }
        double[] clipped = new double[out.size()];
        for (int i = 0; i < out.size(); i++) clipped[i] = out.get(i);
        return clipped;
    }

    private List<Vertex> clipPolygon(List<Vertex> input) {
        ArrayList<Vertex> out = new ArrayList<>();
        if (input.isEmpty()) return out;
        Vertex previous = input.get(input.size() - 1);
        double previousDistance = signedDistance(previous);
        boolean previousInside = previousDistance >= -1e-9;
        for (Vertex current : input) {
            double currentDistance = signedDistance(current);
            boolean currentInside = currentDistance >= -1e-9;
            if (currentInside != previousInside) {
                double denominator = previousDistance - currentDistance;
                double t = Math.abs(denominator) < 1e-12 ? 0.0 : previousDistance / denominator;
                t = Math.max(0.0, Math.min(1.0, t));
                out.add(Vertex.mix(previous, current, t));
            }
            if (currentInside) out.add(current);
            previous = current;
            previousDistance = currentDistance;
            previousInside = currentInside;
        }
        return out;
    }

    private double signedDistance(Vertex v) {
        double coordinate = axis == Axis.X ? v.x : axis == Axis.Y ? v.y : v.z;
        double d = coordinate - offsetMm;
        return flipped ? -d : d;
    }

    private static void append(ArrayList<Double> out, Vertex v) {
        out.add(v.x); out.add(v.y); out.add(v.z);
    }

    private static final class Vertex {
        final double x, y, z;
        Vertex(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
        static Vertex mix(Vertex a, Vertex b, double t) {
            return new Vertex(a.x + (b.x - a.x) * t,
                    a.y + (b.y - a.y) * t,
                    a.z + (b.z - a.z) * t);
        }
    }
}
