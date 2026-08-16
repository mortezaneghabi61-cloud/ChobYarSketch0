package ir.chobyar.sketch;

import android.content.Context;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceView;

import com.google.android.filament.Camera;
import com.google.android.filament.Box;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.IndexBuffer;
import com.google.android.filament.Material;
import com.google.android.filament.Renderer;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.VertexBuffer;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;
import com.google.android.filament.filamat.MaterialBuilder;
import com.google.android.filament.filamat.MaterialPackage;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * Production GPU surface for the CAD workspace.
 *
 * Filament selects Vulkan where the device supports it and retains its Android
 * OpenGL ES backend as a compatibility path. OCCT remains the geometry source;
 * this class owns only GPU resources, frame scheduling and the physical camera.
 */
final class FilamentCadSurface extends SurfaceView implements Choreographer.FrameCallback {
    private static boolean initialized;

    private final Engine engine;
    private final Renderer renderer;
    private final Scene scene;
    private final View view;
    private final Camera camera;
    private final int cameraEntity;
    private final Skybox skybox;
    private final Material cadMaterial;
    private final UiHelper uiHelper;
    private SwapChain swapChain;
    private boolean running;
    private VertexBuffer vertexBuffer;
    private IndexBuffer indexBuffer;
    private int renderableEntity;
    private int meshHash;
    private int meshLength;
    private int surfaceWidth,surfaceHeight;
    private SpatialCadCanvasView.GpuCameraState cameraState;

    FilamentCadSurface(Context context) {
        super(context);
        synchronized (FilamentCadSurface.class) {
            if (!initialized) { Filament.init(); initialized=true; }
        }
        engine=Engine.create();
        renderer=engine.createRenderer();
        scene=engine.createScene();
        view=engine.createView();
        cameraEntity=EntityManager.get().create();
        camera=engine.createCamera(cameraEntity);
        skybox=new Skybox.Builder().color(0.965f,0.972f,0.982f,1.0f).build(engine);
        scene.setSkybox(skybox);view.setScene(scene);view.setCamera(camera);
        MaterialBuilder.init();
        MaterialPackage materialPackage=new MaterialBuilder()
                .name("ChobYar CAD Surface")
                .shading(MaterialBuilder.Shading.UNLIT)
                .doubleSided(true)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .material("void material(inout MaterialInputs material) { prepareMaterial(material); material.baseColor = float4(0.34, 0.60, 0.88, 1.0); }")
                .build(engine);
        if(!materialPackage.isValid())throw new IllegalStateException("CAD material compilation failed");
        ByteBuffer materialBuffer=materialPackage.getBuffer();
        cadMaterial=new Material.Builder().payload(materialBuffer,materialBuffer.remaining()).build(engine);
        camera.lookAt(180.0,140.0,180.0,0.0,0.0,0.0,0.0,0.0,1.0);

        uiHelper=new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
        uiHelper.setRenderCallback(new UiHelper.RendererCallback() {
            @Override public void onNativeWindowChanged(Surface surface) {
                if(swapChain!=null)engine.destroySwapChain(swapChain);
                swapChain=engine.createSwapChain(surface);startFrames();
            }
            @Override public void onDetachedFromSurface() {
                stopFrames();if(swapChain!=null){engine.destroySwapChain(swapChain);swapChain=null;}
                engine.flushAndWait();
            }
            @Override public void onResized(int width,int height) {
                if(width<=0||height<=0)return;
                surfaceWidth=width;surfaceHeight=height;applyCameraState();
            }
        });
        uiHelper.attachTo(this);
    }

    void setCameraState(SpatialCadCanvasView.GpuCameraState state){cameraState=state;applyCameraState();}

    private void applyCameraState(){
        SpatialCadCanvasView.GpuCameraState s=cameraState;
        if(surfaceWidth<=0||surfaceHeight<=0||s==null)return;
        int left=Math.max(0,Math.round(s.left)),top=Math.max(0,Math.round(s.top));
        int right=Math.min(surfaceWidth,Math.round(s.right)),bottom=Math.min(surfaceHeight,Math.round(s.bottom));
        int width=Math.max(1,right-left),height=Math.max(1,bottom-top);
        view.setViewport(new com.google.android.filament.Viewport(left,Math.max(0,surfaceHeight-bottom),width,height));
        setVisibility(s.visible?VISIBLE:INVISIBLE);
        float pixelsPerUnit=Math.max(0.0001f,s.scale*Math.min(width,height)/260f);
        double halfWidth=width/(2.0*pixelsPerUnit),halfHeight=height/(2.0*pixelsPerUnit);
        camera.setProjection(Camera.Projection.ORTHO,-halfWidth,halfWidth,-halfHeight,halfHeight,0.1,100000.0);
        camera.setShift(2.0*s.panX/width,-2.0*s.panY/height);

        double yaw=Math.toRadians(s.yaw),pitch=Math.toRadians(s.pitch);
        double sy=Math.sin(yaw),cy=Math.cos(yaw),sp=Math.sin(pitch),cp=Math.cos(pitch);
        double forwardX=sp*sy,forwardY=sp*cy,forwardZ=cp;
        double upX=-sy*cp,upY=-cy*cp,upZ=sp;
        double distance=10000.0;
        camera.lookAt(s.targetX-forwardX*distance,s.targetY-forwardY*distance,s.targetZ-forwardZ*distance,
                s.targetX,s.targetY,s.targetZ,upX,upY,upZ);
    }

    /** Uploads non-indexed OCCT triangles (xyz xyz xyz per triangle) to the GPU. */
    void setMesh(double[] xyz){
        if(xyz==null)xyz=new double[0];
        int nextHash=Arrays.hashCode(xyz);
        if(meshLength==xyz.length&&meshHash==nextHash)return;
        clearMesh();meshLength=xyz.length;meshHash=nextHash;
        int vertexCount=xyz.length/3;
        if(vertexCount<3)return;

        ByteBuffer vertexBytes=ByteBuffer.allocateDirect(vertexCount*3*4).order(ByteOrder.nativeOrder());
        FloatBuffer vertices=vertexBytes.asFloatBuffer();
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(int i=0;i<vertexCount*3;i+=3){
            float x=(float)xyz[i],y=(float)xyz[i+1],z=(float)xyz[i+2];vertices.put(x).put(y).put(z);
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        vertices.flip();
        ByteBuffer indexBytes=ByteBuffer.allocateDirect(vertexCount*4).order(ByteOrder.nativeOrder());
        IntBuffer indices=indexBytes.asIntBuffer();for(int i=0;i<vertexCount;i++)indices.put(i);indices.flip();

        vertexBuffer=new VertexBuffer.Builder().bufferCount(1).vertexCount(vertexCount)
                .attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,12).build(engine);
        vertexBuffer.setBufferAt(engine,0,vertices);
        indexBuffer=new IndexBuffer.Builder().indexCount(vertexCount).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine);
        indexBuffer.setBuffer(engine,indices);

        float cx=(minX+maxX)*0.5f,cy=(minY+maxY)*0.5f,cz=(minZ+maxZ)*0.5f;
        float hx=Math.max(0.001f,(maxX-minX)*0.5f),hy=Math.max(0.001f,(maxY-minY)*0.5f),hz=Math.max(0.001f,(maxZ-minZ)*0.5f);
        renderableEntity=EntityManager.get().create();
        new RenderableManager.Builder(1).boundingBox(new Box(cx,cy,cz,hx,hy,hz))
                .material(0,cadMaterial.getDefaultInstance())
                .geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vertexBuffer,indexBuffer,0,vertexCount)
                .culling(false).build(engine,renderableEntity);
        scene.addEntity(renderableEntity);
        applyCameraState();
    }

    private void clearMesh(){
        if(renderableEntity!=0){scene.removeEntity(renderableEntity);engine.destroyEntity(renderableEntity);EntityManager.get().destroy(renderableEntity);renderableEntity=0;}
        if(vertexBuffer!=null){engine.destroyVertexBuffer(vertexBuffer);vertexBuffer=null;}
        if(indexBuffer!=null){engine.destroyIndexBuffer(indexBuffer);indexBuffer=null;}
    }

    private void startFrames(){if(running)return;running=true;Choreographer.getInstance().postFrameCallback(this);}
    private void stopFrames(){running=false;Choreographer.getInstance().removeFrameCallback(this);}

    @Override public void doFrame(long frameTimeNanos) {
        if(!running)return;
        if(swapChain!=null&&uiHelper.isReadyToRender()&&renderer.beginFrame(swapChain,frameTimeNanos)){
            renderer.render(view);renderer.endFrame();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    void destroyRenderer(){
        stopFrames();uiHelper.detach();
        if(swapChain!=null){engine.destroySwapChain(swapChain);swapChain=null;}
        clearMesh();engine.destroyMaterial(cadMaterial);MaterialBuilder.shutdown();
        scene.setSkybox(null);engine.destroySkybox(skybox);engine.destroyCameraComponent(cameraEntity);
        engine.destroyView(view);engine.destroyScene(scene);engine.destroyRenderer(renderer);
        EntityManager.get().destroy(cameraEntity);engine.destroy();
    }
}
