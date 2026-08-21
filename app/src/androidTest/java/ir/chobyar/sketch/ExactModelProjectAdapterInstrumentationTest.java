package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ExactModelProjectAdapterInstrumentationTest {

    private static <T> T onMain(java.util.concurrent.Callable<T> task)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result.set(task.call());}catch(Throwable t){error.set(t);}});
        Throwable t=error.get();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;if(t!=null)throw new RuntimeException(t);return result.get();
    }

    private static String sketch(int entityCount)throws Exception{
        JSONArray entities=new JSONArray();
        for(int i=0;i<entityCount;i++)entities.put(new JSONObject().put("type","LINE").put("layer","0").put("color",0xff222222)
                .put("extrusion",0).put("construction",false).put("referenceBodyId",-1).put("referenceEdgeIndex",-1).put("referenceEdgeKind",0)
                .put("x1",i*10).put("y1",0).put("x2",i*10+5).put("y2",0));
        return new JSONObject().put("schemaVersion",1).put("unit","mm").put("currentLayer","0").put("currentColor",0xff222222).put("polygonSides",6)
                .put("view",new JSONObject().put("scale",1).put("offsetX",100).put("offsetY",100).put("grid",true).put("axes",true).put("guides",true).put("dimensions",true).put("snap",true).put("ortho",false))
                .put("layers",new JSONArray().put(new JSONObject().put("name","0").put("visible",true))).put("entities",entities).toString();
    }

    @Test public void emptyLiveWorkspaceExportsLogicalModelWithoutNativeCaches() throws Exception {
        String raw=onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();
            Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
            return ExactModelProjectAdapter.exportModel(cad);
        });
        ExactModelProjectState.Decoded decoded=ExactModelProjectState.decode(raw);
        assertEquals(0,decoded.features.length());assertEquals(0,decoded.directEdits.length());
        assertFalse(raw.contains("subshapeIndex"));assertFalse(raw.contains("triangles"));assertFalse(raw.contains("\"handle\""));
        assertTrue(decoded.camera.optDouble("scale",0)>0);
    }

    @Test public void modelValidationRejectsFeatureSourceOutsideSavedSketch() throws Exception {
        JSONArray features=new JSONArray().put(ExactModelProjectState.feature(1,"EXTRUDE","B1",new JSONArray().put(4),new JSONObject().put("bodyId",1)));
        String model=ExactModelProjectState.encode(new JSONArray(),features,new JSONArray(),new JSONObject());
        try{ExactModelProjectAdapter.validateAgainstSketch(model,sketch(1));fail("out-of-range source must fail");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("outside"));}
    }

    @Test public void modelValidationRequiresBooleanDependenciesBeforeResult() throws Exception {
        JSONObject p=new JSONObject().put("bodyId",3).put("left","B1").put("right","B2").put("operation","UNION");
        JSONArray features=new JSONArray().put(ExactModelProjectState.feature(1,"BOOLEAN","B3",new JSONArray(),p));
        String model=ExactModelProjectState.encode(new JSONArray(),features,new JSONArray(),new JSONObject());
        try{ExactModelProjectAdapter.validateAgainstSketch(model,sketch(0));fail("unordered Boolean dependencies must fail");}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Boolean"));}
    }
}