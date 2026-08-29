package ir.chobyar.sketch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

@RunWith(AndroidJUnit4.class)
public final class ProductionHardeningInstrumentationTest {

    @Test public void internalProjectsAreAtomicManageableAndBundledSamplesAreProtected(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        InternalProjectRepository repo=new InternalProjectRepository(context);
        String payload=CadProjectDocument.encodeSketch("{}");
        String name="Hardening "+System.nanoTime();
        String id=repo.createId(name);
        repo.save(id,name,payload);
        assertEquals(payload,repo.load(id));
        repo.rename(id,"Renamed hardening project");
        assertEquals("Renamed hardening project",repo.name(id));
        repo.delete(id);
        assertFalse(repo.contains(id));

        repo.ensureFurnitureSamples(context);
        assertTrue(repo.contains(InternalProjectRepository.BOULDER_TABLE_ID));
        try{
            repo.save(InternalProjectRepository.BOULDER_TABLE_ID,"must not overwrite",payload);
            fail("Bundled project overwrite must be rejected");
        }catch(IllegalStateException expected){
            assertTrue(expected.getMessage().contains("text"));
        }
        try{
            repo.delete(InternalProjectRepository.BOULDER_TABLE_ID);
            fail("Bundled project deletion must be rejected");
        }catch(IllegalStateException expected){
            assertTrue(repo.contains(InternalProjectRepository.BOULDER_TABLE_ID));
        }
    }

    @Test public void legacySharedPreferencesPayloadMigratesToAtomicFileOnLoad(){
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String id="user-legacy-migration-"+System.nanoTime();
        String payload=CadProjectDocument.encodeSketch("{}");
        SharedPreferences legacy=context.getSharedPreferences("chobyar-internal-projects-v1",Context.MODE_PRIVATE);
        legacy.edit().putString("data."+id,payload).putString("name."+id,"Legacy").commit();
        InternalProjectRepository repo=new InternalProjectRepository(context);
        assertTrue(repo.contains(id));
        assertEquals(payload,repo.load(id));
        assertFalse(legacy.contains("data."+id));
        assertTrue(new File(new File(context.getFilesDir(),"internal-projects"),id+".chobyar").isFile());
        repo.delete(id);
    }

    @Test public void recoverySnapshotIsValidatedAtomicAndSelfHealsCorruption()throws Exception{
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        WorkspaceRecoveryStore store=new WorkspaceRecoveryStore(context);store.clear();
        String payload=CadProjectDocument.encodeSketch("{}");
        store.save(payload,"Bench project");
        assertTrue(store.hasSnapshot());
        WorkspaceRecoveryStore.Snapshot snapshot=store.load();
        assertNotNull(snapshot);assertEquals(payload,snapshot.payload);assertEquals("Bench project",snapshot.name);assertTrue(snapshot.updatedAt>0L);

        File recovery=new File(new File(context.getFilesDir(),"recovery"),"latest.chobyar");
        try(FileOutputStream out=new FileOutputStream(recovery,false)){out.write("not-a-project".getBytes(StandardCharsets.UTF_8));}
        assertNull(store.load());
        assertFalse(store.hasSnapshot());
        store.clear();
    }
}
