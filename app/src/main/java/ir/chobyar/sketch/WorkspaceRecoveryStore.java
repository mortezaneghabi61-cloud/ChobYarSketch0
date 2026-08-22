package ir.chobyar.sketch;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.AtomicFile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Crash/process-death recovery for the last committed CAD workspace. */
final class WorkspaceRecoveryStore {
    private static final String PREFS="chobyar-workspace-recovery-v1";
    private static final String NAME="name";
    private static final String UPDATED="updated";
    private static final long MAX_BYTES=64L*1024L*1024L;

    static final class Snapshot {
        final String payload;
        final String name;
        final long updatedAt;
        Snapshot(String payload,String name,long updatedAt){this.payload=payload;this.name=name;this.updatedAt=updatedAt;}
    }

    private final SharedPreferences prefs;
    private final AtomicFile file;

    WorkspaceRecoveryStore(Context context){
        Context app=context.getApplicationContext();
        File dir=new File(app.getFilesDir(),"recovery");
        if(!dir.exists()&&!dir.mkdirs()&&!dir.isDirectory())throw new IllegalStateException("Recovery directory unavailable");
        file=new AtomicFile(new File(dir,"latest.chobyar"));
        prefs=app.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    synchronized void save(String payload,String displayName){
        CadProjectDocument.decode(payload);
        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
        if(bytes.length<1||bytes.length>MAX_BYTES)throw new IllegalArgumentException("Recovery project size is invalid");
        FileOutputStream out=null;
        try{
            out=file.startWrite();out.write(bytes);out.flush();file.finishWrite(out);out=null;
            String name=displayName==null?"":displayName.trim();
            if(!prefs.edit().putString(NAME,name).putLong(UPDATED,System.currentTimeMillis()).commit())
                throw new IllegalStateException("Recovery metadata could not be stored");
        }catch(RuntimeException e){if(out!=null)file.failWrite(out);throw e;}
        catch(Exception e){if(out!=null)file.failWrite(out);throw new IllegalStateException("Recovery snapshot could not be stored",e);}
    }

    synchronized Snapshot load(){
        File base=file.getBaseFile();
        if(!base.isFile())return null;
        if(base.length()<1||base.length()>MAX_BYTES){clear();return null;}
        try(FileInputStream in=file.openRead();ByteArrayOutputStream out=new ByteArrayOutputStream((int)Math.min(base.length(),1024L*1024L))){
            byte[] buffer=new byte[65536];int n;long total=0;
            while((n=in.read(buffer))!=-1){total+=n;if(total>MAX_BYTES)throw new IllegalArgumentException("Recovery project is too large");out.write(buffer,0,n);}
            String raw=new String(out.toByteArray(),StandardCharsets.UTF_8);CadProjectDocument.decode(raw);
            return new Snapshot(raw,prefs.getString(NAME,""),prefs.getLong(UPDATED,base.lastModified()));
        }catch(Exception e){clear();return null;}
    }

    synchronized boolean hasSnapshot(){return file.getBaseFile().isFile();}

    synchronized void clear(){
        file.delete();prefs.edit().clear().commit();
    }
}
