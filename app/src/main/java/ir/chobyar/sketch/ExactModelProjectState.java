package ir.chobyar.sketch;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Logical exact-model payload stored inside a schema-v2 .chobyar document.
 *
 * This object deliberately contains only reconstructable CAD intent: sketch-plane
 * placement, ordered feature definitions and stable logical topology references.
 * Native OCCT handles, current sub-shape traversal indexes and display triangles
 * are process-local caches and are rejected if they appear in persisted JSON.
 */
final class ExactModelProjectState {
    static final int MODEL_VERSION=1;

    private static final Set<String> FEATURE_KINDS=new HashSet<>(Arrays.asList(
            "EXTRUDE","BOOLEAN","REVOLVE","SWEEP","LOFT","SPHERE"));
    private static final Set<String> DIRECT_KINDS=new HashSet<>(Arrays.asList(
            "FILLET","CHAMFER","PUSH_PULL","SHELL","MOVE","ROTATE","SCALE","MIRROR","PATTERN"));

    static final class Decoded {
        final int modelVersion;
        final JSONArray planes;
        final JSONArray features;
        final JSONArray directEdits;
        final JSONObject camera;
        Decoded(int modelVersion,JSONArray planes,JSONArray features,JSONArray directEdits,JSONObject camera){
            this.modelVersion=modelVersion;this.planes=planes;this.features=features;this.directEdits=directEdits;this.camera=camera;
        }
    }

    private ExactModelProjectState(){}

    static String encode(JSONArray planes,JSONArray features,JSONArray directEdits,JSONObject camera){
        try{
            JSONObject root=new JSONObject();
            root.put("modelVersion",MODEL_VERSION);
            root.put("planes",planes==null?new JSONArray():planes);
            root.put("features",features==null?new JSONArray():features);
            root.put("directEdits",directEdits==null?new JSONArray():directEdits);
            root.put("camera",camera==null?new JSONObject():camera);
            String encoded=root.toString();
            decode(encoded); // one validation path for writers and readers
            return encoded;
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Invalid exact model project state",e);}
    }

    static Decoded decode(String raw){
        if(raw==null||raw.trim().isEmpty())throw new IllegalArgumentException("Model state is empty");
        try{
            JSONObject root=new JSONObject(raw);
            rejectTransientKeys(root);
            int version=root.optInt("modelVersion",-1);
            if(version!=MODEL_VERSION)throw new IllegalArgumentException("Unsupported model state version");
            JSONArray planes=requireArray(root,"planes"),features=requireArray(root,"features"),direct=requireArray(root,"directEdits");
            JSONObject camera=root.optJSONObject("camera");if(camera==null)throw new IllegalArgumentException("Model camera is missing");
            validatePlanes(planes);validateFeatures(features);validateDirectEdits(direct);validateCamera(camera);
            return new Decoded(version,planes,features,direct,camera);
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Malformed exact model project state",e);}
    }

    static JSONObject plane(String layer,Geometry3D.Plane3D plane){
        if(layer==null||layer.trim().isEmpty()||plane==null)throw new IllegalArgumentException("Invalid sketch plane");
        try{return new JSONObject().put("layer",layer).put("label",plane.label)
                .put("origin",vec(plane.origin)).put("u",vec(plane.u)).put("v",vec(plane.v));}
        catch(Exception e){throw new IllegalArgumentException("Invalid sketch plane",e);}
    }

    static Geometry3D.Plane3D planeFromJson(JSONObject row){
        if(row==null)throw new IllegalArgumentException("Plane is missing");
        Geometry3D.Vec3 origin=vec(row.optJSONArray("origin")),u=vec(row.optJSONArray("u")),v=vec(row.optJSONArray("v"));
        if(u.length()<.999f||v.length()<.999f||Math.abs(u.normalized().dot(v.normalized()))>.0015f)
            throw new IllegalArgumentException("Plane basis is invalid");
        return new Geometry3D.Plane3D(origin,u,v,row.optString("label","Plane"));
    }

    static JSONObject feature(int id,String kind,String outputKey,JSONArray sourceEntityIndexes,JSONObject params){
        String k=upper(kind);if(id<1||!FEATURE_KINDS.contains(k)||outputKey==null||outputKey.trim().isEmpty())
            throw new IllegalArgumentException("Invalid feature");
        try{return new JSONObject().put("id",id).put("kind",k).put("output",outputKey)
                .put("sources",sourceEntityIndexes==null?new JSONArray():sourceEntityIndexes)
                .put("params",params==null?new JSONObject():params);}
        catch(Exception e){throw new IllegalArgumentException("Invalid feature",e);}
    }

    static JSONObject directEdit(int id,String kind,String bodyKey,double value,Geometry3D.Vec3 vector,OcctTopologyRef.Ref target){
        String k=upper(kind);if(id<1||!DIRECT_KINDS.contains(k)||bodyKey==null||bodyKey.trim().isEmpty()||!Double.isFinite(value))
            throw new IllegalArgumentException("Invalid direct edit");
        try{
            JSONObject row=new JSONObject().put("id",id).put("kind",k).put("body",bodyKey).put("value",value);
            if(vector!=null)row.put("vector",vec(vector));
            if(target!=null)row.put("target",topologyRef(target));
            return row;
        }catch(Exception e){throw new IllegalArgumentException("Invalid direct edit",e);}
    }

    static JSONObject topologyRef(OcctTopologyRef.Ref ref){
        if(ref==null)throw new IllegalArgumentException("Topology reference is missing");
        try{return new JSONObject().put("kind",ref.kind).put("id",ref.id).put("measure",ref.measure)
                .put("anchor",vec(ref.anchor)).put("vector",vec(ref.vector))
                .put("normal",new JSONArray().put(ref.nx).put(ref.ny).put(ref.nz))
                .put("signatureKind",ref.signatureKind).put("secondaryMeasure",ref.secondaryMeasure);}
        catch(Exception e){throw new IllegalArgumentException("Invalid topology reference",e);}
    }

    static OcctTopologyRef.Ref topologyRefFromJson(JSONObject row){
        if(row==null)throw new IllegalArgumentException("Topology reference is missing");
        int kind=row.optInt("kind",0);String id=row.optString("id","").trim();
        if((kind!=OcctTopologyRef.EDGE&&kind!=OcctTopologyRef.FACE)||id.isEmpty())throw new IllegalArgumentException("Invalid topology reference");
        Geometry3D.Vec3 anchor=vec(row.optJSONArray("anchor")),vector=vec(row.optJSONArray("vector"));
        JSONArray n=row.optJSONArray("normal");if(n==null||n.length()!=3)throw new IllegalArgumentException("Topology normal is missing");
        double measure=finite(row.optDouble("measure",Double.NaN)),secondary=finite(row.optDouble("secondaryMeasure",0));
        double nx=finite(n.optDouble(0,Double.NaN)),ny=finite(n.optDouble(1,Double.NaN)),nz=finite(n.optDouble(2,Double.NaN));
        return new OcctTopologyRef.Ref(kind,id,measure,anchor,vector,nx,ny,nz,row.optInt("signatureKind",0),secondary);
    }

    static JSONObject camera(boolean visible,float yaw,float pitch,float scale,float targetX,float targetY,float targetZ,float panX,float panY){
        try{return new JSONObject().put("visible",visible).put("yaw",yaw).put("pitch",pitch).put("scale",scale)
                .put("target",new JSONArray().put(targetX).put(targetY).put(targetZ))
                .put("pan",new JSONArray().put(panX).put(panY));}
        catch(Exception e){throw new IllegalArgumentException("Invalid camera",e);}
    }

    private static void validatePlanes(JSONArray rows)throws Exception{
        Set<String> layers=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String layer=row.optString("layer","").trim();
            if(layer.isEmpty()||!layers.add(layer))throw new IllegalArgumentException("Duplicate or empty plane layer");
            planeFromJson(row);
        }
    }

    private static void validateFeatures(JSONArray rows)throws Exception{
        Set<Integer> ids=new HashSet<>();Set<String> outputs=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);int id=row.optInt("id",0);String kind=upper(row.optString("kind",""));String output=row.optString("output","").trim();
            if(id<1||!ids.add(id)||!FEATURE_KINDS.contains(kind)||output.isEmpty()||!outputs.add(output))throw new IllegalArgumentException("Invalid feature graph");
            JSONArray sources=row.optJSONArray("sources");JSONObject params=row.optJSONObject("params");if(sources==null||params==null)throw new IllegalArgumentException("Feature source/params missing");
            for(int j=0;j<sources.length();j++)if(sources.getInt(j)<0)throw new IllegalArgumentException("Feature source index invalid");
        }
    }

    private static void validateDirectEdits(JSONArray rows)throws Exception{
        Set<Integer> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);int id=row.optInt("id",0);String kind=upper(row.optString("kind",""));
            if(id<1||!ids.add(id)||!DIRECT_KINDS.contains(kind)||row.optString("body","").trim().isEmpty())throw new IllegalArgumentException("Invalid direct edit graph");
            finite(row.optDouble("value",Double.NaN));
            JSONArray vector=row.optJSONArray("vector");if(vector!=null)vec(vector);
            JSONObject target=row.optJSONObject("target");if(target!=null)topologyRefFromJson(target);
            boolean topologyKind="FILLET".equals(kind)||"CHAMFER".equals(kind)||"PUSH_PULL".equals(kind)||"SHELL".equals(kind);
            if(topologyKind&&target==null)throw new IllegalArgumentException("Direct edit topology target missing");
        }
    }

    private static void validateCamera(JSONObject row)throws Exception{
        if(row.length()==0)return;
        finite(row.optDouble("yaw",0));finite(row.optDouble("pitch",0));double scale=finite(row.optDouble("scale",1));if(scale<=0)throw new IllegalArgumentException("Camera scale invalid");
        JSONArray target=row.optJSONArray("target"),pan=row.optJSONArray("pan");if(target!=null)vec(target);if(pan!=null){if(pan.length()!=2)throw new IllegalArgumentException("Camera pan invalid");finite(pan.getDouble(0));finite(pan.getDouble(1));}
    }

    private static void rejectTransientKeys(Object value)throws Exception{
        if(value instanceof JSONObject){JSONObject o=(JSONObject)value;java.util.Iterator<String> keys=o.keys();while(keys.hasNext()){
            String key=keys.next();if("handle".equalsIgnoreCase(key)||"subshapeIndex".equalsIgnoreCase(key)||"mesh".equalsIgnoreCase(key)||"triangles".equalsIgnoreCase(key))
                throw new IllegalArgumentException("Transient OCCT/display state cannot be persisted");rejectTransientKeys(o.get(key));
        }}else if(value instanceof JSONArray){JSONArray a=(JSONArray)value;for(int i=0;i<a.length();i++)rejectTransientKeys(a.get(i));}
    }

    private static JSONArray requireArray(JSONObject root,String key){JSONArray a=root.optJSONArray(key);if(a==null)throw new IllegalArgumentException("Model "+key+" is missing");return a;}
    private static JSONArray vec(Geometry3D.Vec3 v){
        if(v==null)throw new IllegalArgumentException("Vector is missing");
        try{return new JSONArray().put(v.x).put(v.y).put(v.z);}
        catch(Exception e){throw new IllegalArgumentException("Vector is invalid",e);}
    }
    private static Geometry3D.Vec3 vec(JSONArray a){if(a==null||a.length()!=3)throw new IllegalArgumentException("Vector is invalid");return new Geometry3D.Vec3((float)finite(a.optDouble(0,Double.NaN)),(float)finite(a.optDouble(1,Double.NaN)),(float)finite(a.optDouble(2,Double.NaN)));}
    private static double finite(double v){if(!Double.isFinite(v)||Math.abs(v)>1.0e12)throw new IllegalArgumentException("Non-finite model value");return v;}
    private static String upper(String v){return v==null?"":v.trim().toUpperCase(java.util.Locale.US);}
}
