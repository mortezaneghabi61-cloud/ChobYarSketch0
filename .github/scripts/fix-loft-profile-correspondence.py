from pathlib import Path

SOLID = Path('app/src/main/java/ir/chobyar/sketch/SolidCSG.java')
TEST = Path('app/src/androidTest/java/ir/chobyar/sketch/LoftCommandInstrumentationTest.java')

text = SOLID.read_text(encoding='utf-8')
old = '''        List<PointF> a=cleanProfile(rawA),b=cleanProfile(rawB);\n        if(a.size()<3||b.size()<3)return empty();\n        int n=Math.max(8,Math.min(128,Math.max(sampleCount,Math.max(a.size(),b.size()))));\n        a=resampleClosed(a,n);b=resampleClosed(b,n);\n        if(signedArea(a)<0f)Collections.reverse(a);\n        if(signedArea(b)<0f)Collections.reverse(b);\n        List<Geometry3D.Vec3> ra=new ArrayList<>(),rb=new ArrayList<>();\n'''
new = '''        List<PointF> a=cleanProfile(rawA),b=cleanProfile(rawB);\n        if(a.size()<3||b.size()<3)return empty();\n        if(signedArea(a)<0f)Collections.reverse(a);\n        if(signedArea(b)<0f)Collections.reverse(b);\n        // Preserve authored corners whenever possible. Uniform perimeter sampling\n        // can cut across a sharp corner when the corner does not fall exactly on\n        // a sample interval, shrinking the section and changing volume.\n        if(a.size()!=b.size()){\n            int n=Math.max(8,Math.min(128,Math.max(sampleCount,Math.max(a.size(),b.size()))));\n            a=resampleClosedPreservingVertices(a,n);\n            b=resampleClosedPreservingVertices(b,n);\n        }\n        b=alignClosedProfilePhase(a,b);\n        List<Geometry3D.Vec3> ra=new ArrayList<>(),rb=new ArrayList<>();\n'''
if old in text:
    text = text.replace(old,new,1)
elif 'resampleClosedPreservingVertices(a,n)' not in text:
    raise SystemExit('Loft kernel block not found')

anchor = '''    private static List<PointF> resampleClosed(List<PointF> p,int count){\n'''
helpers = r'''    /**
     * Resamples a polygon without deleting any authored vertex. Extra points are
     * distributed by edge length, so corners remain exact and only straight edge
     * interiors are subdivided.
     */
    private static List<PointF> resampleClosedPreservingVertices(List<PointF> p,int count){
        int m=p==null?0:p.size();
        if(m<2||count<=m){
            List<PointF> copy=new ArrayList<>();
            if(p!=null)for(PointF q:p)copy.add(new PointF(q.x,q.y));
            return copy;
        }
        float[] len=new float[m];float total=0f;
        for(int i=0;i<m;i++){len[i]=dist(p.get(i),p.get((i+1)%m));total+=len[i];}
        if(total<1e-6f)return new ArrayList<>(p);
        int extra=count-m;int[] interior=new int[m];double[] remainder=new double[m];int assigned=0;
        for(int i=0;i<m;i++){
            double exact=(double)extra*len[i]/total;
            interior[i]=(int)Math.floor(exact);remainder[i]=exact-interior[i];assigned+=interior[i];
        }
        while(assigned<extra){
            int best=0;for(int i=1;i<m;i++)if(remainder[i]>remainder[best])best=i;
            interior[best]++;remainder[best]=-1d;assigned++;
        }
        List<PointF> out=new ArrayList<>(count);
        for(int i=0;i<m;i++){
            PointF a=p.get(i),b=p.get((i+1)%m);out.add(new PointF(a.x,a.y));
            int n=interior[i];
            for(int j=1;j<=n;j++){
                float t=(float)j/(n+1f);
                out.add(new PointF(a.x+(b.x-a.x)*t,a.y+(b.y-a.y)*t));
            }
        }
        return out;
    }

    /** Cyclically aligns equal-size rings by normalized 2D shape, avoiding an accidental Loft twist. */
    private static List<PointF> alignClosedProfilePhase(List<PointF> a,List<PointF> b){
        if(a==null||b==null||a.size()!=b.size()||a.isEmpty())return b;
        PointF ca=centroid2(a),cb=centroid2(b);float sa=profileScale(a,ca),sb=profileScale(b,cb);
        if(sa<1e-6f||sb<1e-6f)return b;
        int n=a.size(),bestShift=0;double best=Double.POSITIVE_INFINITY;
        for(int shift=0;shift<n;shift++){
            double score=0d;
            for(int i=0;i<n;i++){
                PointF pa=a.get(i),pb=b.get((i+shift)%n);
                double ax=(pa.x-ca.x)/sa,ay=(pa.y-ca.y)/sa;
                double bx=(pb.x-cb.x)/sb,by=(pb.y-cb.y)/sb;
                double dx=ax-bx,dy=ay-by;score+=dx*dx+dy*dy;
            }
            if(score<best){best=score;bestShift=shift;}
        }
        if(bestShift==0)return b;
        List<PointF> out=new ArrayList<>(n);
        for(int i=0;i<n;i++){PointF q=b.get((i+bestShift)%n);out.add(new PointF(q.x,q.y));}
        return out;
    }

    private static float profileScale(List<PointF> p,PointF c){
        double sum=0d;for(PointF q:p){double dx=q.x-c.x,dy=q.y-c.y;sum+=dx*dx+dy*dy;}
        return(float)Math.sqrt(sum/Math.max(1,p.size()));
    }

'''
if 'resampleClosedPreservingVertices(List<PointF>' not in text:
    if anchor not in text: raise SystemExit('Resample helper anchor not found')
    text=text.replace(anchor,helpers+anchor,1)
SOLID.write_text(text,encoding='utf-8')

if not TEST.exists(): raise SystemExit('Loft instrumentation test missing')
test=TEST.read_text(encoding='utf-8')
test=test.replace('near("Loft frustum volume",28000f,(float)actual,2.0f);','near("Loft frustum volume",28000f,(float)actual,.05f);')
test=test.replace('assertEquals("64-sample Loft should have 64 side faces plus two caps",66,loft.polygons().size());','assertEquals("Vertex-preserving rectangular Loft should have four side faces plus two caps",6,loft.polygons().size());')
TEST.write_text(test,encoding='utf-8')
