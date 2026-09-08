package ir.chobyar.sketch.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Transactional source of truth for construction planes and Sketch-to-plane relationships. */
public final class ConstructionPlaneDocument {
    public enum DeleteResult { DELETED, NOT_FOUND, BUILT_IN, IN_USE, HAS_DERIVED_PLANES }

    private static final int MAX_HISTORY=80;
    private final LinkedHashMap<String,ConstructionPlane> planes=new LinkedHashMap<>();
    private final LinkedHashMap<String,String> sketchPlaneIds=new LinkedHashMap<>();
    private final ArrayDeque<Snapshot> undo=new ArrayDeque<>(),redo=new ArrayDeque<>();
    private String activeSketchId;
    private String activePlaneId=ConstructionPlane.XY_ID;
    private long nextOffsetSerial=1,revision;

    public ConstructionPlaneDocument(){installBuiltIns();}

    public synchronized long revision(){return revision;}
    public synchronized boolean canUndo(){return !undo.isEmpty();}
    public synchronized boolean canRedo(){return !redo.isEmpty();}
    public synchronized void clearHistory(){undo.clear();redo.clear();}
    public synchronized void reset(String initialSketchId){planes.clear();sketchPlaneIds.clear();installBuiltIns();activeSketchId=null;activePlaneId=ConstructionPlane.XY_ID;nextOffsetSerial=1;undo.clear();redo.clear();revision++;if(initialSketchId!=null&&!initialSketchId.trim().isEmpty()){assignSketchWithoutHistory(initialSketchId,ConstructionPlane.XY_ID);revision++;}}
    public synchronized boolean containsPlane(String id){return planes.containsKey(clean(id));}
    public synchronized ConstructionPlane plane(String id){return planes.get(clean(id));}
    public synchronized List<ConstructionPlane> planes(){return Collections.unmodifiableList(new ArrayList<>(planes.values()));}
    public synchronized Map<String,String> sketchPlaneAssignments(){return Collections.unmodifiableMap(new LinkedHashMap<>(sketchPlaneIds));}
    public synchronized Map<String,String> undoSketchPlaneAssignments(){return undo.isEmpty()?null:Collections.unmodifiableMap(new LinkedHashMap<>(undo.peekLast().assignments));}
    public synchronized Map<String,String> redoSketchPlaneAssignments(){return redo.isEmpty()?null:Collections.unmodifiableMap(new LinkedHashMap<>(redo.peekLast().assignments));}
    public synchronized String activePlaneId(){return activePlaneId;}
    public synchronized String activeSketchId(){return activeSketchId;}
    public synchronized long nextOffsetSerial(){return nextOffsetSerial;}
    public synchronized ConstructionPlane activePlane(){ConstructionPlane p=planes.get(activePlaneId);if(p==null)throw new IllegalStateException("Active construction plane is missing");return p;}
    public synchronized String planeIdForSketch(String sketchId){return sketchPlaneIds.get(clean(sketchId));}

    public synchronized ConstructionPlane createOffsetPlane(String sourceId,double distanceMm,String displayName){
        ConstructionPlane source=requirePlane(sourceId);
        if(!Double.isFinite(distanceMm)||Math.abs(distanceMm)>1.0e12)throw new IllegalArgumentException("Offset distance must be finite");
        Snapshot before=snapshot();
        long serial=nextAvailableOffsetSerial();String id="plane:offset:"+serial;
        String name=displayName==null||displayName.trim().isEmpty()?"Offset Plane "+serial:displayName.trim();
        ConstructionPlane.Vector origin=source.origin.plus(source.normal.times(distanceMm));
        ConstructionPlane plane=ConstructionPlane.offset(id,name,source.id,distanceMm,origin,source.uAxis,source.vAxis,source.normal,true,nextCreationOrder());
        nextOffsetSerial=serial+1;planes.put(id,plane);activePlaneId=id;commit(before);return plane;
    }

    /** One transaction for the interaction intent: create an offset and a Sketch bound to it. */
    public synchronized ConstructionPlane createOffsetPlaneWithSketch(String sourceId,double distanceMm,String displayName,String sketchId){
        if(clean(sketchId).isEmpty())throw new IllegalArgumentException("Sketch id is empty");Snapshot before=snapshot();
        ConstructionPlane plane=createOffsetPlaneWithoutHistory(sourceId,distanceMm,displayName);
        assignSketchWithoutHistory(sketchId,plane.id);commit(before);return plane;
    }

    public synchronized void createSketchOnPlane(String sketchId,String planeId){Snapshot before=snapshot();assignSketchWithoutHistory(sketchId,planeId);commit(before);}
    public synchronized void assignSketch(String sketchId,String planeId){createSketchOnPlane(sketchId,planeId);}

    public synchronized ConstructionPlane createReferencePlaneWithSketch(String id,String name,ConstructionPlane.Vector origin,
                                                                          ConstructionPlane.Vector u,ConstructionPlane.Vector v,String sketchId){
        String key=clean(id);if(key.isEmpty()||planes.containsKey(key))throw new IllegalArgumentException("Duplicate or empty construction plane id");
        if(clean(sketchId).isEmpty())throw new IllegalArgumentException("Sketch id is empty");
        Snapshot before=snapshot();ConstructionPlane p=ConstructionPlane.reference(key,name,origin,u,v,u.cross(v).normalized(),true,nextCreationOrder());
        planes.put(key,p);assignSketchWithoutHistory(sketchId,key);commit(before);return p;
    }

    public synchronized boolean activateSketch(String sketchId){
        String id=clean(sketchId),planeId=sketchPlaneIds.get(id);if(planeId==null)return false;
        activeSketchId=id;activePlaneId=planeId;revision++;return true;
    }

    /** Activate construction context without changing the active Sketch or creating history. */
    public synchronized boolean activatePlane(String planeId){
        String id=clean(planeId);if(!planes.containsKey(id)||id.equals(activePlaneId))return false;
        activePlaneId=id;revision++;return true;
    }

    public synchronized boolean renamePlane(String id,String name){
        ConstructionPlane old=requirePlane(id);String cleanName=clean(name);if(cleanName.isEmpty())throw new IllegalArgumentException("Plane name is empty");
        if(old.displayName.equals(cleanName))return false;Snapshot before=snapshot();planes.put(old.id,old.renamed(cleanName));commit(before);return true;
    }

    public synchronized boolean setPlaneVisibility(String id,boolean visible){
        ConstructionPlane old=requirePlane(id);if(old.visible==visible)return false;Snapshot before=snapshot();planes.put(old.id,old.withVisibility(visible));commit(before);return true;
    }

    public synchronized boolean isDefaultProjectState(String initialSketchId){
        String sketch=clean(initialSketchId);if(sketch.isEmpty()||planes.size()!=3||sketchPlaneIds.size()!=1)return false;
        if(!ConstructionPlane.XY_ID.equals(sketchPlaneIds.get(sketch))||!sketch.equals(activeSketchId)
                ||!ConstructionPlane.XY_ID.equals(activePlaneId)||nextOffsetSerial!=1)return false;
        return canonicalBuiltIn(ConstructionPlane.baseXY())&&canonicalBuiltIn(ConstructionPlane.baseXZ())
                &&canonicalBuiltIn(ConstructionPlane.baseYZ());
    }

    public synchronized DeleteResult deletePlane(String id){
        String key=clean(id);ConstructionPlane target=planes.get(key);if(target==null)return DeleteResult.NOT_FOUND;
        if(target.builtIn())return DeleteResult.BUILT_IN;
        if(sketchPlaneIds.containsValue(key))return DeleteResult.IN_USE;
        for(ConstructionPlane plane:planes.values())if(key.equals(plane.sourcePlaneId))return DeleteResult.HAS_DERIVED_PLANES;
        Snapshot before=snapshot();planes.remove(key);if(key.equals(activePlaneId))activePlaneId=ConstructionPlane.XY_ID;commit(before);return DeleteResult.DELETED;
    }

    public synchronized boolean undo(){if(undo.isEmpty())return false;Snapshot current=snapshot();restore(undo.removeLast());redo.addLast(current);revision++;return true;}
    public synchronized boolean redo(){if(redo.isEmpty())return false;Snapshot current=snapshot();restore(redo.removeLast());undo.addLast(current);revision++;return true;}

    /** Restore is prevalidated and does not create a user history step. */
    public synchronized void restoreExternal(Collection<ConstructionPlane> values,Map<String,String> assignments,
                                             String restoredActiveSketchId,String restoredActivePlaneId,long restoredNextSerial){
        LinkedHashMap<String,ConstructionPlane> incoming=new LinkedHashMap<>();java.util.HashSet<Long> orders=new java.util.HashSet<>();
        List<ConstructionPlane> ordered=new ArrayList<>();if(values!=null)ordered.addAll(values);
        ordered.sort((a,b)->{if(a==null||b==null)throw new IllegalArgumentException("Construction plane is missing");return Long.compare(a.creationOrder,b.creationOrder);});
        for(ConstructionPlane plane:ordered){if(plane==null||incoming.put(plane.id,plane)!=null)throw new IllegalArgumentException("Duplicate construction plane id");if(!orders.add(plane.creationOrder))throw new IllegalArgumentException("Duplicate construction plane creation order");}
        requireBuiltIn(incoming,ConstructionPlane.baseXY());requireBuiltIn(incoming,ConstructionPlane.baseXZ());requireBuiltIn(incoming,ConstructionPlane.baseYZ());
        for(ConstructionPlane plane:incoming.values())if(plane.provenance==ConstructionPlane.Provenance.OFFSET){
            ConstructionPlane source=incoming.get(plane.sourcePlaneId);if(source==null)throw new IllegalArgumentException("Offset source plane is missing");
            if(source.id.equals(plane.id))throw new IllegalArgumentException("Offset plane is self-referential");
            requireOffsetGeometry(plane,source);assertNoCycle(plane,incoming);
        }
        LinkedHashMap<String,String> incomingAssignments=new LinkedHashMap<>();
        if(assignments!=null)for(Map.Entry<String,String> e:assignments.entrySet()){
            String sketch=clean(e.getKey()),plane=clean(e.getValue());if(sketch.isEmpty()||!incoming.containsKey(plane))throw new IllegalArgumentException("Sketch plane relationship is invalid");
            incomingAssignments.put(sketch,plane);
        }
        String activePlane=clean(restoredActivePlaneId);if(!incoming.containsKey(activePlane))throw new IllegalArgumentException("Active plane is missing");
        String activeSketch=clean(restoredActiveSketchId);if(!activeSketch.isEmpty()&&!incomingAssignments.containsKey(activeSketch))throw new IllegalArgumentException("Active Sketch plane relationship is invalid");
        if(restoredNextSerial<1)throw new IllegalArgumentException("Plane serial is invalid");
        planes.clear();planes.putAll(incoming);sketchPlaneIds.clear();sketchPlaneIds.putAll(incomingAssignments);
        activeSketchId=activeSketch.isEmpty()?null:activeSketch;activePlaneId=activePlane;nextOffsetSerial=restoredNextSerial;
        undo.clear();redo.clear();revision++;
    }

    public static ConstructionPlaneDocument migrateLegacy(List<ConstructionPlane.Legacy> rows){
        ConstructionPlaneDocument doc=new ConstructionPlaneDocument();
        if(rows==null)return doc;
        for(ConstructionPlane.Legacy row:rows){
            ConstructionPlane base=matchingBase(row);
            double distance=row.origin.dot(base.normal);
            String planeId;
            if(row.origin.length()<=1.0e-9)planeId=base.id;
            else{
                String id="plane:legacy:"+digest(row);
                ConstructionPlane p=ConstructionPlane.offset(id,row.label,base.id,distance,row.origin,row.u,row.v,row.u.cross(row.v).normalized(),true,doc.planes.size());
                doc.planes.put(id,p);planeId=id;
            }
            if(doc.sketchPlaneIds.put(row.sketchId,planeId)!=null)throw new IllegalArgumentException("Duplicate legacy Sketch relationship");
            doc.activeSketchId=row.sketchId;doc.activePlaneId=planeId;
        }
        doc.nextOffsetSerial=1;doc.undo.clear();doc.redo.clear();doc.revision=0;return doc;
    }

    private ConstructionPlane createOffsetPlaneWithoutHistory(String sourceId,double distanceMm,String displayName){
        ConstructionPlane source=requirePlane(sourceId);if(!Double.isFinite(distanceMm)||Math.abs(distanceMm)>1.0e12)throw new IllegalArgumentException("Offset distance must be finite");
        long serial=nextAvailableOffsetSerial();String id="plane:offset:"+serial;
        String name=displayName==null||displayName.trim().isEmpty()?"Offset Plane "+serial:displayName.trim();
        ConstructionPlane p=ConstructionPlane.offset(id,name,source.id,distanceMm,source.origin.plus(source.normal.times(distanceMm)),source.uAxis,source.vAxis,source.normal,true,nextCreationOrder());
        nextOffsetSerial=serial+1;planes.put(id,p);activePlaneId=id;return p;
    }
    private void assignSketchWithoutHistory(String sketchId,String planeId){String sketch=clean(sketchId);if(sketch.isEmpty())throw new IllegalArgumentException("Sketch id is empty");ConstructionPlane p=requirePlane(planeId);sketchPlaneIds.put(sketch,p.id);activeSketchId=sketch;activePlaneId=p.id;}
    private ConstructionPlane requirePlane(String id){ConstructionPlane p=planes.get(clean(id));if(p==null)throw new IllegalArgumentException("Construction plane is missing");return p;}
    private void installBuiltIns(){planes.put(ConstructionPlane.XY_ID,ConstructionPlane.baseXY());planes.put(ConstructionPlane.XZ_ID,ConstructionPlane.baseXZ());planes.put(ConstructionPlane.YZ_ID,ConstructionPlane.baseYZ());}
    private long nextAvailableOffsetSerial(){long serial=nextOffsetSerial;while(serial<Long.MAX_VALUE&&planes.containsKey("plane:offset:"+serial))serial++;if(serial==Long.MAX_VALUE)throw new IllegalStateException("Plane identity serial is exhausted");return serial;}
    private long nextCreationOrder(){long max=-1;for(ConstructionPlane plane:planes.values())max=Math.max(max,plane.creationOrder);if(max==Long.MAX_VALUE)throw new IllegalStateException("Plane creation order is exhausted");return max+1;}
    private boolean canonicalBuiltIn(ConstructionPlane expected){ConstructionPlane actual=planes.get(expected.id);return actual!=null&&actual.provenance==expected.provenance&&actual.visible==expected.visible
            &&actual.displayName.equals(expected.displayName)&&same(actual.origin,expected.origin)&&same(actual.uAxis,expected.uAxis)
            &&same(actual.vAxis,expected.vAxis)&&same(actual.normal,expected.normal)&&actual.creationOrder==expected.creationOrder;}
    private void commit(Snapshot before){undo.addLast(before);while(undo.size()>MAX_HISTORY)undo.removeFirst();redo.clear();revision++;}
    private Snapshot snapshot(){return new Snapshot(planes,sketchPlaneIds,activeSketchId,activePlaneId,nextOffsetSerial);}
    private void restore(Snapshot s){planes.clear();planes.putAll(s.planes);sketchPlaneIds.clear();sketchPlaneIds.putAll(s.assignments);activeSketchId=s.activeSketchId;activePlaneId=s.activePlaneId;nextOffsetSerial=s.nextOffsetSerial;}

    private static final class Snapshot {
        final LinkedHashMap<String,ConstructionPlane> planes;
        final LinkedHashMap<String,String> assignments;
        final String activeSketchId,activePlaneId;final long nextOffsetSerial;
        Snapshot(Map<String,ConstructionPlane> p,Map<String,String> a,String sketch,String plane,long serial){planes=new LinkedHashMap<>(p);assignments=new LinkedHashMap<>(a);activeSketchId=sketch;activePlaneId=plane;nextOffsetSerial=serial;}
    }

    private static void requireBuiltIn(Map<String,ConstructionPlane> map,ConstructionPlane expected){ConstructionPlane actual=map.get(expected.id);if(actual==null||actual.provenance!=expected.provenance)throw new IllegalArgumentException("Required built-in plane is missing");}
    private static void requireOffsetGeometry(ConstructionPlane plane,ConstructionPlane source){ConstructionPlane.Vector expected=source.origin.plus(source.normal.times(plane.offsetDistanceMm));
        if(!same(plane.origin,expected)||!same(plane.uAxis,source.uAxis)||!same(plane.vAxis,source.vAxis)||!same(plane.normal,source.normal))
            throw new IllegalArgumentException("Offset plane geometry does not match its source and distance");}
    private static boolean same(ConstructionPlane.Vector a,ConstructionPlane.Vector b){return close(a.x,b.x)&&close(a.y,b.y)&&close(a.z,b.z);}
    private static boolean close(double a,double b){double scale=Math.max(1.0,Math.max(Math.abs(a),Math.abs(b)));return Math.abs(a-b)<=1.0e-9*scale;}
    private static void assertNoCycle(ConstructionPlane start,Map<String,ConstructionPlane> all){java.util.HashSet<String> seen=new java.util.HashSet<>();ConstructionPlane p=start;while(p!=null&&p.sourcePlaneId!=null){if(!seen.add(p.id))throw new IllegalArgumentException("Cyclic plane provenance");p=all.get(p.sourcePlaneId);}}
    private static ConstructionPlane matchingBase(ConstructionPlane.Legacy row){
        ConstructionPlane.Vector normal=row.u.cross(row.v).normalized();ConstructionPlane[] bases={ConstructionPlane.baseXY(),ConstructionPlane.baseXZ(),ConstructionPlane.baseYZ()};ConstructionPlane match=null;
        for(ConstructionPlane b:bases)if(aligned(row.u,b.uAxis)&&aligned(row.v,b.vAxis)&&normal.dot(b.normal)>1.0-1.0e-6){if(match!=null)throw new IllegalArgumentException("Ambiguous legacy plane");match=b;}
        if(match==null)throw new IllegalArgumentException("Legacy plane basis is unsupported");
        ConstructionPlane.Vector tangential=row.origin.plus(match.normal.times(-row.origin.dot(match.normal)));
        if(tangential.length()>1.0e-6)throw new IllegalArgumentException("Legacy offset origin is not on the source normal");return match;
    }
    private static boolean aligned(ConstructionPlane.Vector a,ConstructionPlane.Vector b){double al=a.length(),bl=b.length();return al>1.0e-9&&Math.abs(a.dot(b)/(al*bl)-1.0)<1.0e-6;}
    private static String digest(ConstructionPlane.Legacy row){try{MessageDigest md=MessageDigest.getInstance("SHA-256");String raw=String.format(Locale.US,"%s|%.17g|%.17g|%.17g|%.17g|%.17g|%.17g|%.17g|%.17g|%.17g",row.sketchId,row.origin.x,row.origin.y,row.origin.z,row.u.x,row.u.y,row.u.z,row.v.x,row.v.y,row.v.z);byte[] bytes=md.digest(raw.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(int i=0;i<8;i++)out.append(String.format(Locale.US,"%02x",bytes[i]));return out.toString();}catch(Exception e){throw new IllegalStateException(e);}}
    private static String clean(String value){return value==null?"":value.trim();}
}
