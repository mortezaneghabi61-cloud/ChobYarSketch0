package ir.chobyar.sketch;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class CadCommandPlannerTest {

    @Test public void englishRectangleThenExtrudeMapsToProductionCommands(){
        CadCommandPlanner.Plan p=CadCommandPlanner.plan("create a rectangle 120 by 80 then extrude it 30 mm");
        assertTrue(p.error(),p.ok());
        assertEquals(Arrays.asList("RECT 0 0 120 80","EXTRUDE 30"),p.commands());
    }

    @Test public void persianDigitsAndPostposedExtrudeAreDeterministic(){
        CadCommandPlanner.Plan p=CadCommandPlanner.plan("\u0645\u0633\u062A\u0637\u06CC\u0644 \u06F1\u06F2\u06F0 \u062F\u0631 \u06F8\u06F0 \u0628\u0633\u0627\u0632 \u0648 \u06F2\u06F0 \u0645\u06CC\u0644\u06CC \u0645\u062A\u0631 \u0627\u06A9\u0633\u062A\u0631\u0648\u062F \u06A9\u0646");
        assertTrue(p.error(),p.ok());
        assertEquals(Arrays.asList("RECT 0 0 120 80","EXTRUDE 20"),p.commands());
    }

    @Test public void centimetersConvertToMillimetersOnlyForLengthOperations(){
        CadCommandPlanner.Plan p=CadCommandPlanner.plan("circle radius 2.5cm then move 1cm -0.5cm");
        assertTrue(p.error(),p.ok());
        assertEquals(Arrays.asList("CIRCLE 0 0 25","MOVE 10 -5"),p.commands());
    }

    @Test public void testedTransformVocabularyMapsExactly(){
        CadCommandPlanner.Plan p=CadCommandPlanner.plan("offset 18 then rotate 90 then scale 0.5 then mirror x then array 3 40 0");
        assertTrue(p.error(),p.ok());
        assertEquals(Arrays.asList(
                "OFFSET 18","ROTATE 90","SCALE 0.5","MIRROR X 0","ARRAY 3 40 0"),p.commands());
    }

    @Test public void selectionDependentOrUnknownOperationFailsClosed(){
        CadCommandPlanner.Plan p=CadCommandPlanner.plan("fillet this edge 5 mm");
        assertFalse(p.ok());
        assertTrue(p.commands().isEmpty());
        assertFalse(p.error().isEmpty());
    }

    @Test public void malformedOrAmbiguousGeometryFailsClosed(){
        assertFalse(CadCommandPlanner.plan("rectangle 120").ok());
        assertFalse(CadCommandPlanner.plan("line 0 0 0 0").ok());
        assertFalse(CadCommandPlanner.plan("circle radius -5").ok());
        assertFalse(CadCommandPlanner.plan("rotate 90 cm").ok());
    }
}
