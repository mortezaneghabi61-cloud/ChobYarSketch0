package ir.chobyar.sketch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * App-owned project shelf.
 *
 * Project payloads are ordinary editable .chobyar documents. Metadata remains
 * in SharedPreferences, while payloads are stored with AtomicFile so a process
 * death or storage interruption cannot leave a half-written CAD document.
 * Legacy SharedPreferences payloads are migrated lazily on first read.
 */
final class InternalProjectRepository {
    static final String BOULDER_TABLE_ID="sample-boulder-table-v1";
    static final String HOURGLASS_TABLE_ID="sample-hourglass-table-v1";
    private static final String PREFS="chobyar-internal-projects-v1";
    private static final String IDS="project-ids";
    private static final String DATA="data."; // legacy payload key
    private static final String NAME="name.";
    private static final String UPDATED="updated.";
    private static final long MAX_PROJECT_BYTES=64L*1024L*1024L;

    static final class Entry {
        final String id,name;
        final long updatedAt;
        final boolean builtIn;
        Entry(String id,String name,long updatedAt,boolean builtIn){this.id=id;this.name=name;this.updatedAt=updatedAt;this.builtIn=builtIn;}
    }

    private final SharedPreferences prefs;
    private final File projectDir;

    InternalProjectRepository(Context context){
        Context app=context.getApplicationContext();
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        projectDir=new File(app.getFilesDir(),"internal-projects");
        if(!projectDir.exists()&&!projectDir.mkdirs()&&!projectDir.isDirectory())
            throw new IllegalStateException("text Projects created text");
    }

    void ensureFurnitureSamples(Context context){
        ensureSample(context,BOULDER_TABLE_ID,"text text text • 760×630",true);
        ensureSample(context,HOURGLASS_TABLE_ID,"text text text • 2000×900×765",false);
    }

    private void ensureSample(Context context,String id,String name,boolean boulder){
        if(contains(id)){
            try{load(id);return;}catch(RuntimeException ignored){removeStoredPayload(id);}
        }
        String payload=boulder?FurnitureSampleProjectFactory.createBoulderTable(context):FurnitureSampleProjectFactory.createHourglassTable(context);
        saveInternal(id,name,payload,true);
    }

    boolean contains(String id){
        String clean=cleanId(id);
        return !clean.isEmpty()&&(payloadFile(clean).isFile()||prefs.contains(DATA+clean));
    }

    String load(String id){
        String clean=cleanId(id);
        if(clean.isEmpty())throw new IllegalArgumentException("Project text text was not found");
        File file=payloadFile(clean);
        String raw;
        if(file.isFile())raw=readAtomic(file);
        else{
            raw=prefs.getString(DATA+clean,null);
            if(raw==null)throw new IllegalArgumentException("Project text text was not found");
            CadProjectDocument.decode(raw);
            // Lazy one-way migration from the old SharedPreferences payload.
            writeAtomic(file,raw);
            if(!prefs.edit().remove(DATA+clean).commit())throw new IllegalStateException("text Project text text");
        }
        CadProjectDocument.decode(raw);
        return raw;
    }

    void save(String id,String name,String payload){saveInternal(id,name,payload,false);}

    private void saveInternal(String id,String name,String payload,boolean allowBundledWrite){
        String cleanId=cleanId(id),cleanName=name==null?"":name.trim();
        if(cleanId.isEmpty()||cleanName.isEmpty())throw new IllegalArgumentException("text Project is empty");
        if(isBuiltIn(cleanId)&&contains(cleanId)&&!allowBundledWrite)
            throw new IllegalStateException("Project text text text text text Save text");
        CadProjectDocument.decode(payload);
        if(payload.getBytes(StandardCharsets.UTF_8).length>MAX_PROJECT_BYTES)
            throw new IllegalArgumentException("text Project text text text text text");
        writeAtomic(payloadFile(cleanId),payload);
        Set<String> ids=new HashSet<>(prefs.getStringSet(IDS,Collections.emptySet()));ids.add(cleanId);
        boolean ok=prefs.edit().putStringSet(IDS,ids).remove(DATA+cleanId).putString(NAME+cleanId,cleanName)
                .putLong(UPDATED+cleanId,System.currentTimeMillis()).commit();
        if(!ok)throw new IllegalStateException("text text Project text text");
    }

    String saveCopy(String name,String payload){
        String id=createId(name);save(id,name,payload);return id;
    }

    void rename(String id,String newName){
        String clean=cleanId(id),name=newName==null?"":newName.trim();
        if(!contains(clean))throw new IllegalArgumentException("Project text text was not found");
        if(isBuiltIn(clean))throw new IllegalStateException("text Project text Transform text");
        if(name.isEmpty())throw new IllegalArgumentException("text Project is empty");
        if(!prefs.edit().putString(NAME+clean,name).putLong(UPDATED+clean,System.currentTimeMillis()).commit())
            throw new IllegalStateException("Transform text Save text");
    }

    void delete(String id){
        String clean=cleanId(id);
        if(!contains(clean))return;
        if(isBuiltIn(clean))throw new IllegalStateException("Project text Delete text");
        Set<String> ids=new HashSet<>(prefs.getStringSet(IDS,Collections.emptySet()));ids.remove(clean);
        boolean metadata=prefs.edit().putStringSet(IDS,ids).remove(DATA+clean).remove(NAME+clean).remove(UPDATED+clean).commit();
        File f=payloadFile(clean);boolean fileOk=!f.exists()||f.delete();
        if(!metadata||!fileOk)throw new IllegalStateException("Delete Project text text");
    }

    String createId(String name){
        String base=cleanId(name);if(base.isEmpty())base="project";
        String id="user-"+base,n=id;int suffix=2;while(contains(n))n=id+"-"+(suffix++);return n;
    }

    String name(String id){return prefs.getString(NAME+cleanId(id),"Project ChobYar");}

    List<Entry> entries(){
        Set<String> ids=new HashSet<>(prefs.getStringSet(IDS,Collections.emptySet()));
        if(contains(BOULDER_TABLE_ID))ids.add(BOULDER_TABLE_ID);
        if(contains(HOURGLASS_TABLE_ID))ids.add(HOURGLASS_TABLE_ID);
        List<Entry> out=new ArrayList<>();
        for(String id:ids)if(contains(id))out.add(new Entry(id,name(id),prefs.getLong(UPDATED+id,0),isBuiltIn(id)));
        out.sort(Comparator.comparingInt((Entry e)->e.builtIn?0:1).thenComparingLong(e->-e.updatedAt));
        return out;
    }

    static boolean isBuiltIn(String id){return BOULDER_TABLE_ID.equals(id)||HOURGLASS_TABLE_ID.equals(id);}

    private File payloadFile(String id){return new File(projectDir,id+".chobyar");}

    private void removeStoredPayload(String id){
        File file=payloadFile(id);if(file.exists()&&!file.delete())throw new IllegalStateException("Project text text text");
        prefs.edit().remove(DATA+id).commit();
    }

    private static String readAtomic(File file){
        if(file.length()<1||file.length()>MAX_PROJECT_BYTES)throw new IllegalArgumentException("text Project invalid text");
        try(FileInputStream in=new AtomicFile(file).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.min(file.length(),1024L*1024L))){
            byte[] buffer=new byte[65536];int n;long total=0;
            while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_PROJECT_BYTES)throw new IllegalArgumentException("text Project text text text text text");out.write(buffer,0,n);}
            return new String(out.toByteArray(),StandardCharsets.UTF_8);
        }catch(IllegalArgumentException e){throw e;}catch(Exception e){throw new IllegalStateException("text Project Done text",e);}
    }

    private static void writeAtomic(File file,String raw){
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;
        try{
            byte[] bytes=raw.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_PROJECT_BYTES)throw new IllegalArgumentException("text Project text text text text text");
            out=atomic.startWrite();out.write(bytes);out.flush();atomic.finishWrite(out);out=null;
        }catch(RuntimeException e){if(out!=null)atomic.failWrite(out);throw e;}
        catch(Exception e){if(out!=null)atomic.failWrite(out);throw new IllegalStateException("Save Project Done text",e);}
    }

    private static String cleanId(String raw){
        String s=raw==null?"":raw.trim().toLowerCase(java.util.Locale.US);StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-'||c=='_')out.append(c);else if(Character.isLetterOrDigit(c))out.append(Integer.toHexString(c));else if(out.length()>0&&out.charAt(out.length()-1)!='-')out.append('-');}
        while(out.length()>0&&out.charAt(out.length()-1)=='-')out.deleteCharAt(out.length()-1);return out.toString();
    }
}
