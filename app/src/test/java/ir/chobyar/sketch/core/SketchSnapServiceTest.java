package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotNull;

public class SketchSnapServiceTest {
    private static final double EPS = 1.0e-6;
    private final SketchSnapService service = new SketchSnapService();

    @Test public void lineEndpointMidpointAndNearestEdgeAreModelSpaceDeterministic() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line("line-a",
                p(0,0), p(100,0)));

        SketchSnapService.Result endpoint = service.snap(doc,p(0.4,0.2),2.0,null);
        assertNotNull(endpoint);
        assertEquals(SketchSnapService.Kind.ENDPOINT,endpoint.kind);
        assertPoint(endpoint.point,0,0);

        SketchSnapService.Result midpoint = service.snap(doc,p(50.2,0.3),2.0,null);
        assertNotNull(midpoint);
        assertEquals(SketchSnapService.Kind.MIDPOINT,midpoint.kind);
        assertPoint(midpoint.point,50,0);

        SketchSnapService.Result edge = service.snap(doc,p(72,1.4),2.0,null);
        assertNotNull(edge);
        assertEquals(SketchSnapService.Kind.ON_EDGE,edge.kind);
        assertPoint(edge.point,72,0);
    }

    @Test public void lineIntersectionIsStableAndCanExcludeEditedEntity() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Line("horizontal",p(0,20),p(100,20)));
        doc.add(new SketchGeometry.Line("vertical",p(40,0),p(40,80)));

        SketchSnapService.Result hit = service.snap(doc,p(40,20),2.0,null);
        assertNotNull(hit);
        assertEquals(SketchSnapService.Kind.INTERSECTION,hit.kind);
        assertPoint(hit.point,40,20);
        assertEquals("horizontal",hit.entityId);
        assertEquals("vertical",hit.secondaryEntityId);

        SketchSnapService.Result excluded = service.snap(doc,p(40.2,20.1),1.0,"vertical");
        assertNotNull(excluded);
        assertEquals("horizontal",excluded.entityId);
        assertEquals(SketchSnapService.Kind.ON_EDGE,excluded.kind);
        assertPoint(excluded.point,40.2,20);
    }

    @Test public void circleCenterQuadrantAndCircumferenceUseOneModelService() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Circle("circle",p(20,30),10));

        SketchSnapService.Result center = service.snap(doc,p(20.2,30.1),1.0,null);
        assertNotNull(center);
        assertEquals(SketchSnapService.Kind.CENTER,center.kind);
        assertPoint(center.point,20,30);

        SketchSnapService.Result quadrant = service.snap(doc,p(30.4,30.2),1.0,null);
        assertNotNull(quadrant);
        assertEquals(SketchSnapService.Kind.QUADRANT,quadrant.kind);
        assertPoint(quadrant.point,30,30);

        SketchSnapService.Result edge = service.snap(doc,p(27.2,37.2),1.0,null);
        assertNotNull(edge);
        assertEquals(SketchSnapService.Kind.ON_EDGE,edge.kind);
        assertEquals(10.0,Math.hypot(edge.point.xMm-20,edge.point.yMm-30),EPS);
    }

    @Test public void arcNearestPointRespectsActualSweepInsteadOfFullCircle() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Arc("arc",p(0,0),10,0,90));

        SketchSnapService.Result insideSweep = service.snap(doc,p(7.2,7.2),1.0,null);
        assertNotNull(insideSweep);
        assertEquals(SketchSnapService.Kind.ON_EDGE,insideSweep.kind);

        // (-10,0) is on the supporting circle but outside the 0..90 degree arc.
        // A professional sketch snap must not report a phantom arc hit there.
        assertNull(service.snap(doc,p(-10,0),0.5,null));
    }

    @Test public void rectPolygonAndPolylineExposeVerticesMidpointsAndEdges() {
        SketchDocument doc = new SketchDocument();
        doc.add(new SketchGeometry.Rect("rect",p(0,0),
                new SketchGeometry.Vector(20,0),new SketchGeometry.Vector(0,10)));
        doc.add(new SketchPolygon("poly",Arrays.asList(p(40,0),p(60,0),p(50,20))));
        doc.add(new SketchGeometry.Polyline("path",Arrays.asList(p(80,0),p(90,10),p(100,0)),false));

        SketchSnapService.Result rectMid = service.snap(doc,p(10,0.4),1.0,null);
        assertNotNull(rectMid);
        assertEquals("rect",rectMid.entityId);
        assertEquals(SketchSnapService.Kind.MIDPOINT,rectMid.kind);
        assertPoint(rectMid.point,10,0);

        SketchSnapService.Result polygonVertex = service.snap(doc,p(50.3,20.2),1.0,null);
        assertNotNull(polygonVertex);
        assertEquals("poly",polygonVertex.entityId);
        assertEquals(SketchSnapService.Kind.ENDPOINT,polygonVertex.kind);

        SketchSnapService.Result pathEdge = service.snap(doc,p(85.3,4.7),1.0,null);
        assertNotNull(pathEdge);
        assertEquals("path",pathEdge.entityId);
        assertEquals(SketchSnapService.Kind.MIDPOINT,pathEdge.kind);
        assertPoint(pathEdge.point,85,5);
    }

    private static SketchGeometry.Point p(double x,double y){
        return new SketchGeometry.Point(x,y);
    }

    private static void assertPoint(SketchGeometry.Point actual,double x,double y){
        assertEquals(x,actual.xMm,EPS);
        assertEquals(y,actual.yMm,EPS);
    }
}
