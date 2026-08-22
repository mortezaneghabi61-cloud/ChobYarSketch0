package ir.chobyar.sketch;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App-owned project shelf. Project payloads remain ordinary editable .chobyar documents. */
final class InternalProjectRepository {
    static final String BOULDER_TABLE_ID="sample-boulder-table-v1";
    static final String HOURGLASS_TABLE_ID="sample-hourglass-table-v1";
    private static final String PREFS="chobyar-internal-projects-v1";
    private static final String IDS="project-ids";
    private static final String DATA="data.";
    private static final String NAME="name.";
    private static final String UPDATED="updated.";

    static final class Entry {
        final String id,name;
        final long updatedAt;
        final boolean builtIn;
        Entry(String id,String name,long updatedAt,boolean builtIn){this.id=id;this.name=name;this.updatedAt=updatedAt;this.builtIn=builtIn;}
    }

    private final SharedPreferences prefs;

    InternalProjectRepository(Context context){
        prefs=context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
    }

    void ensureFurnitureSamples(Context context){
        if(!contains(BOULDER_TABLE_ID))save(BOULDER_TABLE_ID,
                "میز سنگی سه‌گوی • 760×630",FurnitureSampleProjectFactory.createBoulderTable(context));
        if(!contains(HOURGLASS_TABLE_ID))save(HOURGLASS_TABLE_ID,
                "میز پایه ساعت‌شنی • 2000×900×765",FurnitureSampleProjectFactory.createHourglassTable(context));
    }

    boolean contains(String id){return id!=null&&prefs.contains(DATA+id);}

    String load(String id){
        String raw=id==null?null:prefs.getString(DATA+id,null);
        if(raw==null)throw new IllegalArgumentException("پروژه داخل اپ پیدا نشد");
        CadProjectDocument.decode(raw);
        return raw;
    }

    void save(String id,String name,String payload){
        String cleanId=cleanId(id),cleanName=name==null?"":name.trim();
        if(cleanId.isEmpty()||cleanName.isEmpty())throw new IllegalArgumentException("نام پروژه خالی است");
        CadProjectDocument.decode(payload);
        Set<String> ids=new HashSet<>(prefs.getStringSet(IDS,Collections.emptySet()));ids.add(cleanId);
        prefs.edit().putStringSet(IDS,ids).putString(DATA+cleanId,payload).putString(NAME+cleanId,cleanName)
                .putLong(UPDATED+cleanId,System.currentTimeMillis()).apply();
    }

    String createId(String name){
        String base=cleanId(name);if(base.isEmpty())base="project";
        String id="user-"+base,n=id;int suffix=2;while(contains(n))n=id+"-"+(suffix++);return n;
    }

    String name(String id){return prefs.getString(NAME+id,"پروژه چوب‌یار");}

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

    private static String cleanId(String raw){
        String s=raw==null?"":raw.trim().toLowerCase(java.util.Locale.US);StringBuilder out=new StringBuilder();
        for(int i=0;i<s.length();i++){char c=s.charAt(i);if((c>='a'&&c<='z')||(c>='0'&&c<='9')||c=='-'||c=='_')out.append(c);else if(Character.isLetterOrDigit(c))out.append(Integer.toHexString(c));else if(out.length()>0&&out.charAt(out.length()-1)!='-')out.append('-');}
        while(out.length()>0&&out.charAt(out.length()-1)=='-')out.deleteCharAt(out.length()-1);return out.toString();
    }
}
