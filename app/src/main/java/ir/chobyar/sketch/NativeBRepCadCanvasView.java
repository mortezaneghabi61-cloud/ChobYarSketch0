package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import java.util.Locale;

/**
 * Native-kernel gateway layered on top of the ExactBoolean workspace.
 *
 * Java/Polygonal CAD remains usable as a fallback, while arm64 devices exercise
 * OCCT B-Rep primitives, Form features, Boolean topology and exact direct edits
 * through JNI.
 */
public class NativeBRepCadCanvasView extends ExactBooleanCadCanvasView {

    public NativeBRepCadCanvasView(Context context){
        super(context);
    }

    @Override
    public void showSolidManager(){
        String nativeState=NativeBRepKernel.isAvailable()?"Native C++ loaded":"Native C++ is unavailable";
        String occtState=NativeBRepKernel.occtAvailable()?"OCCT Exact B-Rep loaded":"OCCT unavailable for this ABI";
        String[] items={
                "▣ Solid Tools / Exact Edge Tools",
                "⚙ Native + OCCT Kernel / Status",
                "✓ Native Self-Test / JNI Geometry",
                "◆ OCCT Exact B-Rep Self-Test",
                "▣−◯ OCCT Box − Cylinder / Boolean Test",
                "◎ Sphere ↔ Sphere / Native C++",
                "◯ Plane ↔ Sphere / Native C++"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Native B-Rep")
                .setMessage(nativeState+"\n"+occtState
                        +" \n  \n On arm64, Extrude/Revolve/Sweep/Loft, Boolean and Edit 3D can create and modify Open CASCADE TopoDS_Shape bodies.")
                .setItems(items,(d,w)->{
                    if(w==0)NativeBRepCadCanvasView.super.showSolidManager();
                    else if(w==1)showNativeStatus();
                    else if(w==2)toast(NativeBRepKernel.selfTest());
                    else if(w==3)toast(NativeBRepKernel.occtSelfTest());
                    else if(w==4)showOcctBooleanDemo();
                    else if(w==5)showSphereSphereTest();
                    else showPlaneSphereTest();
                })
                .setNegativeButton("Close",null).show();
    }

    @Override
    public String selectedInfo(){
        String base=super.selectedInfo();
        if(NativeBRepKernel.occtAvailable())return base+" | OCCT B-Rep";
        return base+(NativeBRepKernel.isAvailable()?" | Native C++":" | Java fallback");
    }

    @Override
    public void clearAll(){
        super.clearAll();
        NativeBRepKernel.occtClear();
    }

    private void showNativeStatus(){
        int flags=NativeBRepKernel.capabilityFlags();
        StringBuilder msg=new StringBuilder();
        msg.append("Backend: ").append(NativeBRepKernel.version());
        msg.append("\nABI bridge: ").append(NativeBRepKernel.isAvailable()?"✓ Loaded":"✗ Not loaded");
        if(!NativeBRepKernel.isAvailable())msg.append("\nError: ").append(NativeBRepKernel.loadError());
        msg.append("\nOCCT: ").append(NativeBRepKernel.occtAvailable()?"✓ "+NativeBRepKernel.occtVersion():"○ Native fallback");
        msg.append("\n\nNative capabilities:");
        msg.append("\n").append((flags&1)!=0?"✓":"○").append(" Plane ↔ Sphere exact");
        msg.append("\n").append((flags&2)!=0?"✓":"○").append(" Sphere ↔ Sphere exact");
        msg.append("\n").append((flags&4)!=0?"✓":"○").append(" Analytic mass properties");
        msg.append("\n").append((flags&8)!=0?"✓":"○").append(" Native topology self-test");
        msg.append("\n").append((flags&16)!=0?"✓":"○").append(" OCCT shared libraries linked");
        msg.append("\n").append((flags&32)!=0?"✓":"○").append(" TopoDS exact primitives / Extrude");
        msg.append("\n").append((flags&64)!=0?"✓":"○").append(" BRepAlgoAPI Union/Subtract/Intersect");
        msg.append("\n").append((flags&128)!=0?"✓":"○").append(" BRepPrimAPI Revolve");
        msg.append("\n").append((flags&256)!=0?"✓":"○").append(" BRepOffsetAPI Sweep / Loft");
        msg.append("\n").append((flags&512)!=0?"✓":"○").append(" Edge Fillet / Chamfer exact");
        msg.append("\n").append((flags&1024)!=0?"✓":"○").append(" Face Push/Pull / Shell exact");
        msg.append("\n").append((flags&2048)!=0?"✓":"○").append(" Body Move / Rotate exact");
        msg.append(" \n  \n All native dimensions use mm; displayed dimensions may also show cm + mm.");
        msg.append(" \n  \n Direct-edit status: Edge/Face tools operate through Direct Edit while History keeps parametric features.");
        new AlertDialog.Builder(getContext()).setTitle("Native / OCCT B-Rep Kernel")
                .setMessage(msg.toString()).setPositiveButton("OK",null).show();
    }

    private void showOcctBooleanDemo(){
        if(!NativeBRepKernel.occtAvailable()){
            toast("OCCT is unavailable for this ABI; use an arm64 build with OCCT libraries.");
            return;
        }
        long box=0,cylinder=0,cut=0;
        try{
            box=NativeBRepKernel.occtCreateBox(100,80,20);
            cylinder=NativeBRepKernel.occtCreateCylinder(50,40,0,10,20);
            cut=NativeBRepKernel.occtBoolean(NativeBRepKernel.OCCT_SUBTRACT,box,cylinder);
            if(box==0||cylinder==0||cut==0){toast("OCCT Boolean operation failed.");return;}
            double[] s=NativeBRepKernel.occtShapeStats(cut);
            if(s.length<4){toast("B-Rep shape statistics are unavailable.");return;}
            double expected=100.0*80.0*20.0-Math.PI*10.0*10.0*20.0;
            String msg="Box: 10 cm × 8 cm × 2 cm\n"
                    +"Cylinder cutter: Ø 2 cm / 20 mm\n"
                    +"Operation: Exact Subtract (BRepAlgoAPI_Cut)\n\n"
                    +"Volume: "+dualVolume(s[0])+"\n"
                    +"Expected analytic: "+dualVolume(expected)+"\n"
                    +"Difference: "+num(Math.abs(s[0]-expected))+" mm³\n"
                    +"Face: "+(int)s[1]+"   Edge: "+(int)s[2]+"   Solid: "+(int)s[3]+"\n\n"
                    +NativeBRepKernel.occtShapeSummary(cut)
                    +" \n  \n This result is an OCCT TopoDS_Shape; the polygon view is only a display projection of the exact Boolean result.";
            new AlertDialog.Builder(getContext()).setTitle("OCCT Exact Box − Cylinder")
                    .setMessage(msg).setPositiveButton("OK",null).show();
        }finally{
            NativeBRepKernel.occtRelease(cut);
            NativeBRepKernel.occtRelease(cylinder);
            NativeBRepKernel.occtRelease(box);
        }
    }

    private void showSphereSphereTest(){
        Geometry3D.Vec3 a=new Geometry3D.Vec3(0,0,0);
        Geometry3D.Vec3 b=new Geometry3D.Vec3(40,0,0);
        double[] r=NativeBRepKernel.sphereSphere(a,30,b,30);
        if(r.length!=7){toast("Intersection Native was not found");return;}
        String msg="Sphere A: R 30 mm\nSphere B: R 30 mm\nCenter distance: 40 mm"
                +"\n\nIntersection circle radius: "+dual((float)r[6])
                +"\nCenter: ("+num(r[0])+", "+num(r[1])+", "+num(r[2])+") mm"
                +"\nNormal: ("+num(r[3])+", "+num(r[4])+", "+num(r[5])+")";
        new AlertDialog.Builder(getContext()).setTitle("Native Sphere ↔ Sphere")
                .setMessage(msg).setPositiveButton("OK",null).show();
    }

    private void showPlaneSphereTest(){
        double[] r=NativeBRepKernel.planeSphere(
                new Geometry3D.Vec3(0,0,10),new Geometry3D.Vec3(0,0,1),
                new Geometry3D.Vec3(0,0,0),30);
        if(r.length!=7){toast("Intersection Native was not found");return;}
        String msg="Sphere radius: 30 mm\nPlane offset: 10 mm"
                +"\n\nIntersection circle radius: "+dual((float)r[6])
                +"\nDiameter: "+dual((float)r[6]*2f)
                +"\nCenter: ("+num(r[0])+", "+num(r[1])+", "+num(r[2])+") mm";
        new AlertDialog.Builder(getContext()).setTitle("Native Plane ↔ Sphere")
                .setMessage(msg).setPositiveButton("OK",null).show();
    }

    private void toast(String text){
        Toast.makeText(getContext(),text,Toast.LENGTH_LONG).show();
    }

    private static String dual(float mm){return num(mm)+" mm";}
    private static String dualVolume(double mm3){return num(mm3)+" mm³";}
    private static String num(double v){
        String s=String.format(Locale.US,"%.4f",v);
        while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);
        return s;
    }
}
