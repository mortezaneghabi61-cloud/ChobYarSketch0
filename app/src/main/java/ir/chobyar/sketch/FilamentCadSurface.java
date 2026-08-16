package ir.chobyar.sketch;

import android.content.Context;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceView;

import com.google.android.filament.Camera;
import com.google.android.filament.Engine;
import com.google.android.filament.EntityManager;
import com.google.android.filament.Filament;
import com.google.android.filament.Renderer;
import com.google.android.filament.Scene;
import com.google.android.filament.Skybox;
import com.google.android.filament.SwapChain;
import com.google.android.filament.View;
import com.google.android.filament.android.UiHelper;

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
    private final UiHelper uiHelper;
    private SwapChain swapChain;
    private boolean running;

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
                view.setViewport(new com.google.android.filament.Viewport(0,0,width,height));
                camera.setProjection(45.0,(double)width/(double)height,0.1,5000.0, Camera.Fov.VERTICAL);
            }
        });
        uiHelper.attachTo(this);
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
        scene.setSkybox(null);engine.destroySkybox(skybox);engine.destroyCameraComponent(cameraEntity);
        engine.destroyView(view);engine.destroyScene(scene);engine.destroyRenderer(renderer);
        EntityManager.get().destroy(cameraEntity);engine.destroy();
    }
}
