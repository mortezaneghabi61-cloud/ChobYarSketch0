package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;

@RunWith(AndroidJUnit4.class)
public final class FurnitureSavedProjectsInstrumentationTest {
    @Test public void bundledFurnitureProjectsRestoreEditAndPersistInsideApp(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        final Throwable[] error={null};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(()->{
            try{
                InternalProjectRepository repo=new InternalProjectRepository(context);repo.ensureFurnitureSamples(context);
                assertTrue(repo.contains(InternalProjectRepository.BOULDER_TABLE_ID));
                assertTrue(repo.contains(InternalProjectRepository.HOURGLASS_TABLE_ID));

                Shapr3DGuideCadCanvasView boulder=new Shapr3DGuideCadCanvasView(context);
                CadProjectDocument.Decoded boulderDoc=CadProjectDocument.decode(repo.load(InternalProjectRepository.BOULDER_TABLE_ID));
                assertTrue(boulderDoc.hasExactModel());CadProjectPersistenceController.restore(boulder,boulderDocToRaw(repo,InternalProjectRepository.BOULDER_TABLE_ID));
                assertEquals(4,boulder.bodyCount());assertTrue(join(boulder.itemRows()).contains("گوی پایین"));assertTrue(join(boulder.itemRows()).contains("صفحه گرد"));
                capture(boulder,new File(output(context),"01-boulder-table-iso.png"),"ISO");
                capture(boulder,new File(output(context),"03-boulder-table-front.png"),"FRONT");
                assertTrue(boulder.selectItem(0).contains("انتخاب"));
                assertTrue(boulder.updateSelectedProjectSphere(new Geometry3D.Vec3(25f,0f,205f),410f).contains("به‌روز"));
                boulder.entities.get(0).scale(0f,175f,1.05f);assertTrue(boulder.rebuildHistory().contains("بازسازی"));
                String edited=CadProjectPersistenceController.encode(boulder);repo.save("user-boulder-edit-test","ویرایش میز سنگی",edited);
                assertEquals(25.0,sphereCenterX(edited),0.01);
                Shapr3DGuideCadCanvasView editedCanvas=new Shapr3DGuideCadCanvasView(context);CadProjectPersistenceController.restore(editedCanvas,repo.load("user-boulder-edit-test"));assertEquals(4,editedCanvas.bodyCount());
                String reopened=CadProjectPersistenceController.encode(editedCanvas);assertEquals(25.0,sphereCenterX(reopened),0.01);

                Shapr3DGuideCadCanvasView hourglass=new Shapr3DGuideCadCanvasView(context);
                CadProjectPersistenceController.restore(hourglass,repo.load(InternalProjectRepository.HOURGLASS_TABLE_ID));
                assertEquals(2,hourglass.bodyCount());String names=join(hourglass.itemRows());assertTrue(names.contains("1120×500×725"));assertTrue(names.contains("2000×900×40"));
                capture(hourglass,new File(output(context),"02-hourglass-table-iso.png"),"ISO");
                capture(hourglass,new File(output(context),"04-hourglass-table-front.png"),"FRONT");
            }catch(Throwable t){error[0]=t;}
        });
        if(error[0]!=null)throw new AssertionError(error[0]);
    }

    private static String boulderDocToRaw(InternalProjectRepository repo,String id){return repo.load(id);}
    private static double sphereCenterX(String raw)throws Exception{
        String model=CadProjectDocument.decode(raw).modelState;JSONArray features=new JSONObject(model).getJSONArray("features");
        for(int i=0;i<features.length();i++){JSONObject f=features.getJSONObject(i);if("SPHERE".equals(f.getString("kind")))return f.getJSONObject("params").getJSONArray("center").getDouble(0);}
        throw new AssertionError("Sphere feature missing");
    }
    private static String join(String[] rows){StringBuilder s=new StringBuilder();for(String row:rows)s.append(row).append('\n');return s.toString();}
    private static File output(Context context){File f=new File(context.getExternalFilesDir(null),"saved-furniture-validation");assertTrue(f.exists()||f.mkdirs());return f;}
    private static void capture(Shapr3DGuideCadCanvasView view,File file,String standardView)throws Exception{
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(1200,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(1000,android.view.View.MeasureSpec.EXACTLY));
        view.layout(0,0,1200,1000);view.setBodyAppearance(Color.rgb(224,217,201),true);view.setStandardView(standardView);view.fitAll();view.clearWorkspaceSelection();
        Bitmap bitmap=Bitmap.createBitmap(1200,1000,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);canvas.drawColor(Color.rgb(247,246,242));view.draw(canvas);
        try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}assertTrue(file.length()>1000L);
    }
}
