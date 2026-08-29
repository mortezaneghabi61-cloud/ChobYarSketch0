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
            cad.applyProjectSketchPlane("Top",new Geometry3D.Plane3D(new Geometry3D.Vec3(0f,0f,585f),
                    new Geometry3D.Vec3(1f,0f,0f),new Geometry3D.Vec3(0f,1f,0f),"XY • Plane"));
            List<ProfileRow> rows=new ArrayList<>();
            rows.add(circle("Top",0f,0f,380f));
            importRows(cad,rows);

            sphere(cad,new Geometry3D.Vec3(0f,0f,205f),410f,"text text • Ø410");
            sphere(cad,new Geometry3D.Vec3(145f,0f,385f),270f,"text text • Ø270 • X+145");
            sphere(cad,new Geometry3D.Vec3(-35f,0f,500f),260f,"text text Plane • Ø260 • X-35");
            extrude(cad,0,45f,"Plane text • Ø760 × 45",0f,0f,0f);
            cad.setStandardView("ISO");cad.fitAll();cad.clearWorkspaceSelection();
            return CadProjectPersistenceController.encode(cad);
        }finally{cad.clearAll();}
    }

    static String createHourglassTable(Context context){
        Shapr3DGuideCadCanvasView cad=new Shapr3DGuideCadCanvasView(context);
        try{
            cad.applyProjectSketchPlane("0",depthPlane(250f,"XZ • text"));
            cad.applyProjectSketchPlane("Top",depthPlane(450f,"XZ • Plane"));
            List<ProfileRow> rows=new ArrayList<>();
            rows.add(polyline(hourglassProfile(1120f,725f,320f,24)));
            rows.add(rect("Top",-1000f,725f,1000f,765f));
            importRows(cad,rows);
            extrude(cad,0,500f,"text text • 1120×500×725",0f,0f,0f);
            extrude(cad,1,900f,"Plane • 2000×900×40",0f,0f,0f);
            cad.setStandardView("ISO");cad.fitAll();cad.clearWorkspaceSelection();
            return CadProjectPersistenceController.encode(cad);
        }finally{cad.clearAll();}
    }

    private static void sphere(Shapr3DGuideCadCanvasView cad,Geometry3D.Vec3 center,float diameterMm,String name){
        String result=cad.createProjectSphere(center,diameterMm);
        if(!result.contains("Sphere"))throw new IllegalStateException(result);
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
            root.put("layers",new JSONArray().put(new JSONObject().put("name","0").put("visible",true))
                    .put(new JSONObject().put("name","Top").put("visible",true)));
            JSONArray entities=new JSONArray();for(ProfileRow row:rows)entities.put(row.json);root.put("entities",entities);
            String status=cad.importSketchProjectState(root.toString());if(!status.contains("text text"))throw new IllegalStateException(status);
        }catch(Exception e){throw new IllegalStateException("Sample Sketch could not be built",e);}
    }

    private static List<PointF> hourglassProfile(float width,float height,float waist,int segments){
        List<PointF> p=new ArrayList<>();float half=width/2f,neck=waist/2f;
        for(int i=0;i<=segments;i++){float y=height*i/segments,t=Math.abs(2f*y/height-1f);float x=neck+(half-neck)*(float)Math.pow(t,2.25);p.add(new PointF(-x,y));}
        for(int i=segments;i>=0;i--){float y=height*i/segments,t=Math.abs(2f*y/height-1f);float x=neck+(half-neck)*(float)Math.pow(t,2.25);p.add(new PointF(x,y));}
        return p;
    }

    private static Geometry3D.Plane3D depthPlane(float originY,String label){
        return new Geometry3D.Plane3D(new Geometry3D.Vec3(0f,originY,0f),new Geometry3D.Vec3(1f,0f,0f),new Geometry3D.Vec3(0f,0f,1f),label);
    }

    private static ProfileRow polyline(List<PointF> points){
        try{JSONArray a=new JSONArray();for(PointF p:points)a.put(new JSONArray().put(p.x).put(p.y));return base(new JSONObject().put("type","POLYLINE").put("closed",true).put("points",a));}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow circle(String layer,float x,float y,float radius){
        try{return base(new JSONObject().put("type","CIRCLE").put("x",x).put("y",y).put("r",radius),false,layer);}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow rect(float x1,float y1,float x2,float y2){
        return rect("0",x1,y1,x2,y2);
    }

    private static ProfileRow rect(String layer,float x1,float y1,float x2,float y2){
        try{JSONArray p=new JSONArray().put(new JSONArray().put(x1).put(y1)).put(new JSONArray().put(x2).put(y1))
                .put(new JSONArray().put(x2).put(y2)).put(new JSONArray().put(x1).put(y2));
            return base(new JSONObject().put("type","RECT").put("points",p),false,layer);}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static ProfileRow base(JSONObject json){
        return base(json,false);
    }

    private static ProfileRow base(JSONObject json,boolean construction){
        return base(json,construction,"0");
    }

    private static ProfileRow base(JSONObject json,boolean construction,String layer){
        try{json.put("layer",layer).put("color",Color.rgb(25,25,25)).put("extrusion",0).put("construction",construction)
                .put("referenceBodyId",-1).put("referenceEdgeIndex",-1).put("referenceEdgeKind",0);return new ProfileRow(json);}
        catch(Exception e){throw new IllegalStateException(e);}
    }

    private static final class ProfileRow{final JSONObject json;ProfileRow(JSONObject json){this.json=json;}}
}
