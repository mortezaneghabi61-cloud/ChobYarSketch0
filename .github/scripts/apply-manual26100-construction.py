from pathlib import Path


def replace_once(path, old, new, label):
    p=Path(path); text=p.read_text(encoding='utf-8')
    if new in text:
        return False
    if old not in text:
        raise SystemExit(f'{label}: anchor not found in {path}')
    p.write_text(text.replace(old,new,1),encoding='utf-8')
    return True

cad='app/src/main/java/ir/chobyar/sketch/CadCanvasView.java'
replace_once(cad,
'''        boolean isConstruction();\n        boolean canExtrude();''',
'''        boolean isConstruction();\n        void setConstruction(boolean construction);\n        boolean canExtrude();''','entity construction setter')
replace_once(cad,
'''        float extrusion=0f;\n        public String getLayer(){return layer;}''',
'''        float extrusion=0f;\n        boolean construction=false;\n        public String getLayer(){return layer;}''','construction state')
replace_once(cad,
'''        public boolean isConstruction(){return false;}\n        public boolean canExtrude(){return false;}''',
'''        public boolean isConstruction(){return construction;}\n        public void setConstruction(boolean value){construction=value;}\n        public boolean canExtrude(){return false;}''','construction accessors')
replace_once(cad,
'''        void meta(BaseEntity e){e.layer=layer;e.color=color;e.extrusion=extrusion;}''',
'''        void meta(BaseEntity e){e.layer=layer;e.color=color;e.extrusion=extrusion;e.construction=construction;}''','construction copy')

# Construction geometry remains selectable/snappable reference geometry.
p=Path(cad); text=p.read_text(encoding='utf-8')
text=text.replace('if (!isVisible(e) || e.isConstruction()) continue;','if (!isVisible(e)) continue;',1)
text=text.replace('if(e==exclude||!isVisible(e)||e.isConstruction())continue;','if(e==exclude||!isVisible(e))continue;',1)
text=text.replace('if(!isVisible(e)||e.isConstruction())continue;','if(!isVisible(e))continue;',1)

old='''                case"P":case"PUSHPULL":case"EXTRUDE":require(a,2);if(selected==null)return"اول سطح بسته را انتخاب کن";if(!selected.canExtrude())return"این شکل قابل اکسترود نیست";saveUndo();selected.setExtrusion(Math.abs(f(a,1)));invalidate();return"Push/Pull = "+mm(Math.abs(f(a,1)))+" (2.5D)";\n                case"ERASE":case"DELETE":if(selected==null)return"اول شکل را انتخاب کن";deleteSelected();return"حذف شد";'''
new='''                case"P":case"PUSHPULL":case"EXTRUDE":require(a,2);if(selected==null)return"اول سطح بسته را انتخاب کن";if(selected.isConstruction())return"Construction قابل Extrude نیست";if(!selected.canExtrude())return"این شکل قابل اکسترود نیست";saveUndo();selected.setExtrusion(Math.abs(f(a,1)));invalidate();return"Push/Pull = "+mm(Math.abs(f(a,1)))+" (2.5D)";\n                case"CONSTRUCTION":if(selected==null)return"اول Sketch را انتخاب کن";saveUndo();selected.setConstruction(true);invalidate();return"Construction روشن شد";\n                case"NORMAL":case"REGULAR":if(selected==null)return"اول Sketch را انتخاب کن";saveUndo();selected.setConstruction(false);invalidate();return"Construction خاموش شد";\n                case"ERASE":case"DELETE":if(selected==null)return"اول شکل را انتخاب کن";deleteSelected();return"حذف شد";'''
if new not in text:
    if old not in text: raise SystemExit('construction commands anchor not found')
    text=text.replace(old,new,1)
p.write_text(text,encoding='utf-8')

solid='app/src/main/java/ir/chobyar/sketch/SolidCadCanvasView.java'
history='app/src/main/java/ir/chobyar/sketch/ParametricHistorySolidCadCanvasView.java'

# Construction references must never become a closed Solid profile. Detect the
# executable guard itself, not a comment, so repeated CI runs stay idempotent.
p=Path(solid); t=p.read_text(encoding='utf-8')
anchor='''        if(sel.isEmpty())return null;\n        if(sel.size()==1){'''
repl='''        if(sel.isEmpty())return null;\n        // Construction geometry is reference-only and must never define a Solid profile.\n        for(Object e:sel)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;\n        if(sel.size()==1){'''
solid_guard='for(Object e:sel)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;'
if solid_guard not in t:
    if anchor not in t: raise SystemExit('Solid profile anchor not found')
    t=t.replace(anchor,repl,1)
p.write_text(t,encoding='utf-8')

p=Path(history); t=p.read_text(encoding='utf-8')
anchor='''        if(src==null||src.isEmpty())return null;\n        List<Object> current=entities();'''
repl='''        if(src==null||src.isEmpty())return null;\n        // Construction geometry cannot be a History profile source.\n        for(Object e:src)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;\n        List<Object> current=entities();'''
history_guard='for(Object e:src)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;'
if history_guard not in t:
    if anchor not in t: raise SystemExit('History profile anchor not found')
    t=t.replace(anchor,repl,1)
p.write_text(t,encoding='utf-8')

print('Manual 26.100 Construction patch applied')
