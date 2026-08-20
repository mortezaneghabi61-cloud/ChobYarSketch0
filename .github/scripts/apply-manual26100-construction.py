from pathlib import Path

CAD = Path('app/src/main/java/ir/chobyar/sketch/CadCanvasView.java')
SOLID = Path('app/src/main/java/ir/chobyar/sketch/SolidCadCanvasView.java')
HISTORY = Path('app/src/main/java/ir/chobyar/sketch/ParametricHistorySolidCadCanvasView.java')


def require(text: str, token: str, label: str) -> None:
    if token not in text:
        raise SystemExit(f'{label}: contract missing')


cad = CAD.read_text(encoding='utf-8')
solid = SOLID.read_text(encoding='utf-8')
history = HISTORY.read_text(encoding='utf-8')

# Construction is already production state. Verify the evolved contract instead
# of trying to reinsert fields next to stale anchors; Project provenance now adds
# more metadata to the same Entity/BaseEntity section.
require(cad, 'void setConstruction(boolean construction);', 'Entity construction setter')
require(cad, 'boolean construction=false;', 'Construction state')
require(cad, 'public boolean isConstruction(){return construction;}', 'Construction getter')
require(cad, 'public void setConstruction(boolean value){construction=value;}', 'Construction setter implementation')
require(cad, 'e.construction=construction', 'Construction copy metadata')
require(cad, 'if(selected.isConstruction())return"Construction قابل Extrude نیست";', '2.5D extrusion guard')
require(cad, 'case"CONSTRUCTION":if(selected==null)return"اول Sketch را انتخاب کن";', 'Construction command')
require(cad, 'case"NORMAL":case"REGULAR":if(selected==null)return"اول Sketch را انتخاب کن";', 'Normal command')
require(cad, 'for(Entity e:entities)if(!e.isConstruction())e.appendDxf(d);', 'DXF construction exclusion')

# Construction geometry must remain selectable/snappable. Reject the obsolete
# filters if they ever reappear.
for obsolete in (
    'if (!isVisible(e) || e.isConstruction()) continue;',
    'if(e==exclude||!isVisible(e)||e.isConstruction())continue;',
    'if(!isVisible(e)||e.isConstruction())continue;',
):
    if obsolete in cad:
        raise SystemExit('Construction selectable/snappable contract regressed')

require(
    solid,
    'for(Object e:sel)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;',
    'Solid construction profile guard',
)
require(
    history,
    'for(Object e:src)if(Boolean.TRUE.equals(call(e,"isConstruction")))return null;',
    'History construction profile guard',
)

print('Manual 26.100 Construction production contract verified; no patch required')
