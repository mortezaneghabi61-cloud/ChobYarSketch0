package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
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

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
public class ExactModelRestoreInstrumentationTest {

    private static <T> T onMain(Callable<T> task)throws Exception{
        AtomicReference<T> result=new AtomicReference<>();AtomicReference<Throwable> error=new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{try{result.set(task.call());}catch(Throwable t){error.set(t);}});
        Throwable t=error.get();if(t instanceof Exception)throw (Exception)t;if(t instanceof Error)throw (Error)t;if(t!=null)throw new RuntimeException(t);return result.get();
    }

    private static String sketch()throws Exception{
        JSONArray points=new JSONArray().put(new JSONArray().put(0).put(0)).put(new JSONArray().put(100).put(0))
                .put(new JSONArray().put(100).put(50)).put(new JSONArray().put(0).put(50));
        JSONObject rect=new JSONObject().put("type","RECT").put("points",points).put("layer","0").put("color",0xff8b5a2b)
                .put("extrusion",0).put("construction",false).put("referenceBodyId",-1).put("referenceEdgeIndex",-1).put("referenceEdgeKind",0);
        return new JSONObject().put("schemaVersion",1).put("unit","mm").put("currentLayer","0").put("currentColor",0xff202020).put("polygonSides",6)
                .put("view",new JSONObject().put("scale",1).put("offsetX",120).put("offsetY",180).put("grid",true).put("axes",true).put("guides",true).put("dimensions",true).put("snap",true).put("ortho",false))
                .put("layers",new JSONArray().put(new JSONObject().put("name","0").put("visible",true))).put("entities",new JSONArray().put(rect)).toString();
    }

    private static String model()throws Exception{
        JSONObject params=new JSONObject().put("bodyId",1).put("bodyName","Panel").put("visible",true)
                .put("sourceLayer","0").put("heightMm",18.0).put("plane",ExactModelProjectState.plane("0",Geometry3D.xy()));
        JSONArray features=new JSONArray().put(ExactModelProjectState.feature(1,"EXTRUDE","B1",new JSONArray().put(0),params));
        return ExactModelProjectState.encode(new JSONArray().put(ExactModelProjectState.plane("0",Geometry3D.xy())),features,new JSONArray(),new JSONObject());
    }

    @Test public void singleExtrudeRestoresAndReExportsLogicalIntent() throws Exception {
        JSONObject exported=onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
            assertTrue(cad.importSketchProjectState(sketch()).contains("1"));
            String status=ExactModelProjectAdapter.restoreModel(cad,model(),sketch());assertTrue(status.contains("1 features"));assertTrue(cad.hasAnySolidBody());
            return new JSONObject(ExactModelProjectAdapter.exportModel(cad));
        });
        JSONArray features=exported.getJSONArray("features");assertEquals(1,features.length());
        JSONObject feature=features.getJSONObject(0);assertEquals("EXTRUDE",feature.getString("kind"));assertEquals("B1",feature.getString("output"));
        assertEquals(18.0,feature.getJSONObject("params").getDouble("heightMm"),0.0001);assertEquals(0,feature.getJSONArray("sources").getInt(0));
    }

    @Test public void restoreRefusesToOverlayExisting3DGraph() throws Exception {
        String message=onMain(()->{
            Context context=ApplicationProvider.getApplicationContext();Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);cad.importSketchProjectState(sketch());
            ExactModelProjectAdapter.restoreModel(cad,model(),sketch());
            try{ExactModelProjectAdapter.restoreModel(cad,model(),sketch());fail("second restore must not overlay live 3D state");return "";}
            catch(IllegalStateException expected){return expected.getMessage();}
        });
        assertTrue(message.contains("fresh 3D workspace"));
    }
}