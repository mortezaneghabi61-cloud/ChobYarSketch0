package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Clean skachmori shell upgraded to the exact OCCT workspace plus the
 * Shapr-inspired Sketch/Measure workflow, pen-first Automatic Line/Arc,
 * sketch definition states, and driving-dimension DOF solving.
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

            ShaprConstraintSolverCadCanvasView upgraded=new ShaprConstraintSolverCadCanvasView(this);
            easyField.set(this,upgraded);

            Field mainCad=MainActivity.class.getDeclaredField("cad");
            mainCad.setAccessible(true);
            mainCad.set(this,upgraded);

            parent.removeView(old);
            parent.addView(upgraded,Math.max(0,index),params);

            Method wire=EasyMainActivity.class.getDeclaredMethod("wireWorkspaceCallbacks");
            wire.setAccessible(true);
            wire.invoke(this);
            rewireShaprButtons(parent,upgraded);
            upgraded.dispatchWorkspaceState();
        }catch(Exception e){
            Toast.makeText(this,"OCCT Sketch workspace فعال نشد؛ محیط قبلی حفظ شد",Toast.LENGTH_SHORT).show();
        }
    }

    private void rewireShaprButtons(View root,ShaprConstraintSolverCadCanvasView cad){
        if(root instanceof Button){
            Button b=(Button)root;
            String t=String.valueOf(b.getText());
            if(t.contains("Sketch")) b.setOnClickListener(v->cad.showShaprSketchMenu());
            else if(t.contains("Tools")) b.setOnClickListener(v->showMasterTools(cad));
        }
        if(root instanceof ViewGroup){
            ViewGroup g=(ViewGroup)root;
            for(int i=0;i<g.getChildCount();i++)rewireShaprButtons(g.getChildAt(i),cad);
        }
    }

    private void showMasterTools(ShaprConstraintSolverCadCanvasView cad){
        String[] items={
                "✎ Sketch tools",
                "▣ 3D Modeling tools",
                "⌖ Measure انتخاب",
                "⌁ Constraints",
                "● Sketch State / DOF",
                "◇ Plane / Construction",
                "⏱ History"
        };
        new AlertDialog.Builder(this).setTitle("Tools").setItems(items,(d,w)->{
            if(w==0)cad.showShaprSketchMenu();
            else if(w==1)cad.showShaprModelingToolsMenu();
            else if(w==2)cad.showSketchMeasureInspector();
            else if(w==3)cad.showSmartConstraintMenu();
            else if(w==4)new AlertDialog.Builder(this)
                    .setTitle("Sketch State / DOF")
                    .setMessage(cad.sketchStateSummary())
                    .setPositiveButton("بستن",null).show();
            else if(w==5)cad.showPlaneManager();
            else cad.showHistoryManager();
        }).setNegativeButton("بستن",null).show();
    }
}
