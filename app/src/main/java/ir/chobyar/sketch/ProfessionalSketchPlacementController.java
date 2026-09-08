package ir.chobyar.sketch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import ir.chobyar.sketch.core.ConstructionPlane;
import ir.chobyar.sketch.core.ConstructionPlaneDocument;

/** Small interaction authority separating Construct, new-Sketch placement, and existing-Sketch editing. */
public final class ProfessionalSketchPlacementController {
    public enum State { INACTIVE, AWAITING_TARGET }
    public enum ItemKind { BODY, SKETCH, PLANE, REFERENCE_IMAGE }
    public static final class ItemId {
        public final ItemKind kind; public final String stableId;
        ItemId(ItemKind kind,String stableId){this.kind=kind;this.stableId=stableId;}
        @Override public String toString(){return kind.name()+":"+stableId;}
    }

    private final SpatialCadCanvasView cad;
    private State state=State.INACTIVE;

    public ProfessionalSketchPlacementController(SpatialCadCanvasView cad){if(cad==null)throw new IllegalArgumentException("CAD view is required");this.cad=cad;}
    public State state(){return state;}
    public boolean begin(){if(!cad.is3DOverview())return false;state=State.AWAITING_TARGET;return true;}
    public void cancel(){state=State.INACTIVE;}

    public boolean activateConstructionPlane(String planeId){
        if(!cad.constructionPlaneModel().containsPlane(planeId))return false;
        if(!cad.is3DOverview())return false;
        return cad.constructionPlaneModel().activatePlaneWithoutSketch(planeId);
    }

    public String startNewSketchOnPlane(String planeId){
        if(state!=State.AWAITING_TARGET)throw new IllegalStateException("Sketch placement target was not requested");
        if(!cad.constructionPlaneModel().containsPlane(planeId))throw new IllegalArgumentException("Construction Plane was not found");
        cad.createSketchSpaceOnConstructionPlane("Sketch",planeId);state=State.INACTIVE;return cad.activeSketchStableId();
    }

    public boolean editExistingSketch(String sketchId){
        String id=sketchId==null?"":sketchId.trim();if(id.isEmpty()||cad.constructionPlaneModel().planeIdForSketch(id)==null)return false;
        if(!cad.activateSketchStableId(id))return false;cad.enterActiveSketchView();state=State.INACTIVE;return true;
    }

    public String planeIdForSketch(String sketchId){return cad.constructionPlaneModel().planeIdForSketch(sketchId);}
    public boolean renamePlane(String id,String name){return cad.constructionPlaneModel().renamePlane(id,name);}
    public ConstructionPlaneDocument.DeleteResult deletePlane(String id){return cad.constructionPlaneModel().deletePlane(id);}
    public String planeIdByDisplayName(String name){for(ConstructionPlane p:cad.constructionPlaneSnapshot())if(p.displayName.equals(name))return p.id;return null;}

    public List<String> typedItemIds(){
        List<String> out=new ArrayList<>();
        for(Map.Entry<String,String> e:cad.sketchPlaneAssignmentSnapshot().entrySet())out.add(new ItemId(ItemKind.SKETCH,e.getKey()).toString());
        for(ConstructionPlane p:cad.constructionPlaneSnapshot())out.add(new ItemId(ItemKind.PLANE,p.id).toString());
        return Collections.unmodifiableList(out);
    }
}