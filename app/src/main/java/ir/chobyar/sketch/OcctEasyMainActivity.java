package ir.chobyar.sketch;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps the clean skachmori shell from EasyMainActivity while upgrading only the
 * modeling canvas to OcctModelCadCanvasView. This avoids duplicating UI code and
 * lets the exact kernel migrate independently from the workspace chrome.
 */
public class OcctEasyMainActivity extends EasyMainActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        installOcctModelCanvas();
    }

    private void installOcctModelCanvas(){
        try{
            Field easyField=EasyMainActivity.class.getDeclaredField("easyCad");
            easyField.setAccessible(true);
            Object current=easyField.get(this);
            if(!(current instanceof View))return;
            View old=(View)current;
            if(!(old.getParent() instanceof ViewGroup))return;
            ViewGroup parent=(ViewGroup)old.getParent();
            int index=parent.indexOfChild(old);
            ViewGroup.LayoutParams params=old.getLayoutParams();

            OcctModelCadCanvasView upgraded=new OcctModelCadCanvasView(this);
            easyField.set(this,upgraded);

            Field mainCad=MainActivity.class.getDeclaredField("cad");
            mainCad.setAccessible(true);
            mainCad.set(this,upgraded);

            parent.removeView(old);
            parent.addView(upgraded,Math.max(0,index),params);

            Method wire=EasyMainActivity.class.getDeclaredMethod("wireWorkspaceCallbacks");
            wire.setAccessible(true);
            wire.invoke(this);
            upgraded.dispatchWorkspaceState();
        }catch(Exception e){
            Toast.makeText(this,"OCCT Model workspace فعال نشد؛ محیط قبلی حفظ شد",Toast.LENGTH_SHORT).show();
        }
    }
}
