package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class K36dConstraintInteractionContractInstrumentationTest {

    @Test
    public void touchAndStylusUseSameStablePointReferenceShape() {
        ConstraintInteractionContract.PointRef touch =
                new ConstraintInteractionContract.PointRef("line-17", 1);
        ConstraintInteractionContract.PointRef stylus =
                new ConstraintInteractionContract.PointRef("line-17", 1);

        assertEquals(touch, stylus);
        assertEquals(touch.hashCode(), stylus.hashCode());
    }

    @Test
    public void endpointEdgeAndExtensionHaveDistinctFeedback() {
        assertEquals("Endpoint", ConstraintInteractionContract.SnapFeedback.ENDPOINT.label);
        assertEquals("On edge", ConstraintInteractionContract.SnapFeedback.ON_EDGE.label);
        assertEquals("On extension", ConstraintInteractionContract.SnapFeedback.ON_EXTENSION.label);
        assertFalse(ConstraintInteractionContract.SnapFeedback.ENDPOINT.label
                .equals(ConstraintInteractionContract.SnapFeedback.ON_EDGE.label));
        assertFalse(ConstraintInteractionContract.SnapFeedback.ON_EDGE.label
                .equals(ConstraintInteractionContract.SnapFeedback.ON_EXTENSION.label));
    }

    @Test
    public void coincidentIntentCarriesTwoDeterministicEndpointRefs() {
        ConstraintInteractionContract.PointRef driven =
                new ConstraintInteractionContract.PointRef("line-created", 1);
        ConstraintInteractionContract.PointRef target =
                new ConstraintInteractionContract.PointRef("line-existing", 0);

        ConstraintInteractionContract.Intent intent =
                ConstraintInteractionContract.Intent.coincident(driven, target);

        assertEquals(ConstraintInteractionContract.Kind.COINCIDENT, intent.kind);
        assertEquals(driven, intent.drivenPoint);
        assertEquals(target, intent.targetPoint);
        assertNull(intent.hostEntityId);
    }

    @Test
    public void pointOnEntityIntentCarriesEndpointAndHostStableId() {
        ConstraintInteractionContract.PointRef driven =
                new ConstraintInteractionContract.PointRef("line-created", 1);

        ConstraintInteractionContract.Intent intent =
                ConstraintInteractionContract.Intent.pointOnEntity(driven, "line-host");

        assertEquals(ConstraintInteractionContract.Kind.POINT_ON_ENTITY, intent.kind);
        assertEquals(driven, intent.drivenPoint);
        assertEquals("line-host", intent.hostEntityId);
        assertNull(intent.targetPoint);
    }

    @Test
    public void extensionFeedbackDoesNotChangePointOnEntityIdentityContract() {
        ConstraintInteractionContract.PointRef driven =
                new ConstraintInteractionContract.PointRef("line-created", 0);
        ConstraintInteractionContract.Intent intent =
                ConstraintInteractionContract.Intent.pointOnEntity(driven, "line-host");

        ConstraintInteractionContract.SnapFeedback feedback =
                ConstraintInteractionContract.SnapFeedback.ON_EXTENSION;

        assertEquals(ConstraintInteractionContract.Kind.POINT_ON_ENTITY, intent.kind);
        assertEquals("line-host", intent.hostEntityId);
        assertEquals(driven, intent.drivenPoint);
        assertEquals("On extension", feedback.label);
    }

    @Test
    public void conflictsAreExplicitlyNonMutating() {
        ConstraintInteractionContract.Result result =
                ConstraintInteractionContract.Result.conflict(null);

        assertEquals(ConstraintInteractionContract.ResultCode.CONFLICT, result.code);
        assertFalse(result.mutatesGeometry());
        assertTrue(result.message.contains("geometry was left unchanged"));
    }

    @Test
    public void invalidSelfReferencesAreRejectedBeforeSolverMutation() {
        ConstraintInteractionContract.PointRef point =
                new ConstraintInteractionContract.PointRef("line-1", 0);
        try {
            ConstraintInteractionContract.Intent.coincident(point, point);
            fail("Expected self-coincident reference to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("distinct"));
        }

        try {
            ConstraintInteractionContract.Intent.pointOnEntity(point, "line-1");
            fail("Expected self-host point-on-entity reference to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("differ"));
        }
    }

    @Test
    public void seamVersionIsPinnedForParametricAndModelAuthorityIntegration() {
        assertEquals("model-endpoint-constraint-hooks-v1",
                ConstraintInteractionContract.requiredProtectedHookContract());
    }
}
