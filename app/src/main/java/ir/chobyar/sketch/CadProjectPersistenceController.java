package ir.chobyar.sketch;

import android.content.Context;
import java.lang.reflect.Field;
import java.util.List;

/** Transactional Save/Open boundary for exact model plus renderer-only workspace state. */
final class CadProjectPersistenceController {
    private CadProjectPersistenceController(){}

    static String encode(Shapr3DGuideCadCanvasView cad){return encode(cad,null,null);}

    static String encode(Shapr3DGuideCadCanvasView cad,CadAppearanceController appearance,SectionViewController section){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        if((appearance==null)!=(section==null))throw new IllegalArgumentException("Visual workspace controllers are incomplete");
        String sketch=cad.exportSketchProjectState();
        boolean hasBodies=hasAnySolidBody(cad),hasReference=cad.hasReferenceImage();
        String model=null;
        if(hasBodies||hasReference){
            model=ExactModelProjectAdapter.exportModel(cad);
            ExactModelProjectAdapter.validateAgainstSketch(model,sketch);
        }
        String reference=null;
        if(hasReference){
            reference=ReferenceImageProjectAdapter.exportState(cad);
            ReferenceImageProjectAdapter.validate(reference);
        }
        String visual=null;
        if(appearance!=null){
            visual=WorkspaceVisualProjectAdapter.exportState(appearance,section);
            WorkspaceVisualProjectAdapter.validate(visual);
        }
        if(hasReference||visual!=null)return CadProjectDocument.encodeWorkspace(sketch,model,reference,visual);
        if(hasBodies)return CadProjectDocument.encodeModel(sketch,model);
        return CadProjectDocument.encodeSketch(sketch);
    }

    private static boolean hasAnySolidBody(Shapr3DGuideCadCanvasView cad){
        try{
            Field field=SolidCadCanvasView.class.getDeclaredField("bodies");
            field.setAccessible(true);
            Object value=field.get(cad);
            return value instanceof List && !((List<?>)value).isEmpty();
        }catch(ReflectiveOperationException e){throw new IllegalStateException("Solid model presence could not be inspected",e);}
    }

    static CadProjectDocument.Decoded validate(Shapr3DGuideCadCanvasView cad,String raw){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        CadProjectDocument.Decoded decoded=CadProjectDocument.decode(raw);
        if(!cad.canImportSketchProjectState(decoded.sketchState))throw new IllegalArgumentException("Project Sketch is invalid");
        if(decoded.hasExactModel())ExactModelProjectAdapter.validateAgainstSketch(decoded.modelState,decoded.sketchState);
        if(decoded.hasReferenceImage())ReferenceImageProjectAdapter.validate(decoded.referenceImageState);
        if(decoded.hasWorkspaceVisual())WorkspaceVisualProjectAdapter.validate(decoded.workspaceVisualState);
        return decoded;
    }

    static String restore(Shapr3DGuideCadCanvasView cad,String raw){return restore(cad,null,null,null,raw);}

    static String restore(Shapr3DGuideCadCanvasView cad,CadAppearanceController appearance,
                          SectionViewController section,CadAppearanceController.Sink sink,String raw){
        if((appearance==null)!=(section==null))throw new IllegalArgumentException("Visual workspace controllers are incomplete");
        CadProjectDocument.Decoded decoded=validate(cad,raw);
        String previous=encode(cad,appearance,section);
        preflight(cad.getContext(),decoded);
        try{return restoreValidated(cad,appearance,section,sink,decoded);}
        catch(RuntimeException failure){
            try{restoreValidated(cad,appearance,section,sink,CadProjectDocument.decode(previous));}catch(Throwable ignored){}
            throw failure;
        }
    }

    private static void preflight(Context context,CadProjectDocument.Decoded decoded){
        Shapr3DGuideCadCanvasView scratch=new Shapr3DGuideCadCanvasView(context);
        CadAppearanceController scratchAppearance=new CadAppearanceController();
        SectionViewController scratchSection=new SectionViewController();
        try{restoreValidated(scratch,scratchAppearance,scratchSection,null,decoded);}
        finally{scratch.clearAll();}
    }

    private static String restoreValidated(Shapr3DGuideCadCanvasView cad,CadAppearanceController appearance,
                                           SectionViewController section,CadAppearanceController.Sink sink,
                                           CadProjectDocument.Decoded decoded){
        cad.clearAll();
        String sketchStatus=cad.importSketchProjectState(decoded.sketchState);
        String modelStatus=null;
        if(decoded.hasExactModel())modelStatus=ExactModelProjectAdapter.restoreModel(cad,decoded.modelState,decoded.sketchState);
        if(decoded.hasReferenceImage())ReferenceImageProjectAdapter.restore(cad,decoded.referenceImageState);
        if(appearance!=null){
            if(decoded.hasWorkspaceVisual())WorkspaceVisualProjectAdapter.restore(appearance,section,decoded.workspaceVisualState,sink);
            else{
                appearance.restore(CadMaterialPreset.of(CadMaterialPreset.Preset.WOOD),sink);
                section.restore(false,SectionViewController.Axis.Z,0.0,false);
            }
        }
        String status=sketchStatus;
        if(modelStatus!=null)status+=" • "+modelStatus;
        if(decoded.hasReferenceImage())status+=" • Reference Image restored";
        if(decoded.hasWorkspaceVisual()&&appearance!=null)status+=" • Appearance/Section restored";
        return status;
    }
}
