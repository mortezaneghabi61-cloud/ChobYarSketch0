package ir.chobyar.sketch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
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

    /**
     * Rebuild model-v2 into a canvas whose Sketch shell has already been restored.
     * The caller must provide a fresh/3D-empty workspace. Structural validation is
     * completed before the first model mutation; native handles are regenerated.
     */
    static String restoreModel(Shapr3DGuideCadCanvasView cad,String modelState,String sketchState){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        validateAgainstSketch(modelState,sketchState);
        try{
            if(!list(field(SolidCadCanvasView.class,"bodies").get(cad)).isEmpty()
                    ||!list(field(ParametricHistorySolidCadCanvasView.class,"history").get(cad)).isEmpty()
                    ||!list(field(AdvancedParametricSolidCadCanvasView.class,"formHistory").get(cad)).isEmpty()
                    ||!list(field(OcctStableCadCanvasView.class,"timeline").get(cad)).isEmpty())
                throw new IllegalStateException("Model restore requires a fresh 3D workspace");

            ExactModelProjectState.Decoded model=ExactModelProjectState.decode(modelState);
            List<Object> entities=list(field(CadCanvasView.class,"entities").get(cad));
            restorePlanes(cad,model.planes);

            Map<String,Object> bodiesByKey=new HashMap<>();
            int restored=0;
            for(int i=0;i<model.features.length();i++){
                JSONObject row=model.features.getJSONObject(i);JSONObject params=row.getJSONObject("params");
                String kind=row.getString("kind"),output=row.getString("output");int bodyId=params.getInt("bodyId");
                setBodySerial(cad,bodyId);
                Object body;
                if("EXTRUDE".equals(kind)){
                    selectSources(cad,entities,row.getJSONArray("sources"));
                    cad.extrudeSelectedBody((float)(params.getDouble("heightMm")/10.0));
                    body=selectedBody(cad);
                }else if("REVOLVE".equals(kind)){
                    Object profile=entities.get(params.getInt("profileIndex")),axis=entities.get(params.getInt("axisIndex"));
                    cad.createRevolve(profile,axis,params.optBoolean("xAxis",true),(float)params.getDouble("angleDeg"),(float)params.optDouble("heightMm",0));
                    body=selectedBody(cad);
                }else if("SWEEP".equals(kind)){
                    cad.createSweep(entities.get(params.getInt("profileIndex")),entities.get(params.getInt("pathIndex")));
                    body=selectedBody(cad);
                }else if("LOFT".equals(kind)){
                    cad.createLoft(entities.get(params.getInt("firstIndex")),entities.get(params.getInt("secondIndex")));
                    body=selectedBody(cad);
                }else if("SPHERE".equals(kind)){
                    JSONArray c=params.getJSONArray("center");
                    cad.createProjectSphere(new Geometry3D.Vec3((float)c.getDouble(0),(float)c.getDouble(1),(float)c.getDouble(2)),
                            (float)(params.getDouble("radiusMm")*2.0));
                    body=selectedBody(cad);
                }else if("BOOLEAN".equals(kind)){
                    Object left=bodiesByKey.get(params.getString("left")),right=bodiesByKey.get(params.getString("right"));
                    List<Object> current=list(field(SolidCadCanvasView.class,"bodies").get(cad));
                    int leftIndex=current.indexOf(left),rightIndex=current.indexOf(right);
                    if(leftIndex<0||rightIndex<0)throw new IllegalStateException("Boolean input Body is no longer available");
                    cad.applyHistoryBooleanByIndex(params.getString("operation"),leftIndex+1,rightIndex+1,params.optBoolean("keepLeft"),params.optBoolean("keepRight"));
                    body=selectedBody(cad);
                }else throw new IllegalArgumentException("Unsupported persisted feature "+kind);

                if(body==null||bodyNumber(body)!=bodyId)throw new IllegalStateException("Feature rebuild did not produce expected Body "+output);
                restoreBodyParams(body,params);bodiesByKey.put(output,body);restored++;
            }

            restoreDirectEdits(cad,model.directEdits,bodiesByKey);
            restoreCamera(cad,model.camera);
            clearSelection(cad);cad.invalidate();
            return "Exact model restored • "+restored+" features • "+model.directEdits.length()+" direct edits";
        }catch(IllegalArgumentException|IllegalStateException e){throw e;}
        catch(Exception e){throw new IllegalStateException("Exact model rebuild failed",e);}
    }

    static void validateAgainstSketch(String modelState,String sketchState){
        if(sketchState==null||sketchState.trim().isEmpty())throw new IllegalArgumentException("Sketch state is empty");
        try{
            int entityCount=new JSONObject(sketchState).getJSONArray("entities").length();
            ExactModelProjectState.Decoded model=ExactModelProjectState.decode(modelState);
            int previousBodyId=0;
            for(int i=0;i<model.features.length();i++){
                JSONObject feature=model.features.getJSONObject(i);JSONArray sources=feature.getJSONArray("sources");JSONObject params=feature.getJSONObject("params");
                for(int j=0;j<sources.length();j++){
                    int index=sources.getInt(j);
                    if(index<0||index>=entityCount)throw new IllegalArgumentException("Feature source is outside the saved Sketch");
                }
                int bodyId=params.optInt("bodyId",-1);if(bodyId<=previousBodyId)throw new IllegalArgumentException("Feature Body IDs must be positive and ordered");previousBodyId=bodyId;
                validateFeatureParams(feature,entityCount);
            }
            validateBodyDependencyOrder(model.features,model.directEdits);
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Model/Sketch project graph is invalid",e);}
    }

    private static void validateFeatureParams(JSONObject feature,int entityCount)throws Exception{
        String kind=feature.getString("kind");JSONObject p=feature.getJSONObject("params");
        if("EXTRUDE".equals(kind))finite(p.getDouble("heightMm"));
        else if("REVOLVE".equals(kind)){entityIndex(p,"profileIndex",entityCount);entityIndex(p,"axisIndex",entityCount);finite(p.getDouble("angleDeg"));finite(p.optDouble("heightMm",0));}
        else if("SPHERE".equals(kind)){JSONArray c=p.getJSONArray("center");if(c.length()!=3||p.getDouble("radiusMm")<=0)throw new IllegalArgumentException("Sphere parameters are invalid");for(int i=0;i<3;i++)finite(c.getDouble(i));}
        else if("SWEEP".equals(kind)){entityIndex(p,"profileIndex",entityCount);entityIndex(p,"pathIndex",entityCount);}
        else if("LOFT".equals(kind)){entityIndex(p,"firstIndex",entityCount);entityIndex(p,"secondIndex",entityCount);}
        else if("BOOLEAN".equals(kind)){
            String op=p.optString("operation","");if(!"UNION".equals(op)&&!"SUBTRACT".equals(op)&&!"INTERSECT".equals(op))throw new IllegalArgumentException("Boolean operation is invalid");
            if(p.optString("left","").isEmpty()||p.optString("right","").isEmpty())throw new IllegalArgumentException("Boolean Body dependency is missing");
        }
    }

    private static void entityIndex(JSONObject p,String key,int count)throws Exception{int i=p.getInt(key);if(i<0||i>=count)throw new IllegalArgumentException("Feature entity index is invalid");}

    private static JSONArray exportPlanes(Shapr3DGuideCadCanvasView cad)throws Exception{
        @SuppressWarnings("unchecked") Map<String,Geometry3D.Plane3D> map=(Map<String,Geometry3D.Plane3D>)field(SpatialCadCanvasView.class,"planeByLayer").get(cad);
        List<String> names=new ArrayList<>(map.keySet());Collections.sort(names);
        JSONArray out=new JSONArray();
        for(String name:names){Geometry3D.Plane3D plane=map.get(name);if(plane!=null)out.put(ExactModelProjectState.plane(name,plane));}
        return out;
    }

    private static JSONArray exportFeatures(Shapr3DGuideCadCanvasView cad,IdentityHashMap<Object,Integer> entityIndex)throws Exception{
        List<JSONObject> rows=new ArrayList<>();
        for(Object f:list(field(ParametricHistorySolidCadCanvasView.class,"history").get(cad))){JSONObject row=legacyFeature(f,entityIndex);if(row!=null)rows.add(row);}
        for(Object f:list(field(AdvancedParametricSolidCadCanvasView.class,"formHistory").get(cad))){JSONObject row=formFeature(f,entityIndex);if(row!=null)rows.add(row);}
        @SuppressWarnings("unchecked") Map<Object,AnalyticSolidKernel.Primitive> analytic=
                (Map<Object,AnalyticSolidKernel.Primitive>)field(AnalyticCadCanvasView.class,"analyticByBody").get(cad);
        for(Map.Entry<Object,AnalyticSolidKernel.Primitive> entry:analytic.entrySet()){
            AnalyticSolidKernel.Primitive primitive=entry.getValue();
            if(!(primitive instanceof AnalyticSolidKernel.Sphere))continue;
            AnalyticSolidKernel.Sphere sphere=(AnalyticSolidKernel.Sphere)primitive;JSONObject params=bodyParams(entry.getKey());
            params.put("center",new JSONArray().put(sphere.center.x).put(sphere.center.y).put(sphere.center.z)).put("radiusMm",sphere.radiusMm);
            rows.add(ExactModelProjectState.feature(1,"SPHERE",bodyKey(entry.getKey()),new JSONArray(),params));
        }
        rows.sort(Comparator.comparingInt(ExactModelProjectAdapter::outputBodyNumber));
        JSONArray out=new JSONArray();int fileFeatureId=1;for(JSONObject row:rows){row.put("id",fileFeatureId++);out.put(row);}return out;
    }

    private static JSONObject legacyFeature(Object feature,IdentityHashMap<Object,Integer> entityIndex)throws Exception{
        String name=feature.getClass().getSimpleName();Object output=value(feature,"outputBody");if(output==null)return null;String key=bodyKey(output);JSONObject params=bodyParams(output);JSONArray sources=new JSONArray();
        if("ExtrudeFeature".equals(name)){
            for(Object source:list(value(feature,"sourceEntities")))sources.put(requireEntityIndex(entityIndex,source));
            params.put("sourceLayer",String.valueOf(value(feature,"sourceLayer"))).put("heightMm",number(value(feature,"heightMm")));
            Geometry3D.Plane3D plane=(Geometry3D.Plane3D)value(feature,"plane");params.put("plane",ExactModelProjectState.plane(params.optString("sourceLayer","0"),plane));
            return ExactModelProjectState.feature(1,"EXTRUDE",key,sources,params);
        }
        if("BooleanFeature".equals(name)){
            params.put("operation",String.valueOf(value(feature,"operation"))).put("left",bodyKey(value(feature,"leftBody"))).put("right",bodyKey(value(feature,"rightBody")))
                    .put("keepLeft",bool(value(feature,"keepLeft"))).put("keepRight",bool(value(feature,"keepRight")));
            return ExactModelProjectState.feature(1,"BOOLEAN",key,sources,params);
        }
        return null;
    }

    private static JSONObject formFeature(Object feature,IdentityHashMap<Object,Integer> entityIndex)throws Exception{
        String name=feature.getClass().getSimpleName();Object output=value(feature,"outputBody");if(output==null)return null;String key=bodyKey(output);JSONObject params=bodyParams(output);JSONArray sources=new JSONArray();
        if("RevolveFeature".equals(name)){
            Object profile=value(feature,"profileEntity"),axis=value(feature,"axisEntity");sources.put(requireEntityIndex(entityIndex,profile));sources.put(requireEntityIndex(entityIndex,axis));
            params.put("profileIndex",sources.getInt(0)).put("axisIndex",sources.getInt(1)).put("xAxis",bool(value(feature,"xAxis"))).put("angleDeg",number(value(feature,"angleDeg"))).put("heightMm",number(value(feature,"heightMm")));
            Geometry3D.Plane3D plane=(Geometry3D.Plane3D)value(feature,"profilePlane");params.put("profilePlane",ExactModelProjectState.plane("profile",plane));
            return ExactModelProjectState.feature(1,"REVOLVE",key,sources,params);
        }
        if("SweepFeature".equals(name)){
            Object profile=value(feature,"profileEntity"),path=value(feature,"pathEntity");sources.put(requireEntityIndex(entityIndex,profile));sources.put(requireEntityIndex(entityIndex,path));
            params.put("profileIndex",sources.getInt(0)).put("pathIndex",sources.getInt(1));return ExactModelProjectState.feature(1,"SWEEP",key,sources,params);
        }
        if("LoftFeature".equals(name)){
            Object first=value(feature,"first"),second=value(feature,"second");sources.put(requireEntityIndex(entityIndex,first));sources.put(requireEntityIndex(entityIndex,second));
            params.put("firstIndex",sources.getInt(0)).put("secondIndex",sources.getInt(1));return ExactModelProjectState.feature(1,"LOFT",key,sources,params);
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
        float yaw=f(field(SpatialCadCanvasView.class,"cameraYaw").get(cad));
        float pitch=f(field(SpatialCadCanvasView.class,"cameraPitch").get(cad));
        float scale=f(field(SpatialCadCanvasView.class,"spatialScale").get(cad));
        float targetX=f(field(SpatialCadCanvasView.class,"cameraTargetX").get(cad));
        float targetY=f(field(SpatialCadCanvasView.class,"cameraTargetY").get(cad));
        float targetZ=f(field(SpatialCadCanvasView.class,"cameraTargetZ").get(cad));
        float panX=f(field(SpatialCadCanvasView.class,"cameraPanX").get(cad));
        float panY=f(field(SpatialCadCanvasView.class,"cameraPanY").get(cad));
        return ExactModelProjectState.camera(visible,yaw,pitch,scale,targetX,targetY,targetZ,panX,panY);
    }

    @SuppressWarnings("unchecked") private static void restorePlanes(Shapr3DGuideCadCanvasView cad,JSONArray rows)throws Exception{
        Map<String,Geometry3D.Plane3D> map=(Map<String,Geometry3D.Plane3D>)field(SpatialCadCanvasView.class,"planeByLayer").get(cad);map.clear();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);map.put(row.getString("layer"),ExactModelProjectState.planeFromJson(row));}
        String layer=String.valueOf(field(CadCanvasView.class,"currentLayer").get(cad));Geometry3D.Plane3D active=map.get(layer);if(active==null)active=Geometry3D.xy();
        field(SpatialCadCanvasView.class,"activePlane").set(cad,active);
    }

    private static void restoreCamera(Shapr3DGuideCadCanvasView cad,JSONObject camera)throws Exception{
        if(camera==null||camera.length()==0)return;JSONArray target=camera.optJSONArray("target"),pan=camera.optJSONArray("pan");
        field(SpatialCadCanvasView.class,"overview3D").setBoolean(cad,camera.optBoolean("visible",true));
        field(SpatialCadCanvasView.class,"cameraYaw").setFloat(cad,(float)camera.optDouble("yaw",218));
        field(SpatialCadCanvasView.class,"cameraPitch").setFloat(cad,(float)camera.optDouble("pitch",24));
        field(SpatialCadCanvasView.class,"spatialScale").setFloat(cad,(float)camera.optDouble("scale",1));
        if(target!=null){
            field(SpatialCadCanvasView.class,"cameraTargetX").setFloat(cad,(float)target.getDouble(0));
            field(SpatialCadCanvasView.class,"cameraTargetY").setFloat(cad,(float)target.getDouble(1));
            field(SpatialCadCanvasView.class,"cameraTargetZ").setFloat(cad,(float)target.getDouble(2));
        }
        if(pan!=null){
            field(SpatialCadCanvasView.class,"cameraPanX").setFloat(cad,(float)pan.getDouble(0));
            field(SpatialCadCanvasView.class,"cameraPanY").setFloat(cad,(float)pan.getDouble(1));
        }
    }

    private static void restoreDirectEdits(Shapr3DGuideCadCanvasView cad,JSONArray rows,Map<String,Object> bodiesByKey)throws Exception{
        if(rows.length()==0)return;
        Class<?> kindClass=Class.forName("ir.chobyar.sketch.OcctStableCadCanvasView$Kind");Class<?> editClass=Class.forName("ir.chobyar.sketch.OcctStableCadCanvasView$StableEdit");
        Constructor<?> ctor=editClass.getDeclaredConstructor(int.class,kindClass,double.class,Geometry3D.Vec3.class,OcctTopologyRef.Ref.class);ctor.setAccessible(true);
        Method record=OcctStableCadCanvasView.class.getDeclaredMethod("recordStable",Object.class,editClass);record.setAccessible(true);int max=0;
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);Object body=bodiesByKey.get(row.getString("body"));if(body==null)throw new IllegalStateException("Direct edit Body is missing");
            @SuppressWarnings({"rawtypes","unchecked"}) Object kind=Enum.valueOf((Class)kindClass,row.getString("kind"));
            Geometry3D.Vec3 vector=null;JSONArray v=row.optJSONArray("vector");if(v!=null)vector=new Geometry3D.Vec3((float)v.getDouble(0),(float)v.getDouble(1),(float)v.getDouble(2));
            OcctTopologyRef.Ref target=row.has("target")?ExactModelProjectState.topologyRefFromJson(row.getJSONObject("target")):null;int id=row.getInt("id");
            Object edit=ctor.newInstance(id,kind,row.getDouble("value"),vector,target);record.invoke(cad,body,edit);max=Math.max(max,id);
        }
        field(OcctStableCadCanvasView.class,"directSerial").setInt(cad,max+1);
    }

    private static void selectSources(Shapr3DGuideCadCanvasView cad,List<Object> entities,JSONArray indexes)throws Exception{
        List<Object> selected=list(field(SmartCadCanvasView.class,"selectedObjects").get(cad));selected.clear();
        for(int i=0;i<indexes.length();i++)selected.add(entities.get(indexes.getInt(i)));
        field(CadCanvasView.class,"selected").set(cad,selected.isEmpty()?null:selected.get(0));
    }

    private static void clearSelection(Shapr3DGuideCadCanvasView cad)throws Exception{
        list(field(SmartCadCanvasView.class,"selectedObjects").get(cad)).clear();field(CadCanvasView.class,"selected").set(cad,null);
        field(SolidCadCanvasView.class,"selectedBody").set(cad,null);field(SolidCadCanvasView.class,"selectedFace").set(cad,null);
    }

    private static void setBodySerial(Shapr3DGuideCadCanvasView cad,int id)throws Exception{if(id<1)throw new IllegalArgumentException("Body ID is invalid");field(SolidCadCanvasView.class,"bodySerial").setInt(cad,id);}
    private static Object selectedBody(Shapr3DGuideCadCanvasView cad)throws Exception{return field(SolidCadCanvasView.class,"selectedBody").get(cad);}
    private static void restoreBodyParams(Object body,JSONObject p)throws Exception{valueField(body,"name").set(body,p.optString("bodyName","Body "+p.optInt("bodyId")));valueField(body,"visible").setBoolean(body,p.optBoolean("visible",true));}
    private static JSONObject bodyParams(Object body)throws Exception{return new JSONObject().put("bodyId",bodyNumber(body)).put("bodyName",String.valueOf(value(body,"name"))).put("visible",bool(value(body,"visible")));}

    private static void validateBodyDependencyOrder(JSONArray features,JSONArray direct)throws Exception{
        java.util.HashSet<String> known=new java.util.HashSet<>();
        for(int i=0;i<features.length();i++){
            JSONObject row=features.getJSONObject(i);String kind=row.getString("kind"),output=row.getString("output");JSONObject p=row.getJSONObject("params");
            if("BOOLEAN".equals(kind)&&(!known.contains(p.optString("left"))||!known.contains(p.optString("right"))))throw new IllegalArgumentException("Boolean dependency is not available before its feature");
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
    private static double finite(double v){if(!Double.isFinite(v)||Math.abs(v)>1e12)throw new IllegalArgumentException("Feature value is invalid");return v;}

    @SuppressWarnings("unchecked") private static List<Object> list(Object value){return value instanceof List?(List<Object>)value:Collections.emptyList();}
    private static Field field(Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Field valueField(Object object,String name)throws Exception{Class<?> c=object.getClass();while(c!=null){try{Field f=c.getDeclaredField(name);f.setAccessible(true);return f;}catch(NoSuchFieldException ignored){c=c.getSuperclass();}}throw new NoSuchFieldException(name);}
    private static Object value(Object object,String name)throws Exception{return valueField(object,name).get(object);}
    private static double number(Object v){return v instanceof Number?((Number)v).doubleValue():Double.parseDouble(String.valueOf(v));}
    private static float f(Object v){return v instanceof Number?((Number)v).floatValue():Float.parseFloat(String.valueOf(v));}
    private static boolean bool(Object v){return v instanceof Boolean&&(Boolean)v;}
}
