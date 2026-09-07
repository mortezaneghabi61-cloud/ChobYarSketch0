package ir.chobyar.sketch.core;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public final class ConstructionPlaneDocumentTest {
    @Test public void builtInPlanesHaveStableIds() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        assertEquals("plane:xy",doc.plane(ConstructionPlane.XY_ID).id);
        assertEquals("plane:xz",doc.plane(ConstructionPlane.XZ_ID).id);
        assertEquals("plane:yz",doc.plane(ConstructionPlane.YZ_ID).id);
        assertEquals(3,doc.planes().size());
    }

    @Test public void offsetPlaneStoresSourceAndDistance() {
        ConstructionPlane p=new ConstructionPlaneDocument().createOffsetPlane(
                ConstructionPlane.XY_ID,12.5,"Shelf datum");
        assertEquals(ConstructionPlane.Provenance.OFFSET,p.provenance);
        assertEquals(ConstructionPlane.XY_ID,p.sourcePlaneId);
        assertEquals(12.5,p.offsetDistanceMm,0.0);
        assertEquals(12.5,p.origin.z,1.0e-9);
    }

    @Test public void sketchReferencesPlaneByStableId() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        doc.assignSketch("sketch:17",ConstructionPlane.XZ_ID);
        assertEquals(ConstructionPlane.XZ_ID,doc.planeIdForSketch("sketch:17"));
    }

    @Test public void renamePlaneDoesNotChangeStableId() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        String id=doc.createOffsetPlane(ConstructionPlane.XY_ID,4,"Datum").id;
        assertTrue(doc.renamePlane(id,"Joinery datum"));
        assertEquals(id,doc.plane(id).id);
        assertEquals("Joinery datum",doc.plane(id).displayName);
    }

    @Test public void dependentPlaneDeletionFailsClosed() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        String id=doc.createOffsetPlane(ConstructionPlane.XY_ID,4,"Datum").id;
        doc.assignSketch("sketch:2",id);
        assertSame(ConstructionPlaneDocument.DeleteResult.IN_USE,doc.deletePlane(id));
        assertEquals(id,doc.planeIdForSketch("sketch:2"));
    }

    @Test public void builtInPlaneDeletionFailsClosed() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        assertSame(ConstructionPlaneDocument.DeleteResult.BUILT_IN,doc.deletePlane(ConstructionPlane.XY_ID));
        assertTrue(doc.containsPlane(ConstructionPlane.XY_ID));
    }

    @Test public void undoRedoRestoresSamePlaneId() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        String id=doc.createOffsetPlane(ConstructionPlane.YZ_ID,-7,"Rear datum").id;
        assertTrue(doc.undo());
        assertFalse(doc.containsPlane(id));
        assertTrue(doc.redo());
        assertEquals(id,doc.plane(id).id);
    }

    @Test public void sketchAssignmentUndoRedoKeepsRelationship() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        doc.createSketchOnPlane("sketch:2",ConstructionPlane.XZ_ID);
        assertTrue(doc.undo());assertEquals(null,doc.planeIdForSketch("sketch:2"));
        assertTrue(doc.redo());assertEquals(ConstructionPlane.XZ_ID,doc.planeIdForSketch("sketch:2"));
    }

    @Test public void renameAndVisibilityAreUndoableWithoutChangingIdentity() {
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();String id=doc.createOffsetPlane(ConstructionPlane.XY_ID,3,"Datum").id;
        doc.renamePlane(id,"Renamed");doc.setPlaneVisibility(id,false);assertFalse(doc.plane(id).visible);
        assertTrue(doc.undo());assertTrue(doc.plane(id).visible);assertEquals("Renamed",doc.plane(id).displayName);
        assertTrue(doc.undo());assertEquals("Datum",doc.plane(id).displayName);assertEquals(id,doc.plane(id).id);
    }

    @Test public void legacyPlaneSchemaMigratesDeterministically() {
        ConstructionPlane.Legacy first=new ConstructionPlane.Legacy("SKETCH_4","XY + 8 mm",
                new ConstructionPlane.Vector(0,0,8),new ConstructionPlane.Vector(1,0,0),new ConstructionPlane.Vector(0,1,0));
        ConstructionPlaneDocument a=ConstructionPlaneDocument.migrateLegacy(Collections.singletonList(first));
        ConstructionPlaneDocument b=ConstructionPlaneDocument.migrateLegacy(Collections.singletonList(first));
        assertEquals(a.planeIdForSketch("SKETCH_4"),b.planeIdForSketch("SKETCH_4"));
        assertNotEquals(ConstructionPlane.XY_ID,a.planeIdForSketch("SKETCH_4"));
    }

    @Test(expected=IllegalArgumentException.class)
    public void malformedGeometryFailsClosed() {
        ConstructionPlane.offset("plane:bad","Bad",ConstructionPlane.XY_ID,Double.NaN,
                new ConstructionPlane.Vector(0,0,0),new ConstructionPlane.Vector(1,0,0),
                new ConstructionPlane.Vector(0,1,0),new ConstructionPlane.Vector(0,0,1),true,4);
    }
}
