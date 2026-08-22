package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Builds the two reference-derived furniture projects through production Sketch + History APIs. */
final class FurnitureSampleProjectFactory {
    private FurnitureSampleProjectFactory(){}

    static String createBoulderTable(Context context){
        Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
        try{
            cad.applyProjectSketchPlane(Geometry3D.xz());
            List<ProfileRow> rows=new ArrayList<>();
            rows.add(polyline(spheroidHalfProfile(205f,0f,175f,175f,28)));rows.add(axis(0f,0f,350f));
            rows.add(polyline(spheroidHalfProfile(135f,145f,385f,135f,28)));rows.add(axis(145f,250f,520f));
            rows.add(polyline(spheroidHalfProfile(165f,-35f,500f,125f,28)));rows.add(axis(-35f,375f,625f));
            rows.add(halfRect(0f,585f,380f,630f));rows.add(axis(0f,570f,645f));
            importRows(cad,rows);

            revolve(cad,0,1,"گوی پایین • Ø410 × H350");
            revolve(cad,2,3,"گوی میانی • Ø270 × H270 • X+145");
            revolve(cad,4,5,"گوی زیر صفحه • Ø330 × H250 • X-35");
            revolve(cad,6,7,"صفحه گرد • Ø760 × 45");
            cad.setStandardView("ISO");cad.fitAll();cad.clearWorkspaceSelection();
            return CadProjectPersistenceController.encode(cad);
        }finally{cad.clearAll();}
    }

    static String createHourglassTable(Context context){
        Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
        try{
            cad.applyProjectSketchPlane(Geometry3D.xz());
            List<ProfileRow> rows=new ArrayList<>();
            rows.add(polyline(hourglassProfile(1120f,725f,320f,24)));
            rows.add(rect(-1000f,725f,1000f,765f));
            importRows(cad,rows);
            extrude(cad,0,500f,"پایه ساعت‌شنی • 1120×500×725",0f,250f,0f);
            extrude(cad,1,900f,"صفحه • 2000×900×40",0f,450f,0f);
            cad.setStandardView("ISO");cad.fitAll();cad.clearWorkspaceSelection();
            return CadProjectPersistenceController.encode(cad);
        }finally{cad.clearAll();}
    }

    private static void revolve(Shapr3DGuideCadCanvasView cad,int entityIndex,int axisIndex,String name){
        CadCanvasView.Entity source=cad.entities.get(entityIndex);
        String result=cad.createRevolve(source,cad.entities.get(axisIndex),false,360f);
        if(!result.contains("ساخته شد"))throw new IllegalStateException(result);
        int bodyIndex=cad.bodyCount()-1;
        cad.renameItem(bodyIndex,name);
        cad.selectItem(bodyIndex);
    }

    private static void extrude(Shapr3DGuideCadCanvasView cad,int entityIndex,float heightMm,String name,float x,float y,float z){
        select(cad,cad.entities.get(entityIndex));
        String result=cad.extrudeSelectedBody(heightMm/10f);
        if(!result.contains("Body")&&!result.contains("Extrude"))throw new IllegalStateException(result);
        int bodyIndex=cad.bodyCount()-1;
        cad.renameItem(bodyIndex,name);
        cad.selectItem(bodyIndex);
        if((Math.abs(x)+Math.abs(y)+Math.abs(z))>1e-5f&&!cad.applyProjectBodyTransform(x,y,z,0f,0f,0f))
            throw new IllegalStateException("Extrude transform failed");
    }

    private static void select(Shapr3DGuideCadCanvasView cad,CadCanvasView.Entity entity){
        cad.selected=entity;cad.selectedObjects.clear();cad.selectedObjects.add(entity);
    }

    private static void importRows(Shapr3DGuideCadCanvasView cad,List<ProfileRow> rows){
        try{
            JSONObject root=new JSONObject();root.put("schemaVersion",1).put("unit","mm").put("currentLayer","0")
                    .put("currentColor",Color.rgb(25,25,25)).put("polygonSides",6);
            root.put("view",new JSONObject().put("scale",1).put("offsetX",120).put("offsetY",160)
                    .put("grid",true).put("axes",true).put("guides",true).put("dimensions",true).put("snap",true).put("ortho",false));
            root.put("layers",new JSONArray().put(new JSONObject().put("name","0").put("visible",true)));
            JSONArray entities=new JSONArray();for(ProfileRow row:rows)entities.put(row.json);root.put("entities",entities);
            String status=cad.importSketchProjectState(root.toString());if(!status.contains("باز شد"))throw new IllegalStateException(status);
        }catch(Exception e){throw new IllegalStateException("Sample Sketch could not be built",e);}
    }

    private static List<PointF> spheroidHalfProfile(float radius,float centerX,float centerY,float radiusY,int segments){
        List<PointF> p=new ArrayList<>();
        // A microscopic axis clearance keeps the closed wire valid for both the preview CSG
        // and OCCT's full-angle revolve (a zero-radius closing edge is rejected by OCCT).
        for(int i=0;i<=segments;i++){double a=Math.toRadians(-90d+180d*i/segments);float x=centerX+Math.max(.75f,radius*(float)Math.cos(a));p.add(new PointF(x,centerY+radiusY*(float)Math.sin(a)));}
        return p;
    }

    private static List<PointF> hourglassProfile(float width,float height,float waist,int segments){
        List<PointF> p=new ArrayList<>();float half=width/2f,neck=waist/2f;
        for(int i=0;i<=segments;i++){float y=height*i/segments,t=Math.abs(2f*y/height-1f);float x=neck+(half-neck)*(float)Math.pow(t,2.25);p.add(new PointF(-x,y));}
        for(int i=segments;i>=0;i--){float y=height*i/segments,t=Math.abs(2f*y/height-1f);float x=neck+(half-neck)*(float)Math.pow(t,2.25);p.add(new PointF(x,y));}
        return p;
    }

    private static ProfileRow polyline(List<PointF> points){
        try{JSONArray a=new JSONArray();for(PointF p:points)a.put(new JSONArray().put(p.x).put(p.y));return base(new JSONObject().put("type","POLYLINE").put("closed",true).put("points",a));}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow axis(float x,float y1,float y2){
        try{return base(new JSONObject().put("type","LINE").put("x1",x).put("y1",y1).put("x2",x).put("y2",y2),true);}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow halfRect(float axisX,float y1,float radius,float y2){
        try{JSONArray p=new JSONArray().put(new JSONArray().put(axisX+.75f).put(y1)).put(new JSONArray().put(axisX+radius).put(y1))
                .put(new JSONArray().put(axisX+radius).put(y2)).put(new JSONArray().put(axisX+.75f).put(y2));
            return base(new JSONObject().put("type","RECT").put("points",p));}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow rect(float x1,float y1,float x2,float y2){
        try{JSONArray p=new JSONArray().put(new JSONArray().put(x1).put(y1)).put(new JSONArray().put(x2).put(y1))
                .put(new JSONArray().put(x2).put(y2)).put(new JSONArray().put(x1).put(y2));
            return base(new JSONObject().put("type","RECT").put("points",p));}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow base(JSONObject json){
        return base(json,false);
    }

    private static ProfileRow base(JSONObject json,boolean construction){
        try{json.put("layer","0").put("color",Color.rgb(25,25,25)).put("extrusion",0).put("construction",construction)
                .put("referenceBodyId",-1).put("referenceEdgeIndex",-1).put("referenceEdgeKind",0);return new ProfileRow(json);}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static final class ProfileRow{final JSONObject json;ProfileRow(JSONObject json){this.json=json;}}
}
