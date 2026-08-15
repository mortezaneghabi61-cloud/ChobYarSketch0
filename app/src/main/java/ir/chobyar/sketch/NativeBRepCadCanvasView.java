package ir.chobyar.sketch;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.Toast;

import java.util.Locale;

/**
 * Native-kernel gateway layered on top of the current ExactBoolean workspace.
 *
 * Existing Java CAD features keep working while exact geometry services begin
 * moving behind JNI. This class is intentionally a migration layer: the UI can
 * stay stable while the native backend grows from analytic intersections to an
 * OCCT-backed B-Rep/Boolean implementation.
 */
public class NativeBRepCadCanvasView extends ExactBooleanCadCanvasView {

    public NativeBRepCadCanvasView(Context context){
        super(context);
    }

    @Override
    public void showSolidManager(){
        String state=NativeBRepKernel.isAvailable()?"Native C++ فعال":"Native C++ در دسترس نیست";
        String[] items={
                "▣ ابزارهای Solid / Exact Edge فعلی",
                "⚙ Native B-Rep Kernel / وضعیت موتور C++",
                "✓ Native Self-Test / تست عددی و Topology",
                "◎ تست تقاطع Sphere ↔ Sphere در C++",
                "◯ تست Plane ↔ Sphere در C++"
        };
        new AlertDialog.Builder(getContext())
                .setTitle("Solid 3D • Native Core")
                .setMessage(state+"\n\nاین لایه مرز JNI/NDK را وارد اپ می‌کند. Exact Boolean عمومی هنوز به OCCT adapter بعدی نیاز دارد؛ ابزارهای فعلی بدون حذف شدن در گزینه اول هستند.")
                .setItems(items,(d,w)->{
                    if(w==0)NativeBRepCadCanvasView.super.showSolidManager();
                    else if(w==1)showNativeStatus();
                    else if(w==2)toast(NativeBRepKernel.selfTest());
                    else if(w==3)showSphereSphereTest();
                    else showPlaneSphereTest();
                })
                .setNegativeButton("بستن",null).show();
    }

    @Override
    public String selectedInfo(){
        String base=super.selectedInfo();
        return base+(NativeBRepKernel.isAvailable()?" | Native C++":" | Java fallback");
    }

    private void showNativeStatus(){
        int flags=NativeBRepKernel.capabilityFlags();
        StringBuilder msg=new StringBuilder();
        msg.append("Backend: ").append(NativeBRepKernel.version());
        msg.append("\nABI bridge: ").append(NativeBRepKernel.isAvailable()?"✓ Loaded":"✗ Not loaded");
        if(!NativeBRepKernel.isAvailable())msg.append("\nError: ").append(NativeBRepKernel.loadError());
        msg.append("\n\nNative capabilities:");
        msg.append("\n").append((flags&1)!=0?"✓":"○").append(" Plane ↔ Sphere exact");
        msg.append("\n").append((flags&2)!=0?"✓":"○").append(" Sphere ↔ Sphere exact");
        msg.append("\n").append((flags&4)!=0?"✓":"○").append(" Analytic mass properties");
        msg.append("\n").append((flags&8)!=0?"✓":"○").append(" Topology self-test");
        msg.append("\n○ OCCT exact B-Rep Boolean adapter — مرحله بعد");
        msg.append("\n\nنمایش طول‌ها همچنان cm + mm است؛ مدل داخلی mm باقی می‌ماند.");
        new AlertDialog.Builder(getContext()).setTitle("Native B-Rep Kernel")
                .setMessage(msg.toString()).setPositiveButton("باشه",null).show();
    }

    private void showSphereSphereTest(){
        Geometry3D.Vec3 a=new Geometry3D.Vec3(0,0,0);
        Geometry3D.Vec3 b=new Geometry3D.Vec3(40,0,0);
        double[] r=NativeBRepKernel.sphereSphere(a,30,b,30);
        if(r.length!=7){toast("تقاطع Native پیدا نشد");return;}
        String msg="Sphere A: R 3 cm / 30 mm\nSphere B: R 3 cm / 30 mm\nCenter distance: 4 cm / 40 mm"
                +"\n\nIntersection circle radius: "+dual((float)r[6])
                +"\nCenter: ("+num(r[0])+", "+num(r[1])+", "+num(r[2])+") mm"
                +"\nNormal: ("+num(r[3])+", "+num(r[4])+", "+num(r[5])+")";
        new AlertDialog.Builder(getContext()).setTitle("Native Sphere ↔ Sphere")
                .setMessage(msg).setPositiveButton("باشه",null).show();
    }

    private void showPlaneSphereTest(){
        double[] r=NativeBRepKernel.planeSphere(
                new Geometry3D.Vec3(0,0,10),new Geometry3D.Vec3(0,0,1),
                new Geometry3D.Vec3(0,0,0),30);
        if(r.length!=7){toast("تقاطع Native پیدا نشد");return;}
        String msg="Sphere radius: 3 cm / 30 mm\nPlane offset: 1 cm / 10 mm"
                +"\n\nIntersection circle radius: "+dual((float)r[6])
                +"\nDiameter: "+dual((float)r[6]*2f)
                +"\nCenter: ("+num(r[0])+", "+num(r[1])+", "+num(r[2])+") mm";
        new AlertDialog.Builder(getContext()).setTitle("Native Plane ↔ Sphere")
                .setMessage(msg).setPositiveButton("باشه",null).show();
    }

    private void toast(String text){
        Toast.makeText(getContext(),text,Toast.LENGTH_LONG).show();
    }

    private static String dual(float mm){return num(mm/10f)+" cm / "+num(mm)+" mm";}
    private static String num(double v){
        String s=String.format(Locale.US,"%.4f",v);
        while(s.contains(".")&&(s.endsWith("0")||s.endsWith(".")))s=s.substring(0,s.length()-1);
        return s;
    }
}
