package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

/**
 * Clean skachmori shell upgraded to the exact OCCT workspace plus the
 * Shapr-aligned Sketch/Measure workflow, pen-first Automatic Line/Arc,
 * sketch definition states, driving-dimension DOF solving, parametric
 * Ellipse / Fit Point / Control Point Spline editing, spline point/tangent
 * Break-Join workflows, pen-drawn circular Arc, full Snaps / Guides including
 * OCCT-derived 3D Guidepoints and Distant Edges.
 */
public class OcctEasyMainActivity extends EasyMainActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        installOcctModelCanvas();
    }

    private void installOcctModelCanvas(){
        try{
            NativeBRepCadCanvasView old=easyCad;
            if(old==null)return;
            if(!(old.getParent() instanceof ViewGroup))return;
            ViewGroup parent=(ViewGroup)old.getParent();
            int index=parent.indexOfChild(old);
            ViewGroup.LayoutParams params=old.getLayoutParams();

            Shapr3DGuideCadCanvasView upgraded=new Shapr3DGuideCadCanvasView(this);
            easyCad=upgraded;
            cad=upgraded;

            parent.removeView(old);
            parent.addView(upgraded,Math.max(0,index),params);

            wireWorkspaceCallbacks();
            rewireShaprButtons(parent,upgraded);
            upgraded.dispatchWorkspaceState();
        }catch(Exception e){
            Toast.makeText(this,"OCCT Sketch workspace فعال نشد؛ محیط قبلی حفظ شد",Toast.LENGTH_SHORT).show();
        }
    }

    private void rewireShaprButtons(View root,Shapr3DGuideCadCanvasView cad){
        if(root instanceof Button){
            Button b=(Button)root;
            String t=String.valueOf(b.getText());
            if(t.contains("Sketch")) b.setOnClickListener(v->cad.showShaprSketchMenu());
            else if(t.contains("Tools")) b.setOnClickListener(v->showMasterTools(cad));
            else if(t.contains("Snap")) b.setOnClickListener(v->cad.showShaprSnappingOptions());
        }
        if(root instanceof ViewGroup){
            ViewGroup g=(ViewGroup)root;
            for(int i=0;i<g.getChildCount();i++)rewireShaprButtons(g.getChildAt(i),cad);
        }
    }

    private void showMasterTools(Shapr3DGuideCadCanvasView cad){
        String[] items={
                "✎ Sketch tools",
                "⌁ Snaps / Guides",
                "〰 Ellipse / Spline edit",
                "▣ 3D Modeling tools",
                "⌖ Measure انتخاب",
                "⌁ Constraints",
                "⚙ Constraint Settings",
                "● Sketch State / DOF",
                "◇ Plane / Construction",
                "⏱ History"
        };
        new AlertDialog.Builder(this).setTitle("Tools").setItems(items,(d,w)->{
            if(w==0)cad.showShaprSketchMenu();
            else if(w==1)cad.showShaprSnappingOptions();
            else if(w==2)cad.showCurveEditor();
            else if(w==3)cad.showShaprModelingToolsMenu();
            else if(w==4)cad.showSketchMeasureInspector();
            else if(w==5)cad.showSmartConstraintMenu();
            else if(w==6)cad.showShaprConstraintSettings();
            else if(w==7)new AlertDialog.Builder(this)
                    .setTitle("Sketch State / DOF")
                    .setMessage(cad.sketchStateSummary())
                    .setPositiveButton("بستن",null).show();
            else if(w==8)cad.showPlaneManager();
            else cad.showHistoryManager();
        }).setNegativeButton("بستن",null).show();
    }
}
