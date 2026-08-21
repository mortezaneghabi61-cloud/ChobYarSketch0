package ir.chobyar.sketch;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

/**
 * Versioned persistence adapter for the workspace Reference Image.
 *
 * The bitmap is embedded as lossless PNG so a .chobyar project is portable and
 * never depends on a transient Android content Uri. Plane registration and all
 * calibration values are persisted separately from the exact OCCT feature graph.
 */
final class ReferenceImageProjectAdapter {
    static final int VERSION=1;
    private static final int MAX_SIDE_PX=8192;
    private static final long MAX_PIXELS=16_000_000L;
    private static final int MAX_PNG_BYTES=24*1024*1024;
    private static final int MAX_BASE64_CHARS=((MAX_PNG_BYTES+2)/3)*4+16;

    static final class Decoded {
        final Bitmap bitmap;
        final String name;
        final Geometry3D.Plane3D plane;
        final float widthMm,centerU,centerV,rotationDeg,opacity;
        final boolean visible;
        Decoded(Bitmap bitmap,String name,Geometry3D.Plane3D plane,float widthMm,float centerU,float centerV,float rotationDeg,float opacity,boolean visible){
            this.bitmap=bitmap;this.name=name;this.plane=plane;this.widthMm=widthMm;this.centerU=centerU;this.centerV=centerV;
            this.rotationDeg=rotationDeg;this.opacity=opacity;this.visible=visible;
        }
    }

    private ReferenceImageProjectAdapter(){}

    static String exportState(Shapr3DGuideCadCanvasView cad){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        try{
            Object image=field(SpatialCadCanvasView.class,"referenceImage").get(cad);
            if(image==null)return null;
            Bitmap bitmap=(Bitmap)value(image,"bitmap");
            validateBitmapShape(bitmap);
            ByteArrayOutputStream out=new ByteArrayOutputStream(Math.min(MAX_PNG_BYTES,Math.max(4096,bitmap.getWidth()*bitmap.getHeight()/2)));
            if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,out))throw new IllegalStateException("Reference Image PNG encoding failed");
            byte[] png=out.toByteArray();
            if(png.length==0||png.length>MAX_PNG_BYTES)throw new IllegalStateException("Reference Image is too large for a portable project file");

            JSONObject root=new JSONObject();
            root.put("version",VERSION);
            root.put("encoding","png-base64");
            root.put("widthPx",bitmap.getWidth());
            root.put("heightPx",bitmap.getHeight());
            root.put("imageData",Base64.encodeToString(png,Base64.NO_WRAP));
            root.put("name",safeName(String.valueOf(value(image,"name"))));
            root.put("plane",ExactModelProjectState.plane("reference",(Geometry3D.Plane3D)value(image,"plane")));
            root.put("widthMm",finite(number(value(image,"widthMm")),"Reference Image width"));
            root.put("centerU",finite(number(value(image,"centerU")),"Reference Image U position"));
            root.put("centerV",finite(number(value(image,"centerV")),"Reference Image V position"));
            root.put("rotationDeg",finite(number(value(image,"rotationDeg")),"Reference Image rotation"));
            root.put("opacity",finite(number(value(image,"opacity")),"Reference Image opacity"));
            root.put("visible",bool(value(image,"visible")));
            String raw=root.toString();
            validate(raw,false);
            return raw;
        }catch(IllegalArgumentException|IllegalStateException e){throw e;}
        catch(Exception e){throw new IllegalStateException("Reference Image could not be exported",e);}
    }

    /** Parse and validate without allocating the full decoded bitmap. */
    static void validate(String raw){validate(raw,false);}

    static void restore(Shapr3DGuideCadCanvasView cad,String raw){
        if(cad==null)throw new IllegalArgumentException("CAD workspace is missing");
        Decoded decoded=validate(raw,true);
        try{
            Class<?> imageClass=Class.forName("ir.chobyar.sketch.SpatialCadCanvasView$ReferenceImage");
            Constructor<?> ctor=imageClass.getDeclaredConstructor(Bitmap.class,String.class,Geometry3D.Plane3D.class);
            ctor.setAccessible(true);
            Object image=ctor.newInstance(decoded.bitmap,decoded.name,decoded.plane);
            setFloat(imageClass,image,"widthMm",decoded.widthMm);
            setFloat(imageClass,image,"centerU",decoded.centerU);
            setFloat(imageClass,image,"centerV",decoded.centerV);
            setFloat(imageClass,image,"rotationDeg",decoded.rotationDeg);
            setFloat(imageClass,image,"opacity",decoded.opacity);
            Field visible=imageClass.getDeclaredField("visible");visible.setAccessible(true);visible.setBoolean(image,decoded.visible);
            Field target=field(SpatialCadCanvasView.class,"referenceImage");target.set(cad,image);
            cad.invalidate();
        }catch(RuntimeException e){if(decoded.bitmap!=null&&!decoded.bitmap.isRecycled())decoded.bitmap.recycle();throw e;}
        catch(Exception e){if(decoded.bitmap!=null&&!decoded.bitmap.isRecycled())decoded.bitmap.recycle();throw new IllegalStateException("Reference Image could not be restored",e);}
    }

    private static Decoded validate(String raw,boolean decodeBitmap){
        if(raw==null||raw.trim().isEmpty())throw new IllegalArgumentException("Reference Image state is empty");
        try{
            JSONObject root=new JSONObject(raw);
            if(root.optInt("version",-1)!=VERSION)throw new IllegalArgumentException("Unsupported Reference Image state version");
            if(!"png-base64".equals(root.optString("encoding","")))throw new IllegalArgumentException("Unsupported Reference Image encoding");
            int width=root.optInt("widthPx",0),height=root.optInt("heightPx",0);
            validateDimensions(width,height);
            String encoded=root.optString("imageData","");
            if(encoded.isEmpty()||encoded.length()>MAX_BASE64_CHARS)throw new IllegalArgumentException("Reference Image payload is too large");
            byte[] png;
            try{png=Base64.decode(encoded,Base64.DEFAULT);}catch(IllegalArgumentException e){throw new IllegalArgumentException("Reference Image payload is not valid Base64",e);}
            if(png.length==0||png.length>MAX_PNG_BYTES)throw new IllegalArgumentException("Reference Image PNG payload is too large");

            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
            BitmapFactory.decodeByteArray(png,0,png.length,bounds);
            if(bounds.outWidth!=width||bounds.outHeight!=height)throw new IllegalArgumentException("Reference Image pixel dimensions do not match the project metadata");
            validateDimensions(bounds.outWidth,bounds.outHeight);

            String name=safeName(root.optString("name","Reference Image"));
            JSONObject planeJson=root.optJSONObject("plane");if(planeJson==null)throw new IllegalArgumentException("Reference Image plane is missing");
            Geometry3D.Plane3D plane=ExactModelProjectState.planeFromJson(planeJson);
            float widthMm=(float)finite(root.optDouble("widthMm",Double.NaN),"Reference Image width");
            float centerU=(float)finite(root.optDouble("centerU",Double.NaN),"Reference Image U position");
            float centerV=(float)finite(root.optDouble("centerV",Double.NaN),"Reference Image V position");
            float rotation=(float)finite(root.optDouble("rotationDeg",Double.NaN),"Reference Image rotation");
            float opacity=(float)finite(root.optDouble("opacity",Double.NaN),"Reference Image opacity");
            if(widthMm<1f||widthMm>1_000_000f)throw new IllegalArgumentException("Reference Image width is invalid");
            if(opacity<0f||opacity>1f)throw new IllegalArgumentException("Reference Image opacity is invalid");
            if(Math.abs(centerU)>10_000_000f||Math.abs(centerV)>10_000_000f||Math.abs(rotation)>10_000_000f)throw new IllegalArgumentException("Reference Image placement is invalid");
            boolean visible=root.optBoolean("visible",true);

            Bitmap bitmap=null;
            if(decodeBitmap){
                bitmap=BitmapFactory.decodeByteArray(png,0,png.length);
                if(bitmap==null||bitmap.getWidth()!=width||bitmap.getHeight()!=height)throw new IllegalArgumentException("Reference Image PNG could not be decoded");
            }
            return new Decoded(bitmap,name,plane,widthMm,centerU,centerV,rotation,opacity,visible);
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new IllegalArgumentException("Malformed Reference Image project state",e);}
    }

    private static void validateBitmapShape(Bitmap bitmap){
        if(bitmap==null||bitmap.isRecycled())throw new IllegalArgumentException("Reference Image bitmap is missing");
        validateDimensions(bitmap.getWidth(),bitmap.getHeight());
    }

    private static void validateDimensions(int width,int height){
        if(width<2||height<2||width>MAX_SIDE_PX||height>MAX_SIDE_PX||(long)width*height>MAX_PIXELS)
            throw new IllegalArgumentException("Reference Image pixel dimensions are outside the supported project limit");
    }

    private static String safeName(String name){
        String value=name==null?"Reference Image":name.trim();
        if(value.isEmpty())value="Reference Image";
        if(value.length()>200)throw new IllegalArgumentException("Reference Image name is too long");
        return value;
    }

    private static double finite(double value,String label){
        if(!Double.isFinite(value))throw new IllegalArgumentException(label+" is invalid");return value;
    }

    private static Field field(Class<?> owner,String name)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);return f;}
    private static Object value(Object object,String name)throws Exception{Field f=object.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(object);}
    private static double number(Object value){return value instanceof Number?((Number)value).doubleValue():Double.parseDouble(String.valueOf(value));}
    private static boolean bool(Object value){return value instanceof Boolean&&(Boolean)value;}
    private static void setFloat(Class<?> owner,Object object,String name,float value)throws Exception{Field f=owner.getDeclaredField(name);f.setAccessible(true);f.setFloat(object,value);}
}
