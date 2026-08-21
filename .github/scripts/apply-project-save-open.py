from pathlib import Path

# ---------------------------------------------------------------------------
# CadCanvasView: explicit, non-reflective sketch snapshot API.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/ir/chobyar/sketch/CadCanvasView.java')
s = path.read_text(encoding='utf-8')

if 'public String exportSketchProjectState()' not in s:
    marker = '    protected interface Entity{\n'
    if marker not in s:
        raise SystemExit('CadCanvasView entity marker not found')
    methods = r'''    // ------------------------------------------------------------------
    // Project persistence — explicit sketch/document shell API
    // ------------------------------------------------------------------

    /**
     * Versioned sketch snapshot used by the .chobyar project envelope.
     * This method lives beside the entity types so persistence does not need
     * reflection and malformed files can be validated before state mutation.
     */
    public String exportSketchProjectState() {
        try {
            org.json.JSONObject root=new org.json.JSONObject();
            root.put("schemaVersion",1);
            root.put("unit","mm");
            root.put("currentLayer",currentLayer);
            root.put("currentColor",currentColor);
            root.put("polygonSides",polygonSides);

            org.json.JSONObject view=new org.json.JSONObject();
            view.put("scale",viewScale);view.put("offsetX",offsetX);view.put("offsetY",offsetY);
            view.put("grid",showGrid);view.put("axes",showAxes);view.put("guides",showGuides);
            view.put("dimensions",showDimensions);view.put("snap",snapEnabled);view.put("ortho",orthoEnabled);
            root.put("view",view);

            org.json.JSONArray layerRows=new org.json.JSONArray();
            for(Map.Entry<String,Boolean> entry:layers.entrySet()){
                org.json.JSONObject row=new org.json.JSONObject();
                row.put("name",entry.getKey());row.put("visible",Boolean.TRUE.equals(entry.getValue()));layerRows.put(row);
            }
            root.put("layers",layerRows);

            org.json.JSONArray rows=new org.json.JSONArray();
            for(Entity entity:entities)rows.put(projectEntityToJson(entity));
            root.put("entities",rows);
            return root.toString();
        }catch(Exception e){
            throw new IllegalStateException("Project sketch serialization failed",e);
        }
    }

    /** Parse-only guard for SAF Open. Never mutates the current document. */
    public boolean canImportSketchProjectState(String raw){
        try{parseSketchProjectState(raw);return true;}catch(Exception e){return false;}
    }

    /**
     * Restores a validated sketch snapshot atomically at the CadCanvasView layer.
     * Subclasses should call clearAll() before this when opening a new document,
     * after canImportSketchProjectState(raw) returned true.
     */
    public String importSketchProjectState(String raw){
        try{
            ParsedSketchProject parsed=parseSketchProjectState(raw);
            entities.clear();entities.addAll(parsed.entities);
            layers.clear();layers.putAll(parsed.layers);if(layers.isEmpty())layers.put("0",true);
            currentLayer=parsed.currentLayer;currentColor=parsed.currentColor;polygonSides=parsed.polygonSides;
            viewScale=parsed.viewScale;offsetX=parsed.offsetX;offsetY=parsed.offsetY;
            showGrid=parsed.showGrid;showAxes=parsed.showAxes;showGuides=parsed.showGuides;
            showDimensions=parsed.showDimensions;snapEnabled=parsed.snapEnabled;orthoEnabled=parsed.orthoEnabled;
            selected=null;tool=TOOL_SELECT;drawing=false;draggingSelection=false;activeHandle=-1;snapVisible=false;
            undoStack.clear();redoStack.clear();scenes.clear();freePoints.clear();invalidate();
            return "پروژه Sketch باز شد • "+entities.size()+" آیتم";
        }catch(Exception e){return "بازکردن پروژه انجام نشد • فایل نامعتبر است";}
    }

    private static final class ParsedSketchProject{
        final List<Entity> entities;final LinkedHashMap<String,Boolean> layers;
        final String currentLayer;final int currentColor,polygonSides;
        final float viewScale,offsetX,offsetY;final boolean showGrid,showAxes,showGuides,showDimensions,snapEnabled,orthoEnabled;
        ParsedSketchProject(List<Entity> entities,LinkedHashMap<String,Boolean> layers,String currentLayer,int currentColor,int polygonSides,
                            float viewScale,float offsetX,float offsetY,boolean showGrid,boolean showAxes,boolean showGuides,
                            boolean showDimensions,boolean snapEnabled,boolean orthoEnabled){
            this.entities=entities;this.layers=layers;this.currentLayer=currentLayer;this.currentColor=currentColor;this.polygonSides=polygonSides;
            this.viewScale=viewScale;this.offsetX=offsetX;this.offsetY=offsetY;this.showGrid=showGrid;this.showAxes=showAxes;this.showGuides=showGuides;
            this.showDimensions=showDimensions;this.snapEnabled=snapEnabled;this.orthoEnabled=orthoEnabled;
        }
    }

    private ParsedSketchProject parseSketchProjectState(String raw)throws Exception{
        if(raw==null||raw.trim().isEmpty())throw new IllegalArgumentException("empty");
        org.json.JSONObject root=new org.json.JSONObject(raw);
        if(root.optInt("schemaVersion",-1)!=1)throw new IllegalArgumentException("schema");
        if(!"mm".equals(root.optString("unit","mm")))throw new IllegalArgumentException("unit");
        String layer=root.optString("currentLayer","0").trim();if(layer.isEmpty())layer="0";
        int color=root.optInt("currentColor",Color.rgb(25,25,25));
        int sides=Math.max(3,Math.min(64,root.optInt("polygonSides",6)));
        org.json.JSONObject view=root.optJSONObject("view");
        float scale=view==null?1f:finiteFloat(view.optDouble("scale",1));
        scale=clamp(scale,MIN_VIEW_SCALE,MAX_VIEW_SCALE);
        float ox=view==null?120f:finiteFloat(view.optDouble("offsetX",120));
        float oy=view==null?160f:finiteFloat(view.optDouble("offsetY",160));
        boolean grid=view==null||view.optBoolean("grid",true),axes=view==null||view.optBoolean("axes",true);
        boolean guides=view==null||view.optBoolean("guides",true),dims=view==null||view.optBoolean("dimensions",true);
        boolean snap=view==null||view.optBoolean("snap",true),ortho=view!=null&&view.optBoolean("ortho",false);

        LinkedHashMap<String,Boolean> restoredLayers=new LinkedHashMap<>();
        org.json.JSONArray layerRows=root.optJSONArray("layers");
        if(layerRows!=null){for(int i=0;i<layerRows.length();i++){
            org.json.JSONObject row=layerRows.getJSONObject(i);String name=row.getString("name").trim();
            if(name.isEmpty())throw new IllegalArgumentException("layer");restoredLayers.put(name,row.optBoolean("visible",true));
        }}
        if(!restoredLayers.containsKey(layer))restoredLayers.put(layer,true);

        org.json.JSONArray rows=root.getJSONArray("entities");
        List<Entity> restored=new ArrayList<>();
        for(int i=0;i<rows.length();i++)restored.add(projectEntityFromJson(rows.getJSONObject(i)));
        return new ParsedSketchProject(restored,restoredLayers,layer,color,sides,scale,ox,oy,grid,axes,guides,dims,snap,ortho);
    }

    private org.json.JSONObject projectEntityToJson(Entity entity)throws Exception{
        if(!(entity instanceof BaseEntity))throw new IllegalArgumentException("unsupported entity");
        BaseEntity base=(BaseEntity)entity;org.json.JSONObject o=new org.json.JSONObject();
        if(entity instanceof PointEntity){PointEntity e=(PointEntity)entity;o.put("type","POINT");o.put("x",e.x);o.put("y",e.y);}
        else if(entity instanceof LineEntity){LineEntity e=(LineEntity)entity;o.put("type","LINE");o.put("x1",e.x1);o.put("y1",e.y1);o.put("x2",e.x2);o.put("y2",e.y2);}
        else if(entity instanceof RectEntity){RectEntity e=(RectEntity)entity;o.put("type","RECT");o.put("points",projectPoints(e.p));}
        else if(entity instanceof CircleEntity){CircleEntity e=(CircleEntity)entity;o.put("type","CIRCLE");o.put("x",e.x);o.put("y",e.y);o.put("r",e.r);}
        else if(entity instanceof ArcEntity){ArcEntity e=(ArcEntity)entity;o.put("type","ARC");o.put("x",e.x);o.put("y",e.y);o.put("r",e.r);o.put("start",e.start);o.put("sweep",e.sweep);}
        else if(entity instanceof PolygonEntity){PolygonEntity e=(PolygonEntity)entity;o.put("type","POLYGON");o.put("points",projectPoints(e.points));}
        else if(entity instanceof PolylineEntity){PolylineEntity e=(PolylineEntity)entity;o.put("type","POLYLINE");o.put("closed",e.closed);o.put("points",projectPoints(e.points));}
        else if(entity instanceof MeasureEntity){MeasureEntity e=(MeasureEntity)entity;o.put("type","MEASURE");o.put("x1",e.x1);o.put("y1",e.y1);o.put("x2",e.x2);o.put("y2",e.y2);}
        else if(entity instanceof AngleEntity){AngleEntity e=(AngleEntity)entity;o.put("type","ANGLE");o.put("ax",e.ax);o.put("ay",e.ay);o.put("cx",e.cx);o.put("cy",e.cy);o.put("bx",e.bx);o.put("by",e.by);}
        else if(entity instanceof GuideEntity){GuideEntity e=(GuideEntity)entity;o.put("type","GUIDE");o.put("vertical",e.vertical);o.put("value",e.value);}
        else throw new IllegalArgumentException("unsupported entity "+entity.getClass().getSimpleName());
        o.put("layer",base.layer);o.put("color",base.color);o.put("extrusion",base.extrusion);o.put("construction",base.construction);
        o.put("referenceBodyId",base.referenceBodyId);o.put("referenceEdgeIndex",base.referenceEdgeIndex);o.put("referenceEdgeKind",base.referenceEdgeKind);
        return o;
    }

    private Entity projectEntityFromJson(org.json.JSONObject o)throws Exception{
        String type=o.getString("type").trim().toUpperCase(Locale.US);Entity entity;
        if("POINT".equals(type))entity=new PointEntity(ff(o,"x"),ff(o,"y"));
        else if("LINE".equals(type))entity=new LineEntity(ff(o,"x1"),ff(o,"y1"),ff(o,"x2"),ff(o,"y2"));
        else if("RECT".equals(type)){List<PointF> p=projectPoints(o.getJSONArray("points"),4,4);entity=new RectEntity(p.toArray(new PointF[0]));}
        else if("CIRCLE".equals(type)){float r=positive(o,"r");entity=new CircleEntity(ff(o,"x"),ff(o,"y"),r);}
        else if("ARC".equals(type)){float r=positive(o,"r");entity=new ArcEntity(ff(o,"x"),ff(o,"y"),r,ff(o,"start"),ff(o,"sweep"));}
        else if("POLYGON".equals(type)){List<PointF> p=projectPoints(o.getJSONArray("points"),3,10000);entity=new PolygonEntity(p);}
        else if("POLYLINE".equals(type)){List<PointF> p=projectPoints(o.getJSONArray("points"),2,100000);entity=new PolylineEntity(p,o.optBoolean("closed",false));}
        else if("MEASURE".equals(type))entity=new MeasureEntity(ff(o,"x1"),ff(o,"y1"),ff(o,"x2"),ff(o,"y2"));
        else if("ANGLE".equals(type))entity=new AngleEntity(ff(o,"ax"),ff(o,"ay"),ff(o,"cx"),ff(o,"cy"),ff(o,"bx"),ff(o,"by"));
        else if("GUIDE".equals(type))entity=new GuideEntity(o.getBoolean("vertical"),ff(o,"value"));
        else throw new IllegalArgumentException("unknown entity type");
        BaseEntity base=(BaseEntity)entity;base.layer=o.optString("layer","0");base.color=o.optInt("color",Color.rgb(25,25,25));
        base.extrusion=finiteFloat(o.optDouble("extrusion",0));base.construction=o.optBoolean("construction",base.construction);
        base.referenceBodyId=o.optInt("referenceBodyId",-1);base.referenceEdgeIndex=o.optInt("referenceEdgeIndex",-1);base.referenceEdgeKind=o.optInt("referenceEdgeKind",0);
        return entity;
    }

    private static org.json.JSONArray projectPoints(PointF[] points)throws Exception{
        org.json.JSONArray a=new org.json.JSONArray();for(PointF p:points)a.put(new org.json.JSONArray().put(p.x).put(p.y));return a;
    }
    private static org.json.JSONArray projectPoints(List<PointF> points)throws Exception{
        org.json.JSONArray a=new org.json.JSONArray();for(PointF p:points)a.put(new org.json.JSONArray().put(p.x).put(p.y));return a;
    }
    private static List<PointF> projectPoints(org.json.JSONArray a,int min,int max)throws Exception{
        if(a.length()<min||a.length()>max)throw new IllegalArgumentException("point count");List<PointF> out=new ArrayList<>();
        for(int i=0;i<a.length();i++){org.json.JSONArray p=a.getJSONArray(i);if(p.length()!=2)throw new IllegalArgumentException("point");out.add(new PointF(finiteFloat(p.getDouble(0)),finiteFloat(p.getDouble(1))));}return out;
    }
    private static float ff(org.json.JSONObject o,String key)throws Exception{return finiteFloat(o.getDouble(key));}
    private static float positive(org.json.JSONObject o,String key)throws Exception{float v=ff(o,key);if(v<=0f)throw new IllegalArgumentException(key);return v;}
    private static float finiteFloat(double value){if(Double.isNaN(value)||Double.isInfinite(value)||Math.abs(value)>1.0e9)throw new IllegalArgumentException("non-finite");return(float)value;}

'''
    s = s.replace(marker, methods + marker, 1)
    path.write_text(s, encoding='utf-8')

# ---------------------------------------------------------------------------
# ChobYarActivity: SAF Save/Open entry. v1 refuses models that would lose 3D.
# ---------------------------------------------------------------------------
path = Path('app/src/main/java/ir/chobyar/sketch/ChobYarActivity.java')
s = path.read_text(encoding='utf-8')

if 'REQUEST_SAVE_PROJECT=1703' not in s:
    anchor='    private static final int REQUEST_REFERENCE_IMAGE=1702;\n'
    if anchor not in s: raise SystemExit('Activity request anchor missing')
    s=s.replace(anchor,anchor+'    private static final int REQUEST_SAVE_PROJECT=1703;\n    private static final int REQUEST_OPEN_PROJECT=1704;\n',1)

old='        b.addView(topAction("⌂",this::showItems),new LinearLayout.LayoutParams(dp(42),dp(48)));'
new='        b.addView(topAction("⌂",this::showProjectMenu),new LinearLayout.LayoutParams(dp(42),dp(48)));'
if old in s:s=s.replace(old,new,1)
elif new not in s:raise SystemExit('top project menu anchor missing')

if 'private void showProjectMenu()' not in s:
    marker='    private void more(){\n'
    if marker not in s: raise SystemExit('more marker missing')
    method=r'''    private void showProjectMenu(){
        String[] rows={"Items / Layers","Save Project","Open Project","Export STEP / STL"};
        new AlertDialog.Builder(this).setTitle("Project").setItems(rows,(d,w)->{
            if(w==0)showItems();else if(w==1)saveProject();else if(w==2)openProject();else showCadExport();
        }).setNegativeButton("بستن",null).show();
    }

    private void saveProject(){
        // Schema v1 is deliberately non-lossy. Exact 3D History and reference
        // images are added in the next schema rather than silently flattened.
        if(cad.itemRows().length>0||cad.hasReferenceImage()){
            toast("ذخیره پروژه 3D هنوز کامل نشده؛ برای جلوگیری از حذف History فایل ناقص ذخیره نمی‌شود");return;
        }
        Intent intent=new Intent(Intent.ACTION_CREATE_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");intent.putExtra(Intent.EXTRA_TITLE,"ChobYar-Project.chobyar");
        startActivityForResult(intent,REQUEST_SAVE_PROJECT);
    }

    private void openProject(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.addCategory(Intent.CATEGORY_OPENABLE);intent.setType("application/json");
        startActivityForResult(intent,REQUEST_OPEN_PROJECT);
    }

'''
    s=s.replace(marker,method+marker,1)

activity_marker='    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){\n        super.onActivityResult(requestCode,resultCode,data);\n'
if activity_marker not in s: raise SystemExit('onActivityResult marker missing')
if 'if(requestCode==REQUEST_SAVE_PROJECT)' not in s:
    project_result=r'''        if(requestCode==REQUEST_SAVE_PROJECT){
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            try(OutputStream out=getContentResolver().openOutputStream(data.getData())){
                if(out==null)throw new IllegalStateException();String json=CadProjectDocument.encodeSketch(cad.exportSketchProjectState());
                out.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));out.flush();toast("پروژه ذخیره شد");
            }catch(Exception e){toast("ذخیره پروژه انجام نشد");}return;
        }
        if(requestCode==REQUEST_OPEN_PROJECT){
            if(resultCode!=RESULT_OK||data==null||data.getData()==null)return;
            try(InputStream in=getContentResolver().openInputStream(data.getData());java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream()){
                if(in==null)throw new IllegalStateException();byte[] bytes=new byte[65536];int n;while((n=in.read(bytes))>0)buffer.write(bytes,0,n);
                CadProjectDocument.Decoded decoded=CadProjectDocument.decode(new String(buffer.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
                if(!cad.canImportSketchProjectState(decoded.sketchState)){toast("فایل پروژه معتبر نیست");return;}
                cad.clearAll();String result=cad.importSketchProjectState(decoded.sketchState);syncGpuMesh();updateWorkspaceChrome();cad.post(cad::fitAll);status(result);
            }catch(Exception e){toast("بازکردن پروژه انجام نشد");}return;
        }
'''
    s=s.replace(activity_marker,activity_marker+project_result,1)

path.write_text(s, encoding='utf-8')
