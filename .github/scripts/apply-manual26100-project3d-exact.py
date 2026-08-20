from pathlib import Path

ROOT = Path('.')


def replace_once(text, old, new, label):
    if new in text:
        return text
    if old not in text:
        raise SystemExit(f'{label}: anchor not found')
    return text.replace(old, new, 1)


def patch(path, transform):
    p = ROOT / path
    text = p.read_text(encoding='utf-8')
    out = transform(text)
    if out != text:
        p.write_text(out, encoding='utf-8')
        print('patched', path)
    else:
        print('already patched', path)


def patch_cad(text):
    anchor = '''    protected final void coreObserveScaleGesture(MotionEvent event) {
        scaleDetector.onTouchEvent(event);
    }
'''
    block = anchor + '''
    /** Adds exact projected/reference sketch geometry without exposing private entity classes. */
    protected final Entity coreAddConstructionLine(float x1,float y1,float x2,float y2) {
        Entity e=new LineEntity(x1,y1,x2,y2);e.setConstruction(true);addPrepared(e);return e;
    }
    protected final Entity coreAddConstructionCircle(float cx,float cy,float radius) {
        Entity e=new CircleEntity(cx,cy,Math.abs(radius));e.setConstruction(true);addPrepared(e);return e;
    }
    protected final Entity coreAddConstructionArc(float cx,float cy,float radius,float startDeg,float sweepDeg) {
        Entity e=new ArcEntity(cx,cy,Math.abs(radius),startDeg,sweepDeg);e.setConstruction(true);addPrepared(e);return e;
    }
'''
    return replace_once(text, anchor, block, 'CadCanvasView projected-entity contract')


def patch_kernel_java(text):
    text = replace_once(
        text,
        '''    static final int OCCT_PROFILE_POLYGON = 0;\n    static final int OCCT_PROFILE_CIRCLE = 1;\n''',
        '''    static final int OCCT_PROFILE_POLYGON = 0;\n    static final int OCCT_PROFILE_CIRCLE = 1;\n\n    // Exact OCCT edge descriptor contract. Every record has 18 doubles:\n    // kind,index,p1.xyz,p2.xyz,center.xyz,normal.xyz,radius,first,last,orientation.\n    static final int OCCT_EDGE_UNSUPPORTED = 0;\n    static final int OCCT_EDGE_LINE = 1;\n    static final int OCCT_EDGE_CIRCLE = 2;\n    static final int OCCT_EDGE_ARC = 3;\n    static final int OCCT_EDGE_RECORD_SIZE = 18;\n''',
        'NativeBRepKernel edge constants')

    anchor = '''    static double[] occtTriangulate(long handle,double deflectionMm){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctTriangulate(handle,deflectionMm);}catch(Throwable t){return new double[0];}
    }
'''
    block = anchor + '''
    /** Exact analytic B-Rep edges; never reconstructed from the display mesh. */
    static double[] occtEdgeDescriptors(long handle){
        if(!occtAvailable()||handle==0L)return new double[0];
        try{return nativeOcctEdgeDescriptors(handle);}catch(Throwable t){return new double[0];}
    }
'''
    text = replace_once(text, anchor, block, 'NativeBRepKernel edge descriptor wrapper')

    anchor2 = '''    private static native double[] nativeOcctShapeStats(long handle);
    private static native double[] nativeOcctTriangulate(long handle,double deflection);
'''
    block2 = '''    private static native double[] nativeOcctShapeStats(long handle);
    private static native double[] nativeOcctTriangulate(long handle,double deflection);
    private static native double[] nativeOcctEdgeDescriptors(long handle);
'''
    return replace_once(text, anchor2, block2, 'NativeBRepKernel edge descriptor JNI declaration')


def patch_cpp(text):
    text = replace_once(text, '#include <BRepAdaptor_Surface.hxx>\n', '#include <BRepAdaptor_Surface.hxx>\n#include <BRepAdaptor_Curve.hxx>\n', 'OCCT BRepAdaptor_Curve include')
    text = replace_once(text, '#include <GeomAbs_SurfaceType.hxx>\n', '#include <GeomAbs_SurfaceType.hxx>\n#include <GeomAbs_CurveType.hxx>\n', 'OCCT GeomAbs_CurveType include')
    text = replace_once(text, '#include <gp_Ax2.hxx>\n', '#include <gp_Ax2.hxx>\n#include <gp_Circ.hxx>\n', 'OCCT gp_Circ include')

    anchor = '''extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctShapeStats(JNIEnv* env, jclass, jlong handle) {
'''
    function = r'''extern "C" JNIEXPORT jdoubleArray JNICALL
Java_ir_chobyar_sketch_NativeBRepKernel_nativeOcctEdgeDescriptors(JNIEnv* env, jclass, jlong handle) {
#ifdef CHOBYAR_WITH_OCCT
    TopoDS_Shape shape;if(!loadShape(handle,shape))return emptyArray(env);
    try {
        constexpr int RECORD=18;
        std::vector<double> data;
        const int edgeCount=countSubShapes(shape,TopAbs_EDGE);
        data.reserve(static_cast<size_t>(std::max(0,edgeCount))*RECORD);
        int edgeIndex=0;
        for(TopExp_Explorer ex(shape,TopAbs_EDGE);ex.More();ex.Next(),++edgeIndex){
            const TopoDS_Edge edge=TopoDS::Edge(ex.Current());
            BRepAdaptor_Curve curve(edge);
            const double first=curve.FirstParameter(),last=curve.LastParameter();
            if(!std::isfinite(first)||!std::isfinite(last))continue;
            const gp_Pnt p1=curve.Value(first),p2=curve.Value(last);
            int kind=0;
            gp_Pnt center(0.0,0.0,0.0);gp_Dir normal(0.0,0.0,1.0);double radius=0.0;
            const GeomAbs_CurveType type=curve.GetType();
            if(type==GeomAbs_Line){kind=1;}
            else if(type==GeomAbs_Circle){
                const gp_Circ circle=curve.Circle();center=circle.Location();normal=circle.Axis().Direction();radius=circle.Radius();
                const double span=std::abs(last-first);kind=std::abs(span-2.0*PI)<1.0e-6?2:3;
            }
            const double orientation=edge.Orientation()==TopAbs_REVERSED?-1.0:1.0;
            const double rec[RECORD]={static_cast<double>(kind),static_cast<double>(edgeIndex),p1.X(),p1.Y(),p1.Z(),p2.X(),p2.Y(),p2.Z(),center.X(),center.Y(),center.Z(),normal.X(),normal.Y(),normal.Z(),radius,first,last,orientation};
            data.insert(data.end(),rec,rec+RECORD);
        }
        if(data.empty())return emptyArray(env);
        jdoubleArray out=env->NewDoubleArray(static_cast<jsize>(data.size()));
        if(out)env->SetDoubleArrayRegion(out,0,static_cast<jsize>(data.size()),data.data());
        return out?out:emptyArray(env);
    }catch(...){return emptyArray(env);}
#else
    (void)handle;return emptyArray(env);
#endif
}

'''
    return replace_once(text, anchor, function + anchor, 'OCCT exact edge descriptor JNI')


def patch_guide(text):
    anchor = '''    @Override
    public boolean onTouchEvent(MotionEvent event){
'''
    block = r'''    @Override
    public String executeCommand(String raw){
        if(raw!=null){
            String s=raw.trim().replace(',',' ');
            if("PROJECT3D".equalsIgnoreCase(s)||"PROJECTBODY".equalsIgnoreCase(s))return projectExactBodyEdges();
        }
        return super.executeCommand(raw);
    }

    /** Manual 26.100 Project: exact OCCT Line/Circle/Arc edges -> Construction sketch geometry. */
    public String projectExactBodyEdges(){
        List<double[]> batches=new ArrayList<>();
        for(long handle:nativeHandles()){
            double[] d=NativeBRepKernel.occtEdgeDescriptors(handle);
            if(d!=null&&d.length>=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE)batches.add(d);
        }
        if(batches.isEmpty())return "Project 3D • Shape دقیق OCCT آماده نیست";
        return projectDescriptorBatches(batches);
    }

    /** Package-visible deterministic mapper used by x86 emulator tests. */
    String projectExactDescriptorsForTest(double[] descriptors){
        List<double[]> batches=new ArrayList<>();
        if(descriptors!=null)batches.add(descriptors);
        return projectDescriptorBatches(batches);
    }

    private String projectDescriptorBatches(List<double[]> batches){
        Geometry3D.Plane3D plane=activePlane();
        if(plane==null)return "Project 3D • Sketch Plane فعال نیست";
        Set<String> lineKeys=new HashSet<>(),circleKeys=new HashSet<>(),arcKeys=new HashSet<>();
        int lines=0,circles=0,arcs=0,unsupported=0,skipped=0;
        boolean undoSaved=false;
        final int n=NativeBRepKernel.OCCT_EDGE_RECORD_SIZE;
        for(double[] d:batches){
            if(d==null)continue;
            for(int i=0;i+n-1<d.length;i+=n){
                int kind=(int)Math.round(d[i]);
                Geometry3D.Vec3 p1=new Geometry3D.Vec3((float)d[i+2],(float)d[i+3],(float)d[i+4]);
                Geometry3D.Vec3 p2=new Geometry3D.Vec3((float)d[i+5],(float)d[i+6],(float)d[i+7]);
                if(kind==NativeBRepKernel.OCCT_EDGE_LINE){
                    PointF a=toLocal(plane,p1),b=toLocal(plane,p2);
                    if(Math.hypot(a.x-b.x,a.y-b.y)<1.0e-4){skipped++;continue;}
                    String key=projectLineKey(a,b);if(!lineKeys.add(key)){skipped++;continue;}
                    if(!undoSaved){coreSaveUndo();undoSaved=true;} coreAddConstructionLine(a.x,a.y,b.x,b.y);lines++;continue;
                }
                if(kind==NativeBRepKernel.OCCT_EDGE_CIRCLE||kind==NativeBRepKernel.OCCT_EDGE_ARC){
                    Geometry3D.Vec3 center3=new Geometry3D.Vec3((float)d[i+8],(float)d[i+9],(float)d[i+10]);
                    Geometry3D.Vec3 normal3=new Geometry3D.Vec3((float)d[i+11],(float)d[i+12],(float)d[i+13]);
                    float radius=Math.abs((float)d[i+14]);
                    if(radius<1.0e-5f||normal3.length()<1.0e-6f){unsupported++;continue;}
                    float alignment=normal3.normalized().dot(plane.normal.normalized());
                    if(Math.abs(Math.abs(alignment)-1f)>1.0e-4f){unsupported++;continue;}
                    PointF c=toLocal(plane,center3);
                    if(kind==NativeBRepKernel.OCCT_EDGE_CIRCLE){
                        String key=projectCircleKey(c,radius);if(!circleKeys.add(key)){skipped++;continue;}
                        if(!undoSaved){coreSaveUndo();undoSaved=true;} coreAddConstructionCircle(c.x,c.y,radius);circles++;continue;
                    }
                    PointF startPoint=toLocal(plane,p1);
                    float start=(float)Math.toDegrees(Math.atan2(startPoint.y-c.y,startPoint.x-c.x));
                    float span=(float)Math.toDegrees(Math.abs(d[i+16]-d[i+15]));
                    if(!(span>1.0e-4f)||span>=359.999f){unsupported++;continue;}
                    float sweep=alignment>=0f?span:-span;
                    String key=projectArcKey(c,radius,start,sweep);if(!arcKeys.add(key)){skipped++;continue;}
                    if(!undoSaved){coreSaveUndo();undoSaved=true;} coreAddConstructionArc(c.x,c.y,radius,start,sweep);arcs++;continue;
                }
                unsupported++;
            }
        }
        if(undoSaved)invalidate();
        return "Project 3D • Line "+lines+" • Circle "+circles+" • Arc "+arcs+" • Unsupported "+unsupported+" • Skipped "+skipped;
    }

    private static String projectLineKey(PointF a,PointF b){String x=projectPointKey(a),y=projectPointKey(b);return x.compareTo(y)<=0?x+"|"+y:y+"|"+x;}
    private static String projectPointKey(PointF p){return Math.round(p.x*100f)+":"+Math.round(p.y*100f);}
    private static String projectCircleKey(PointF c,float r){return projectPointKey(c)+":"+Math.round(r*100f);}
    private static String projectArcKey(PointF c,float r,float start,float sweep){return projectCircleKey(c,r)+":"+Math.round(start*100f)+":"+Math.round(sweep*100f);}

'''
    return replace_once(text, anchor, block + anchor, 'Shapr3DGuide exact Project command')

patch('app/src/main/java/ir/chobyar/sketch/CadCanvasView.java', patch_cad)
patch('app/src/main/java/ir/chobyar/sketch/NativeBRepKernel.java', patch_kernel_java)
patch('app/src/main/cpp/occt_brep_jni.cpp', patch_cpp)
patch('app/src/main/java/ir/chobyar/sketch/Shapr3DGuideCadCanvasView.java', patch_guide)
print('Manual 26.100 exact Project 3D patch ready')
