package ir.chobyar.sketch;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Keeps the clean skachmori shell while upgrading the modeling canvas to the
 * exact OCCT workspace with stable Face/Edge references and integrated History.
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

            OcctStableCadCanvasView upgraded=new OcctStableCadCanvasView(this);
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
            Toast.makeText(this,"OCCT Stable workspace فعال نشد؛ محیط قبلی حفظ شد",Toast.LENGTH_SHORT).show();
        }
    }
}
