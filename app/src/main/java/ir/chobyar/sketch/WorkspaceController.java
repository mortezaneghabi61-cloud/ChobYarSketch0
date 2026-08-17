package ir.chobyar.sketch;

/**
 * Single source of truth for the production modeling workspace.
 *
 * Geometry remains owned by the sketch/OCCT layers. This controller owns the
 * interaction contract around that geometry: one active tool, one selection
 * kind and one deterministic select -> preview -> commit/cancel session.
 */
final class WorkspaceController {

    enum Mode { SKETCH, MODELING, DRAWINGS }
    enum Tool { NONE, MOVE_ROTATE, ALIGN, EXTRUDE, REVOLVE }
    enum Phase { IDLE, SELECT_PRIMARY, SELECT_SECONDARY, PREVIEW }
    enum Selection { NONE, SKETCH, REGION, BODY, FACE, EDGE, VERTEX }

    static final class State {
        final Mode mode;
        final Tool tool;
        final Phase phase;
        final Selection selection;
        final long revision;

        State(Mode mode, Tool tool, Phase phase, Selection selection, long revision) {
            this.mode=mode;
            this.tool=tool;
            this.phase=phase;
            this.selection=selection;
            this.revision=revision;
        }

        boolean sessionActive(){ return tool!=Tool.NONE; }
        boolean canCommit(){ return phase==Phase.PREVIEW; }

        String title(){
            switch(tool){
                case MOVE_ROTATE:return "Move / Rotate";
                case ALIGN:return "Align";
                case EXTRUDE:return "Extrude";
                case REVOLVE:return "Revolve";
                default:return "";
            }
        }

        String instruction(){
            if(tool==Tool.MOVE_ROTATE){
                return phase==Phase.SELECT_PRIMARY
                        ? "شکل، پروفایل یا بدنه‌ای را برای حرکت انتخاب کن"
                        : "فلش یا حلقه را بکش؛ دوربین بدون خروج از ابزار قابل حرکت است";
            }
            if(tool==Tool.ALIGN){
                if(phase==Phase.SELECT_PRIMARY)return "قطعه‌ای را که باید جابه‌جا شود انتخاب کن";
                if(phase==Phase.SELECT_SECONDARY)return "سطح، لبه یا محور مقصد را انتخاب کن";
                return "پیش‌نمایش را بررسی کن؛ لمس Align جهت Same/Opposed را عوض می‌کند";
            }
            if(tool==Tool.EXTRUDE){
                return phase==Phase.SELECT_PRIMARY
                        ? "داخل یک پروفایل بسته را انتخاب کن"
                        : "فلش را بکش یا ارتفاع دقیق را به میلی‌متر وارد کن";
            }
            if(tool==Tool.REVOLVE){
                if(phase==Phase.SELECT_PRIMARY)return "پروفایل بستهٔ خراطی را انتخاب کن";
                if(phase==Phase.SELECT_SECONDARY)return "خط یا محور دوران را انتخاب کن";
                return "حلقه=زاویه، فلش=Height؛ برای رزوه Angle=360×دور و Height=Pitch×دور";
            }
            return "";
        }
    }

    private Mode mode=Mode.SKETCH;
    private Tool tool=Tool.NONE;
    private Phase phase=Phase.IDLE;
    private Selection selection=Selection.NONE;
    private long revision;

    State state(){ return new State(mode,tool,phase,selection,revision); }

    State onCanvasState(boolean modeling, String rawSelection){
        mode=modeling?Mode.MODELING:Mode.SKETCH;
        selection=parseSelection(rawSelection);
        if(tool==Tool.MOVE_ROTATE){
            phase=selection==Selection.NONE?Phase.SELECT_PRIMARY:Phase.PREVIEW;
        }else if(tool==Tool.EXTRUDE){
            phase=isSketchProfile(selection)?Phase.PREVIEW:Phase.SELECT_PRIMARY;
        }
        revision++;
        return state();
    }

    State begin(Tool next){
        tool=next==null?Tool.NONE:next;
        if(tool==Tool.NONE)phase=Phase.IDLE;
        else if(tool==Tool.MOVE_ROTATE)phase=selection==Selection.NONE?Phase.SELECT_PRIMARY:Phase.PREVIEW;
        else if(tool==Tool.EXTRUDE)phase=isSketchProfile(selection)?Phase.PREVIEW:Phase.SELECT_PRIMARY;
        else phase=Phase.SELECT_PRIMARY;
        revision++;
        return state();
    }

    State primaryAccepted(){
        if(tool==Tool.ALIGN||tool==Tool.REVOLVE)phase=Phase.SELECT_SECONDARY;
        else if(tool!=Tool.NONE)phase=Phase.PREVIEW;
        revision++;
        return state();
    }

    State previewReady(){
        if(tool!=Tool.NONE)phase=Phase.PREVIEW;
        revision++;
        return state();
    }

    State finish(){
        tool=Tool.NONE;phase=Phase.IDLE;revision++;return state();
    }

    State cancel(){ return finish(); }

    private static boolean isSketchProfile(Selection value){
        return value==Selection.SKETCH||value==Selection.REGION;
    }

    private static Selection parseSelection(String raw){
        if(raw==null)return Selection.NONE;
        String value=raw.trim().toUpperCase(java.util.Locale.US);
        if(value.isEmpty()||"NONE".equals(value))return Selection.NONE;
        try{return Selection.valueOf(value);}catch(Exception ignored){return Selection.NONE;}
    }
}
