package ir.chobyar.sketch;

import android.content.Context;
import android.graphics.Color;
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
import com.google.android.filament.LightManager;
import com.google.android.filament.Renderer;
import com.google.android.filament.RenderableManager;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.SurfaceOrientation;
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
 * Material compilation is deliberately lazy so a blank workspace can reach its
 * first interactive frame without paying the filamat compiler cost.
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
    private Material cadMaterial;
    private boolean materialBuilderInitialized;
    private final int keyLightEntity;
    private final int fillLightEntity;
    private final UiHelper uiHelper;
    private SwapChain swapChain;
    private boolean running;
    private boolean destroyed;
    private VertexBuffer vertexBuffer;
    private IndexBuffer indexBuffer;
    private int renderableEntity;
    private int meshHash;
    private int meshLength;
    private double[] meshSnapshot=new double[0];
    private float materialRed=.38f,materialGreen=.64f,materialBlue=.90f,materialRoughness=.62f,materialMetallic=0f;
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
        keyLightEntity=EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL).color(1.0f,0.97f,0.92f).intensity(92000f)
                .direction(-0.55f,-0.70f,-0.85f).castShadows(false).build(engine,keyLightEntity);
        fillLightEntity=EntityManager.get().create();
        new LightManager.Builder(LightManager.Type.DIRECTIONAL).color(0.72f,0.84f,1.0f).intensity(38000f)
                .direction(0.65f,0.25f,0.45f).castShadows(false).build(engine,fillLightEntity);
        scene.addEntity(keyLightEntity);scene.addEntity(fillLightEntity);
        camera.lookAt(180.0,140.0,180.0,0.0,0.0,0.0,0.0,0.0,1.0);

        uiHelper=new UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK);
        uiHelper.setRenderCallback(new UiHelper.RendererCallback() {
            @Override public void onNativeWindowChanged(Surface surface) {
                if(destroyed)return;
                if(swapChain!=null)engine.destroySwapChain(swapChain);
                swapChain=engine.createSwapChain(surface);startFrames();
            }
            @Override public void onDetachedFromSurface() {
                if(destroyed)return;
                stopFrames();if(swapChain!=null){engine.destroySwapChain(swapChain);swapChain=null;}
                engine.flushAndWait();
            }
            @Override public void onResized(int width,int height) {
                if(destroyed||width<=0||height<=0)return;
                surfaceWidth=width;surfaceHeight=height;applyCameraState();
            }
        });
        uiHelper.attachTo(this);
    }

    private Material buildCadMaterial(){
        if(!materialBuilderInitialized){MaterialBuilder.init();materialBuilderInitialized=true;}
        String shader="void material(inout MaterialInputs material) { prepareMaterial(material); material.baseColor = float4("+
                materialRed+", "+materialGreen+", "+materialBlue+", 1.0); material.roughness = "+materialRoughness+
                "; material.metallic = "+materialMetallic+"; material.reflectance = 0.35; }";
        MaterialPackage materialPackage=new MaterialBuilder()
                .name("ChobYar CAD Surface")
                .shading(MaterialBuilder.Shading.LIT)
                .doubleSided(true)
                .platform(MaterialBuilder.Platform.MOBILE)
                .targetApi(MaterialBuilder.TargetApi.ALL)
                .optimization(MaterialBuilder.Optimization.PERFORMANCE)
                .material(shader)
                .build(engine);
        if(!materialPackage.isValid())throw new IllegalStateException("CAD material compilation failed");
        ByteBuffer materialBuffer=materialPackage.getBuffer();
        return new Material.Builder().payload(materialBuffer,materialBuffer.remaining()).build(engine);
    }

    void setAppearance(int color,float roughness,float metallic){
        if(destroyed)return;
        float r=Color.red(color)/255f,g=Color.green(color)/255f,b=Color.blue(color)/255f;
        roughness=Math.max(.04f,Math.min(1f,roughness));metallic=Math.max(0f,Math.min(1f,metallic));
        if(Math.abs(r-materialRed)<.001f&&Math.abs(g-materialGreen)<.001f&&Math.abs(b-materialBlue)<.001f&&Math.abs(roughness-materialRoughness)<.001f&&Math.abs(metallic-materialMetallic)<.001f)return;
        materialRed=r;materialGreen=g;materialBlue=b;materialRoughness=roughness;materialMetallic=metallic;
        // No body has been uploaded yet: keep the desired appearance and let
        // the first setMesh compile exactly one material instead of two.
        if(cadMaterial==null)return;
        double[] restore=meshSnapshot;clearMesh();Material old=cadMaterial;cadMaterial=buildCadMaterial();engine.destroyMaterial(old);meshLength=-1;meshHash=0;if(restore.length>=9)setMesh(restore);
    }

    void setCameraState(SpatialCadCanvasView.GpuCameraState state){
        if(destroyed)return;
        cameraState=state;applyCameraState();
    }

    private void applyCameraState(){
        if(destroyed)return;
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
        if(destroyed)return;
        if(xyz==null)xyz=new double[0];
        int nextHash=Arrays.hashCode(xyz);
        if(meshLength==xyz.length&&meshHash==nextHash)return;
        clearMesh();meshSnapshot=Arrays.copyOf(xyz,xyz.length);meshLength=xyz.length;meshHash=nextHash;
        int vertexCount=xyz.length/3;
        if(vertexCount<3)return;
        if(cadMaterial==null)cadMaterial=buildCadMaterial();

        ByteBuffer vertexBytes=ByteBuffer.allocateDirect(vertexCount*3*4).order(ByteOrder.nativeOrder());
        FloatBuffer vertices=vertexBytes.asFloatBuffer();
        ByteBuffer normalBytes=ByteBuffer.allocateDirect(vertexCount*3*4).order(ByteOrder.nativeOrder());
        FloatBuffer normals=normalBytes.asFloatBuffer();
        float minX=Float.POSITIVE_INFINITY,minY=minX,minZ=minX,maxX=Float.NEGATIVE_INFINITY,maxY=maxX,maxZ=maxX;
        for(int i=0;i<vertexCount*3;i+=3){
            float x=(float)xyz[i],y=(float)xyz[i+1],z=(float)xyz[i+2];vertices.put(x).put(y).put(z);
            minX=Math.min(minX,x);minY=Math.min(minY,y);minZ=Math.min(minZ,z);
            maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);maxZ=Math.max(maxZ,z);
        }
        vertices.flip();
        for(int triangle=0;triangle<vertexCount;triangle+=3){
            int k=triangle*3;
            double ax=xyz[k],ay=xyz[k+1],az=xyz[k+2];
            double ux=xyz[k+3]-ax,uy=xyz[k+4]-ay,uz=xyz[k+5]-az;
            double vx=xyz[k+6]-ax,vy=xyz[k+7]-ay,vz=xyz[k+8]-az;
            double nx=uy*vz-uz*vy,ny=uz*vx-ux*vz,nz=ux*vy-uy*vx;
            double length=Math.max(1e-12,Math.sqrt(nx*nx+ny*ny+nz*nz));
            float fx=(float)(nx/length),fy=(float)(ny/length),fz=(float)(nz/length);
            for(int corner=0;corner<3;corner++)normals.put(fx).put(fy).put(fz);
        }
        normals.flip();
        ByteBuffer tangentBytes=ByteBuffer.allocateDirect(vertexCount*4*4).order(ByteOrder.nativeOrder());
        FloatBuffer tangents=tangentBytes.asFloatBuffer();
        SurfaceOrientation orientation=new SurfaceOrientation.Builder().vertexCount(vertexCount).normals(normals).build();
        orientation.getQuatsAsFloat(tangents);orientation.destroy();tangents.rewind();
        ByteBuffer indexBytes=ByteBuffer.allocateDirect(vertexCount*4).order(ByteOrder.nativeOrder());
        IntBuffer indices=indexBytes.asIntBuffer();for(int i=0;i<vertexCount;i++)indices.put(i);indices.flip();

        vertexBuffer=new VertexBuffer.Builder().bufferCount(2).vertexCount(vertexCount)
                .attribute(VertexBuffer.VertexAttribute.POSITION,0,VertexBuffer.AttributeType.FLOAT3,0,12)
                .attribute(VertexBuffer.VertexAttribute.TANGENTS,1,VertexBuffer.AttributeType.FLOAT4,0,16).build(engine);
        vertexBuffer.setBufferAt(engine,0,vertices);
        vertexBuffer.setBufferAt(engine,1,tangents);
        indexBuffer=new IndexBuffer.Builder().indexCount(vertexCount).bufferType(IndexBuffer.Builder.IndexType.UINT).build(engine);
        indexBuffer.setBuffer(engine,indices);

        float cx=(minX+maxX)*0.5f,cy=(minY+maxY)*0.5f,cz=(minZ+maxZ)*0.5f;
        float hx=Math.max(0.001f,(maxX-minX)*0.5f),hy=Math.max(0.001f,(maxY-minY)*0.5f),hz=Math.max(0.001f,(maxZ-minZ)*0.5f);
        renderableEntity=EntityManager.get().create();
        new RenderableManager.Builder(1).boundingBox(new Box(cx,cy,cz,hx,hy,hz))
                .material(0,cadMaterial.getDefaultInstance())
                .geometry(0,RenderableManager.PrimitiveType.TRIANGLES,vertexBuffer,indexBuffer,0,vertexCount)
                .culling(false).build(engine,renderableEntity);
        scene.addEntity(renderableEntity);applyCameraState();
    }

    private void clearMesh(){
        if(renderableEntity!=0){scene.removeEntity(renderableEntity);engine.destroyEntity(renderableEntity);EntityManager.get().destroy(renderableEntity);renderableEntity=0;}
        if(vertexBuffer!=null){engine.destroyVertexBuffer(vertexBuffer);vertexBuffer=null;}
        if(indexBuffer!=null){engine.destroyIndexBuffer(indexBuffer);indexBuffer=null;}
    }

    private void startFrames(){if(destroyed||running)return;running=true;Choreographer.getInstance().postFrameCallback(this);}
    private void stopFrames(){running=false;Choreographer.getInstance().removeFrameCallback(this);}

    @Override public void doFrame(long frameTimeNanos) {
        if(destroyed||!running)return;
        if(swapChain!=null&&uiHelper.isReadyToRender()&&renderer.beginFrame(swapChain,frameTimeNanos)){renderer.render(view);renderer.endFrame();}
        if(!destroyed&&running)Choreographer.getInstance().postFrameCallback(this);
    }

    void destroyRenderer(){
        if(destroyed)return;
        // Publish terminal ownership before UiHelper.detach(): detach can invoke
        // onDetachedFromSurface synchronously, and that callback must never touch
        // an Engine whose destruction is already in progress.
        destroyed=true;
        stopFrames();uiHelper.detach();
        if(swapChain!=null){engine.destroySwapChain(swapChain);swapChain=null;}
        clearMesh();scene.removeEntity(keyLightEntity);scene.removeEntity(fillLightEntity);
        engine.destroyEntity(keyLightEntity);engine.destroyEntity(fillLightEntity);
        EntityManager.get().destroy(keyLightEntity);EntityManager.get().destroy(fillLightEntity);
        if(cadMaterial!=null){engine.destroyMaterial(cadMaterial);cadMaterial=null;}
        if(materialBuilderInitialized){MaterialBuilder.shutdown();materialBuilderInitialized=false;}
        scene.setSkybox(null);engine.destroySkybox(skybox);engine.destroyCameraComponent(cameraEntity);
        engine.destroyView(view);engine.destroyScene(scene);engine.destroyRenderer(renderer);
        EntityManager.get().destroy(cameraEntity);engine.destroy();
    }
}
