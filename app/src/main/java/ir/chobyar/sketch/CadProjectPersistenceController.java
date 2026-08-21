package ir.chobyar.sketch;

import android.content.Context;

import java.lang.reflect.Field;
import java.util.List;

/**
 * Transaction boundary used by Save/Open UI.
 *
 * A new file is fully parsed and rebuilt in a scratch CAD workspace before the
 * live document is touched. The current supported document is snapshotted first,
 * so an unexpected live rebuild failure can restore the previous project.
 */
final class CadProjectPersistenceController {
    private CadProjectPersistenceController(){}

    static String encode(Shapr3DGuideCadCanvasView cad){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        String sketch=cad.exportSketchProjectState();
        if(hasAnySolidBody(cad)){
            String model=ExactModelProjectAdapter.exportModel(cad);
            ExactModelProjectAdapter.validateAgainstSketch(model,sketch);
            return CadProjectDocument.encodeModel(sketch,model);
        }
        if(cad.hasReferenceImage())throw new IllegalStateException("Reference Image project persistence is not implemented yet");
        return CadProjectDocument.encodeSketch(sketch);
    }

    /**
     * SolidCadCanvasView intentionally keeps its body collection private.  The
     * Save/Open boundary only needs a loss-prevention predicate here: if any Body
     * exists, model-v2 must be used instead of silently writing sketch-v1.  Keep
     * this reflective compatibility shim inside persistence code rather than
     * exposing prototype internals to production UI.
     */
    private static boolean hasAnySolidBody(Shapr3DGuideCadCanvasView cad){
        try{
            Field field=SolidCadCanvasView.class.getDeclaredField("bodies");
            field.setAccessible(true);
            Object value=field.get(cad);
            return value instanceof List && !((List<?>)value).isEmpty();
        }catch(ReflectiveOperationException e){
            throw new IllegalStateException("Solid model presence could not be inspected",e);
        }
    }

    static CadProjectDocument.Decoded validate(Shapr3DGuideCadCanvasView cad,String raw){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        CadProjectDocument.Decoded decoded=CadProjectDocument.decode(raw);
        if(!cad.canImportSketchProjectState(decoded.sketchState))throw new IllegalArgumentException("Project Sketch is invalid");
        if(decoded.hasExactModel())ExactModelProjectAdapter.validateAgainstSketch(decoded.modelState,decoded.sketchState);
        return decoded;
    }

    static String restore(Shapr3DGuideCadCanvasView cad,String raw){
        CadProjectDocument.Decoded decoded=validate(cad,raw);
        String previous=encode(cad); // refuse destructive Open when current state cannot yet be persisted safely
        preflight(cad.getContext(),decoded);
        try{return restoreValidated(cad,decoded);}
        catch(RuntimeException failure){
            try{restoreValidated(cad,CadProjectDocument.decode(previous));}catch(Throwable ignored){}
            throw failure;
        }
    }

    private static void preflight(Context context,CadProjectDocument.Decoded decoded){
        Shapr3DGuideCadCanvasView scratch=new Shapr3DGuideCadCanvasView(context);
        try{restoreValidated(scratch,decoded);}
        finally{scratch.clearAll();}
    }

    private static String restoreValidated(Shapr3DGuideCadCanvasView cad,CadProjectDocument.Decoded decoded){
        cad.clearAll();
        String sketchStatus=cad.importSketchProjectState(decoded.sketchState);
        if(decoded.hasExactModel()){
            String modelStatus=ExactModelProjectAdapter.restoreModel(cad,decoded.modelState,decoded.sketchState);
            return sketchStatus+" • "+modelStatus;
        }
        return sketchStatus;
    }
}
