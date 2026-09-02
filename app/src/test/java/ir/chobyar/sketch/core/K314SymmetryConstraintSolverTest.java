package ir.chobyar.sketch.core;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** K3.14 RED structural fence: model/solver must own a three-entity Symmetry contract. */
public final class K314SymmetryConstraintSolverTest {

    @Test public void modelMustExposeThreeStableIdSymmetryConstraint() throws Exception {
        SketchConstraint.Kind kind = Enum.valueOf(SketchConstraint.Kind.class, "SYMMETRY");
        assertNotNull(kind);

        Method factory = SketchConstraint.class.getDeclaredMethod(
                "symmetry", String.class, String.class, String.class, String.class);
        assertNotNull(factory);

        Field tertiary = SketchConstraint.class.getDeclaredField("tertiaryEntityId");
        assertNotNull(tertiary);

        Object value = factory.invoke(null, "sym-1", "source", "mirror", "axis");
        assertTrue(value instanceof SketchConstraint);
        SketchConstraint constraint = (SketchConstraint) value;
        assertTrue(constraint.referencedEntityIds().contains("source"));
        assertTrue(constraint.referencedEntityIds().contains("mirror"));
        assertTrue(constraint.referencedEntityIds().contains("axis"));
    }
}
