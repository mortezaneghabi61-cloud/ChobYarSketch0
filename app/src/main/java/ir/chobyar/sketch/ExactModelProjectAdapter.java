package ir.chobyar.sketch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Boundary between the live CAD object graph and the versioned project DTO.
 *
 * Reflection is confined to this model/persistence adapter because several
 * generations of the CAD prototype still keep their History records private.
 * Production UI code does not inspect model internals. The serialized form is
 * logical CAD intent only and is validated by {@link ExactModelProjectState}.
 */
final class ExactModelProjectAdapter {
    private ExactModelProjectAdapter(){}

    static String exportModel(Shapr3DGuideCadCanvasView cad){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        try{
            if(hasReferenceImage(cad))throw new IllegalStateException("Reference Image persistence is not implemented in model-v2 yet");
            if(hasManualCopies(cad))throw new IllegalStateException("Exact transform Copy persistence is not implemented in model-v2 yet");

            List<Object> entities=list(field(CadCanvasView.class,"entities").get(cad));
            IdentityHashMap<Object,Integer> entityIndex=new IdentityHashMap<>();
            for(int i=0;i<entities.size();i++)entityIndex.put(entities.get(i),i);

            JSONArray planes=exportPlanes(cad);
            JSONArray features=exportFeatures(cad,entityIndex);
            JSONArray direct=exportDirectEdits(cad);
            JSONObject camera=exportCamera(cad);
            return ExactModelProjectState.encode(planes,features,direct,camera);
        }catch(IllegalArgumentException|IllegalStateException e){throw e;}
        catch(Exception e){throw new IllegalStateException("Exact model snapshot could not be exported",e);}
    }

    static void validateAgainstSketch(String modelState,String sketchState){
        if(sketchState==null||sketchState.trim().isEmpty())throw new IllegalArgumentException("Sketch state is empty");
        try{
            int entityCount=new JSONObject(sketchState).getJSONArray("entities").length();
            ExactModelProjectState.Decoded model=ExactModelProjectState.decode(modelState);
            for(int i=0;i<model.features.length();i++){
                JSONArray sources=model.features.getJSONObject(i).getJSONArray("sources");
                for(int j=0;j<sources.length();j++){
                    int index=sources.getInt(j);
                    if(index<0||index>=entityCount)throw new IllegalArgumentException("Feature source is outside the saved Sketch");
                }
            }
            validateBodyDependencyOrder(model.features,model.directEdits);
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Model/Sketch project graph is invalid",e);}
    }

    private static JSONArray exportPlanes(Shapr3DGuideCadCanvasView cad)throws Exception{
        @SuppressWarnings("unchecked") Map<String,Geometry3D.Plane3D> map=(Map<String,Geometry3D.Plane3D>)field(SpatialCadCanvasView.class,"planeByLayer").get(cad);
        List<String> names=new ArrayList<>(map.keySet());Collections.sort(names);
        JSONArray out=new JSONArray();
        for(String name:names){Geometry3D.Plane3D plane=map.get(name);if(plane!=null)out.put(ExactModelProjectState.plane(name,plane));}
        return out;
    }

    private static JSONArray exportFeatures(Shapr3DGuideCadCanvasView cad,IdentityHashMap<Object,Integer> entityIndex)throws Exception{
        List<JSONObject> rows=new ArrayList<>();
        for(Object f:list(field(ParametricHistorySolidCadCanvasView.class,"history").get(cad))){
            JSONObject row=legacyFeature(f,entityIndex);if(row!=null)rows.add(row);
        }
        for(Object f:list(field(AdvancedParametricSolidCadCanvasView.class,"formHistory").get(cad))){
            JSONObject row=formFeature(f,entityIndex);if(row!=null)rows.add(row);
        }
        rows.sort(Comparator.comparingInt(ExactModelProjectAdapter::outputBodyNumber));
        JSONArray out=new JSONArray();
        int fileFeatureId=1;
        for(JSONObject row:rows){row.put("id",fileFeatureId++);out.put(row);}
        return out;
    }

    private static JSONObject legacyFeature(Object feature,IdentityHashMap<Object,Integer> entityIndex)throws Exception{
        String name=feature.getClass().getSimpleName();Object output=value(feature,"outputBody");
        if(output==null)return null;String key=bodyKey(output);
        JSONObject params=bodyParams(output);
        JSONArray sources=new JSONArray();
        if("ExtrudeFeature".equals(name)){
            for(Object source:list(value(feature,"sourceEntities")))sources.put(requireEntityIndex(entityIndex,source));
            params.put("sourceLayer",String.valueOf(value(feature,"sourceLayer")));
            params.put("heightMm",number(value(feature,"heightMm")));
            Geometry3D.Plane3D plane=(Geometry3D.Plane3D)value(feature,"plane");
            params.put("plane",ExactModelProjectState.plane(params.optString("sourceLayer","0"),plane));
            return ExactModelProjectState.feature(1,"EXTRUDE",key,sources,params);
        }
        if("BooleanFeature".equals(name)){
            params.put("operation",String.valueOf(value(feature,"operation")));
            params.put("left",bodyKey(value(feature,"leftBody")));
            params.put("right",bodyKey(value(feature,"rightBody")));
            params.put("keepLeft",bool(value(feature,"keepLeft")));
            params.put("keepRight",bool(value(feature,"keepRight")));
            return ExactModelProjectState.feature(1,"BOOLEAN",key,sources,params);
        }
        return null;
    }

    private static JSONObject formFeature(Object feature,IdentityHashMap<Object,Integer> entityIndex)throws Exception{
        String name=feature.getClass().getSimpleName();Object output=value(feature,"outputBody");
        if(output==null)return null;String key=bodyKey(output);JSONObject params=bodyParams(output);JSONArray sources=new JSONArray();
        if("RevolveFeature".equals(name)){
            Object profile=value(feature,"profileEntity"),axis=value(feature,"axisEntity");
            sources.put(requireEntityIndex(entityIndex,profile));sources.put(requireEntityIndex(entityIndex,axis));
            params.put("profileIndex",sources.getInt(0)).put("axisIndex",sources.getInt(1));
            params.put("xAxis",bool(value(feature,"xAxis"))).put("angleDeg",number(value(feature,"angleDeg"))).put("heightMm",number(value(feature,"heightMm")));
            Geometry3D.Plane3D plane=(Geometry3D.Plane3D)value(feature,"profilePlane");
            params.put("profilePlane",ExactModelProjectState.plane("profile",plane));
            return ExactModelProjectState.feature(1,"REVOLVE",key,sources,params);
        }
        if("SweepFeature".equals(name)){
            Object profile=value(feature,"profileEntity"),path=value(feature,"pathEntity");
            sources.put(requireEntityIndex(entityIndex,profile));sources.put(requireEntityIndex(entityIndex,path));
            params.put("profileIndex",sources.getInt(0)).put("pathIndex",sources.getInt(1));
            return ExactModelProjectState.feature(1,"SWEEP",key,sources,params);
        }
        if("LoftFeature".equals(name)){
            Object first=value(feature,"first"),second=value(feature,"second");
            sources.put(requireEntityIndex(entityIndex,first));sources.put(requireEntityIndex(entityIndex,second));
            params.put("firstIndex",sources.getInt(0)).put("secondIndex",sources.getInt(1));
            return ExactModelProjectState.feature(1,"LOFT",key,sources,params);
        }
        return null;
    }

    private static JSONArray exportDirectEdits(Shapr3DGuideCadCanvasView cad)throws Exception{
        JSONArray out=new JSONArray();
        for(Object entry:list(field(OcctStableCadCanvasView.class,"timeline").get(cad))){
            Object body=value(entry,"body"),edit=value(entry,"edit");if(body==null||edit==null)continue;
            int id=((Number)value(edit,"id")).intValue();String kind=String.valueOf(value(edit,"kind"));double amount=number(value(edit,"value"));
            Geometry3D.Vec3 vector=(Geometry3D.Vec3)value(edit,"vector");OcctTopologyRef.Ref target=(OcctTopologyRef.Ref)value(edit,"target");
            out.put(ExactModelProjectState.directEdit(id,kind,bodyKey(body),amount,vector,target));
        }
        return out;
    }

    private static JSONObject exportCamera(Shapr3DGuideCadCanvasView cad)throws Exception{
        boolean visible=bool(field(SpatialCadCanvasView.class,"overview3D").get(cad));
        float yaw=f(field(SpatialCadCanvasView.class,"viewYaw").get(cad));float pitch=f(field(SpatialCadCanvasView.class,"viewPitch").get(cad));
        float scale=f(field(SpatialCadCanvasView.class,"spatialScale").get(cad));Geometry3D.Vec3 target=(Geometry3D.Vec3)field(SpatialCadCanvasView.class,"viewTarget").get(cad);
        float panX=f(field(SpatialCadCanvasView.class,"viewPanX").get(cad)),panY=f(field(SpatialCadCanvasView.class,"viewPanY").get(cad));
        if(target==null)target=new Geometry3D.Vec3(0,0,0);
        return ExactModelProjectState.camera(visible,yaw,pitch,scale,target.x,target.y,target.z,panX,panY);
    }

    private static JSONObject bodyParams(Object body)throws Exception{
        return new JSONObject().put("bodyId",bodyNumber(body)).put("bodyName",String.valueOf(value(body,"name"))).put("visible",bool(value(body,"visible")));
    }

    private static void validateBodyDependencyOrder(JSONArray features,JSONArray direct)throws Exception{
        java.util.HashSet<String> known=new java.util.HashSet<>();
        for(int i=0;i<features.length();i++){
            JSONObject row=features.getJSONObject(i);String kind=row.getString("kind"),output=row.getString("output");JSONObject p=row.getJSONObject("params");
            if("BOOLEAN".equals(kind)){
                if(!known.contains(p.optString("left"))||!known.contains(p.optString("right")))throw new IllegalArgumentException("Boolean dependency is not available before its feature");
            }
            if(!known.add(output))throw new IllegalArgumentException("Duplicate Body output in model graph");
        }
        for(int i=0;i<direct.length();i++)if(!known.contains(direct.getJSONObject(i).getString("body")))throw new IllegalArgumentException("Direct edit targets an unknown Body");
    }

    private static boolean hasReferenceImage(Object cad)throws Exception{return field(SpatialCadCanvasView.class,"referenceImage").get(cad)!=null;}
    private static boolean hasManualCopies(Object cad)throws Exception{return !list(field(OcctStableCadCanvasView.class,"manualCopies").get(cad)).isEmpty();}
    private static int requireEntityIndex(IdentityHashMap<Object,Integer> map,Object entity){Integer i=map.get(entity);if(i==null)throw new IllegalStateException("History source is not present in the saved Sketch");return i;}
    private static int outputBodyNumber(JSONObject row){try{return row.getJSONObject("params").getInt("bodyId");}catch(Exception e){return Integer.MAX_VALUE;}}
    private static String bodyKey(Object body)throws Exception{return "B"+bodyNumber(body);}
    private static int bodyNumber(Object body)throws Exception{return ((Number)value(body,"id")).intValue();}

    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List?(List<Object>)value:Collections.emptyList();}
    private static Field field(Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object value(Object object,String name)throws Exception{Class<?> c=object.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f.get(object);}catch(NoSuchFieldException ignored){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static double number(Object v){return v instanceof Number?((Number)v).doubleValue():Double.parseDouble(String.valueOf(v));}
    private static float f(Object v){return v instanceof Number?((Number)v).floatValue():Float.parseFloat(String.valueOf(v));}
    private static boolean bool(Object v){return v instanceof Boolean&&(Boolean)v;}
}