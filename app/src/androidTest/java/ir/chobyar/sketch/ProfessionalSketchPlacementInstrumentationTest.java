package ir.chobyar.sketch;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import ir.chobyar.sketch.core.ConstructionPlaneDocument;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Production contracts for plane-aware Sketch placement and typed project Items. */
@RunWith(AndroidJUnit4.class)
public final class ProfessionalSketchPlacementInstrumentationTest {
    private static K33MirroredCadCanvasView canvas() {
        Context context = ApplicationProvider.getApplicationContext();
        return new K33MirroredCadCanvasView(context);
    }

    private static void onMain(Runnable task) {
        final Throwable[] failure = {null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try { task.run(); } catch (Throwable t) { failure[0] = t; }
        });
        if (failure[0] instanceof AssertionError) throw (AssertionError) failure[0];
        if (failure[0] instanceof RuntimeException) throw (RuntimeException) failure[0];
        if (failure[0] != null) throw new RuntimeException(failure[0]);
    }

    @Test public void constructOffsetCreatesPlaneWithoutSketch() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            String sketchBefore = cad.activeSketchStableId();
            int countBefore = countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH);
            String planeId = cad.createOffsetConstructionPlane(12.5f, "Shelf datum");
            assertTrue(cad.hasConstructionPlane(planeId));
            assertEquals(sketchBefore, cad.activeSketchStableId());
            assertEquals(countBefore, countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH));
            assertEquals(12.5, cad.constructionPlaneOffsetMm(planeId), 0.0);
            assertTrue(cad.is3DOverview());
        });
    }

    @Test public void constructBasePlaneDoesNotCreateSketch() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            String sketchBefore = cad.activeSketchStableId();
            int countBefore = countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH);
            assertTrue(cad.activateConstructionPlane("plane:xz").contains("XZ"));
            assertEquals(sketchBefore, cad.activeSketchStableId());
            assertEquals(countBefore, countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH));
            assertEquals("plane:xz", cad.activeConstructionPlaneId());
            assertTrue(cad.is3DOverview());
        });
    }

    @Test public void sketchFromModelRequiresExplicitTarget() {
        try (ActivityScenario<ChobYarActivity> scenario = ActivityScenario.launch(ChobYarActivity.class)) {
            scenario.onActivity(activity -> {
                K33MirroredCadCanvasView cad = findCanvas(activity.getWindow().getDecorView());
                assertNotNull(cad);
                cad.setStandardView("ISO");
                int before = countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH);
                View sketch = findVisible(activity.getWindow().getDecorView(), "Sketch");
                assertNotNull("Production Sketch button not found", sketch);
                assertTrue(sketch.performClick());
                assertTrue(cad.isSketchPlacementAwaitingTarget());
                assertTrue(cad.is3DOverview());
                assertEquals(before, countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH));
            });
        }
    }

    @Test public void cancelSketchPlacementCreatesNothing() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            int before = countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH);
            cad.beginSketchPlacement();
            assertTrue(cad.isSketchPlacementAwaitingTarget());
            cad.cancelSketchPlacement();
            assertFalse(cad.isSketchPlacementAwaitingTarget());
            assertTrue(cad.is3DOverview());
            assertEquals(before, countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH));
        });
    }

    @Test public void sketchOnBuiltInPlaneReferencesExactStablePlaneId() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            cad.beginSketchPlacement();
            String previous = cad.activeSketchStableId();
            assertTrue(cad.startSketchOnConstructionPlane("plane:xz").contains("Sketch"));
            String created = cad.activeSketchStableId();
            assertNotEquals(previous, created);
            assertEquals("plane:xz", cad.sketchPlaneId(created));
            assertFalse(cad.is3DOverview());
        });
    }

    @Test public void sketchOnOffsetPlaneReferencesExactStablePlaneId() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            String planeId = cad.createOffsetConstructionPlane(17.0f, "Upper shelf");
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane(planeId);
            assertEquals(planeId, cad.sketchPlaneId(cad.activeSketchStableId()));
            assertEquals(17.0, cad.constructionPlaneOffsetMm(planeId), 0.0);
        });
    }

    @Test public void itemsExposeConstructionPlanes() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            CadItemRef[] items = cad.projectItemRefs();
            assertNotNull(find(items, CadItemRef.Kind.PLANE, "plane:xy"));
            assertNotNull(find(items, CadItemRef.Kind.PLANE, "plane:xz"));
            assertNotNull(find(items, CadItemRef.Kind.PLANE, "plane:yz"));
        });
    }

    @Test public void itemsExposeSketchesSeparatelyFromBodies() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("RECT 0 0 80 50");
            assertTrue(cad.executeCommand("EXTRUDE 30").contains("created"));
            CadItemRef[] items = cad.projectItemRefs();
            assertTrue(countKind(items, CadItemRef.Kind.SKETCH) >= 1);
            assertEquals(1, countKind(items, CadItemRef.Kind.BODY));
            assertNotEquals(findFirst(items, CadItemRef.Kind.SKETCH).stableId,
                    findFirst(items, CadItemRef.Kind.BODY).stableId);
        });
    }

    @Test public void planeHideShowFromItemsMutatesModelVisibility() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            assertTrue(cad.setConstructionPlaneItemVisibility("plane:xy", false));
            assertFalse(cad.isConstructionPlaneVisible("plane:xy"));
            assertFalse(find(cad.projectItemRefs(), CadItemRef.Kind.PLANE, "plane:xy").visible);
            assertTrue(cad.setConstructionPlaneItemVisibility("plane:xy", true));
            assertTrue(cad.isConstructionPlaneVisible("plane:xy"));
        });
    }

    @Test public void planeRenameKeepsStableId() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            String id = cad.createOffsetConstructionPlane(8f, "Datum");
            assertTrue(cad.renameConstructionPlaneItem(id, "Shelf datum"));
            CadItemRef item = find(cad.projectItemRefs(), CadItemRef.Kind.PLANE, id);
            assertNotNull(item);
            assertEquals(id, item.stableId);
            assertEquals("Shelf datum", item.label);
        });
    }

    @Test public void builtInPlaneDeleteFailsClosed() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            assertEquals(ConstructionPlaneDocument.DeleteResult.BUILT_IN,
                    cad.deleteConstructionPlaneItem("plane:xy"));
            assertTrue(cad.hasConstructionPlane("plane:xy"));
        });
    }

    @Test public void planeReferencedBySketchDeleteFailsClosed() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            String id = cad.createOffsetConstructionPlane(4f, "Sketch datum");
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane(id);
            assertEquals(ConstructionPlaneDocument.DeleteResult.IN_USE,
                    cad.deleteConstructionPlaneItem(id));
            assertTrue(cad.hasConstructionPlane(id));
        });
    }

    @Test public void derivedPlaneSourceDeleteFailsClosed() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            String source = cad.createOffsetConstructionPlane(5f, "Source");
            String child = cad.createOffsetConstructionPlane(2f, "Child");
            assertTrue(cad.hasConstructionPlane(child));
            assertEquals(ConstructionPlaneDocument.DeleteResult.HAS_DERIVED_PLANES,
                    cad.deleteConstructionPlaneItem(source));
            assertTrue(cad.hasConstructionPlane(source));
        });
    }

    @Test public void existingSketchItemEditsSameStableSketch() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane("plane:xz");
            String id = cad.activeSketchStableId();
            cad.finishSketchSessionToModel();
            assertTrue(cad.editExistingSketch(id).contains("Sketch"));
            assertEquals(id, cad.activeSketchStableId());
            assertEquals("plane:xz", cad.sketchPlaneId(id));
        });
    }

    @Test public void newSketchDoesNotReusePreviousActiveSketch() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane("plane:xz");
            String first = cad.activeSketchStableId();
            cad.finishSketchSessionToModel();
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane("plane:yz");
            String second = cad.activeSketchStableId();
            assertNotEquals(first, second);
            assertEquals("plane:yz", cad.sketchPlaneId(second));
        });
    }

    @Test public void faceToSketchKeepsValidStableRelationship() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.executeCommand("RECT 0 0 80 50");
            assertTrue(cad.executeCommand("EXTRUDE 30").contains("created"));
            selectFirstBodyFace(cad);
            String before = cad.activeSketchStableId();
            String result = cad.sketchOnSelectedFace();
            assertTrue(result, result.contains("Sketch"));
            String created = cad.activeSketchStableId();
            assertNotEquals(before, created);
            String planeId = cad.sketchPlaneId(created);
            assertNotNull(planeId);
            assertTrue(cad.hasConstructionPlane(planeId));
            assertFalse(cad.is3DOverview());
        });
    }

    @Test public void invalidFaceSketchFailsClosed() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            int before = countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH);
            cad.sketchOnSelectedFace();
            assertEquals(before, countKind(cad.projectItemRefs(), CadItemRef.Kind.SKETCH));
            assertTrue(cad.is3DOverview());
        });
    }

    @Test public void doneSketchReturnsToModelViewWithoutGeometryMutation() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("FRONT");
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane("plane:xz");
            String before = cad.exportSketchProjectState();
            cad.finishSketchSessionToModel();
            assertTrue(cad.is3DOverview());
            assertEquals(before, cad.exportSketchProjectState());
        });
    }

    @Test public void doneSketchDoesNotMutatePlaneModelRevision() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane("plane:yz");
            long before = cad.constructionPlaneModelRevision();
            cad.finishSketchSessionToModel();
            assertEquals(before, cad.constructionPlaneModelRevision());
        });
    }

    @Test public void saveOpenKeepsPlaneItemAndSketchRelationship() {
        onMain(() -> {
            K33MirroredCadCanvasView source = canvas();
            source.setStandardView("ISO");
            String planeId = source.createOffsetConstructionPlane(13f, "Persistent datum");
            source.renameConstructionPlaneItem(planeId, "Cabinet datum");
            source.beginSketchPlacement();
            source.startSketchOnConstructionPlane(planeId);
            String sketchId = source.activeSketchStableId();
            String project = CadProjectPersistenceController.encode(source);
            K33MirroredCadCanvasView restored = canvas();
            CadProjectPersistenceController.restore(restored, project);
            CadItemRef plane = find(restored.projectItemRefs(), CadItemRef.Kind.PLANE, planeId);
            assertNotNull(plane);
            assertEquals("Cabinet datum", plane.label);
            assertEquals(planeId, restored.sketchPlaneId(sketchId));
        });
    }

    @Test public void recoveryKeepsPlaneVisibilityAndSketchTarget() {
        onMain(() -> {
            Context context = ApplicationProvider.getApplicationContext();
            K33MirroredCadCanvasView source = canvas();
            source.setStandardView("ISO");
            String planeId = source.createOffsetConstructionPlane(-6f, "Recovery datum");
            source.setConstructionPlaneItemVisibility(planeId, false);
            source.beginSketchPlacement();
            source.startSketchOnConstructionPlane(planeId);
            String sketchId = source.activeSketchStableId();
            WorkspaceRecoveryStore store = new WorkspaceRecoveryStore(context);
            store.clear();
            store.save(CadProjectPersistenceController.encode(source), "Placement recovery");
            WorkspaceRecoveryStore.Snapshot snapshot = store.load();
            assertNotNull(snapshot);
            K33MirroredCadCanvasView restored = canvas();
            CadProjectPersistenceController.restore(restored, snapshot.payload);
            store.clear();
            assertFalse(restored.isConstructionPlaneVisible(planeId));
            assertEquals(planeId, restored.sketchPlaneId(sketchId));
        });
    }

    @Test public void placementCancelDoesNotConsumeStableSketchIdentity() {
        onMain(() -> {
            K33MirroredCadCanvasView cad = canvas();
            cad.setStandardView("ISO");
            cad.beginSketchPlacement();
            cad.cancelSketchPlacement();
            cad.beginSketchPlacement();
            cad.startSketchOnConstructionPlane("plane:xy");
            assertEquals("sketch:2", cad.activeSketchStableId());
        });
    }

    private static int countKind(CadItemRef[] items, CadItemRef.Kind kind) {
        int count = 0;
        for (CadItemRef item : items) if (item.kind == kind) count++;
        return count;
    }

    private static CadItemRef findFirst(CadItemRef[] items, CadItemRef.Kind kind) {
        for (CadItemRef item : items) if (item.kind == kind) return item;
        return null;
    }

    private static CadItemRef find(CadItemRef[] items, CadItemRef.Kind kind, String stableId) {
        for (CadItemRef item : items)
            if (item.kind == kind && stableId.equals(item.stableId)) return item;
        return null;
    }

    private static View findVisible(View view, String description) {
        CharSequence current = view.getContentDescription();
        if (view.getVisibility() == View.VISIBLE && current != null && description.contentEquals(current)) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findVisible(group.getChildAt(i), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static K33MirroredCadCanvasView findCanvas(View view) {
        if (view instanceof K33MirroredCadCanvasView) return (K33MirroredCadCanvasView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                K33MirroredCadCanvasView found = findCanvas(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void selectFirstBodyFace(K33MirroredCadCanvasView cad) {
        try {
            Field selectedBodyField = SolidCadCanvasView.class.getDeclaredField("selectedBody");
            selectedBodyField.setAccessible(true);
            Object body = selectedBodyField.get(cad);
            assertNotNull("Extrude did not select a production body", body);
            Field csgField = body.getClass().getDeclaredField("csg");
            csgField.setAccessible(true);
            SolidCSG csg = (SolidCSG) csgField.get(body);
            assertNotNull(csg);
            assertFalse("Body has no faces", csg.polygons().isEmpty());
            Field selectedFaceField = SolidCadCanvasView.class.getDeclaredField("selectedFace");
            selectedFaceField.setAccessible(true);
            selectedFaceField.set(cad, csg.polygons().get(0));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Could not select the production face for the contract", e);
        }
    }
}
