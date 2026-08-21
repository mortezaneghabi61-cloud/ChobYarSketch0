package ir.chobyar.sketch;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Production-only visual synchronizer for the canonical ChobYar 3D Figma master.
 *
 * Geometry and tool behavior stay owned by ChobYarActivity/CAD. This class only
 * normalizes the workspace chrome: command bar, tool rails, contextual rail,
 * session bar, labels and bottom modeling status.
 */
final class FigmaWorkspaceStyler {
    private static final int TAG_STATUS = 0x43485931;
    private static final WeakHashMap<FrameLayout,Boolean> INSTALLED = new WeakHashMap<>();

    private static final Map<String,String> LABELS = new HashMap<>();
    static {
        LABELS.put("⌕\nSearch","⌕\nجستجو");
        LABELS.put("✎\nSketch","✎\nاسکچ");
        LABELS.put("＋\nAdd","＋\nافزودن");
        LABELS.put("↗\nTransform","↗\nتبدیل");
        LABELS.put("⌁\nTools","⌁\nابزار");
        LABELS.put("mm\nUnits","mm\nواحد");
        LABELS.put("×\nClose","×\nبستن");
        LABELS.put("×\nDeselect All","×\nلغو انتخاب");
        LABELS.put("◉\nMaterial","◉\nمتریال");
        LABELS.put("◫\nSection","◫\nبرش");
        LABELS.put("⌨\nMeasure","⌨\nاندازه");
        LABELS.put("⌖\nMeasure","⌖\nاندازه");
        LABELS.put("⌫\nDelete","⌫\nحذف");
        LABELS.put("▧\nImage","▧\nتصویر");
        LABELS.put("▱\nHistory","▱\nHistory");
    }

    private FigmaWorkspaceStyler() {}

    static void apply(Activity activity) {
        if (!(activity instanceof ChobYarActivity)) return;
        View content = activity.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) return;
        ViewGroup contentGroup = (ViewGroup) content;
        if (contentGroup.getChildCount() == 0) return;
        View workspace = contentGroup.getChildAt(0);
        if (!(workspace instanceof FrameLayout)) return;
        FrameLayout root = (FrameLayout) workspace;

        restyleExisting(root);
        ensureBottomStatus(activity, root);
        installDynamicStyling(root);
        refreshBottomStatus(root);
    }

    private static void restyleExisting(FrameLayout root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (!(child instanceof LinearLayout) && !(child instanceof TextView)) continue;
            String all = descendantText(child);

            if (contains(all, "چوب‌یار 3D") && contains(all, "⌂")) {
                styleTopBar(child);
            } else if (contains(all, "Search") || contains(all, "جستجو")) {
                styleRail(child, Gravity.START | Gravity.CENTER_VERTICAL, 12, 0, 0, 0);
            } else if (contains(all, "Fit") && (contains(all, "Snap") || contains(all, "واحد") || contains(all, "Units"))) {
                styleRail(child, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 12, 0);
            } else if (contains(all, "H/V") && contains(all, "Constraints")) {
                styleRail(child, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 78, 0);
            } else if (contains(all, "لغو") && contains(all, "انجام")) {
                styleSessionBar(child);
            } else if (child instanceof LinearLayout) {
                styleToolContainer((LinearLayout) child);
            } else if (child instanceof TextView) {
                styleText((TextView) child);
            }
        }
    }

    private static void styleTopBar(View view) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) view;
        bar.setPadding(dp(bar, 10), dp(bar, 4), dp(bar, 10), dp(bar, 4));
        bar.setBackground(panel(bar, Color.argb(250,255,255,255), Color.rgb(211,218,228), 16));
        bar.setElevation(dp(bar, 6));
        if (bar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams();
            p.width = ViewGroup.LayoutParams.MATCH_PARENT;
            p.height = dp(bar, 60);
            p.gravity = Gravity.TOP;
            p.setMargins(dp(bar, 12), dp(bar, 8), dp(bar, 12), 0);
            bar.setLayoutParams(p);
        }
        styleToolContainer(bar);
        forEachText(bar, tv -> {
            String text = String.valueOf(tv.getText());
            if (text.contains("چوب‌یار 3D") || text.startsWith("Sketch") || text.contains("انتخاب شد")) {
                tv.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                tv.setTextSize(13f);
                tv.setTypeface(null, Typeface.BOLD);
                tv.setPadding(dp(tv, 8), 0, dp(tv, 8), 0);
            }
        });
    }

    private static void styleRail(View view, int gravity, int l, int t, int r, int b) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout rail = (LinearLayout) view;
        rail.setPadding(dp(rail, 3), dp(rail, 5), dp(rail, 3), dp(rail, 5));
        rail.setBackground(panel(rail, Color.argb(248,255,255,255), Color.rgb(214,221,230), 16));
        rail.setElevation(dp(rail, 7));
        if (rail.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) rail.getLayoutParams();
            p.gravity = gravity;
            p.setMargins(dp(rail,l), dp(rail,t), dp(rail,r), dp(rail,b));
            rail.setLayoutParams(p);
        }
        styleToolContainer(rail);
    }

    private static void styleSessionBar(View view) {
        if (!(view instanceof LinearLayout)) return;
        LinearLayout bar = (LinearLayout) view;
        bar.setPadding(dp(bar, 4), dp(bar, 3), dp(bar, 4), dp(bar, 3));
        bar.setBackground(panel(bar, Color.argb(252,255,255,255), Color.rgb(204,214,226), 16));
        bar.setElevation(dp(bar, 8));
        if (bar.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) bar.getLayoutParams();
            p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            p.setMargins(0,0,0,dp(bar,58));
            bar.setLayoutParams(p);
        }
        styleToolContainer(bar);
        forEachText(bar, tv -> {
            if ("انجام".contentEquals(tv.getText())) {
                tv.setTextColor(Color.rgb(22,118,74));
                tv.setTypeface(null, Typeface.BOLD);
            } else if ("لغو".contentEquals(tv.getText())) {
                tv.setTextColor(Color.rgb(165,54,54));
            }
        });
    }

    private static void styleToolContainer(LinearLayout container) {
        for (int i=0;i<container.getChildCount();i++) {
            View child=container.getChildAt(i);
            if (child instanceof TextView) styleText((TextView)child);
            else if (child instanceof LinearLayout) styleToolContainer((LinearLayout)child);
        }
    }

    private static void styleText(TextView tv) {
        String current = String.valueOf(tv.getText());
        String mapped = LABELS.get(current);
        if (mapped != null) {
            String semantic = semanticLabel(current);
            if (!semantic.isEmpty()) tv.setContentDescription(semantic);
            tv.setText(mapped);
        }
        String text = String.valueOf(tv.getText());
        if (text.indexOf('\n') >= 0) {
            tv.setTextSize(8.2f);
            tv.setMinWidth(dp(tv,58));
            tv.setMinHeight(dp(tv,50));
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(Color.rgb(55,65,81));
            tv.setPadding(dp(tv,3),dp(tv,3),dp(tv,3),dp(tv,3));
        } else {
            tv.setTextColor(Color.rgb(38,45,56));
        }
    }

    private static String semanticLabel(String original) {
        if (original == null) return "";
        int split = original.lastIndexOf('\n');
        return (split >= 0 ? original.substring(split + 1) : original).trim();
    }

    private static void installDynamicStyling(FrameLayout root) {
        synchronized (INSTALLED) {
            if (INSTALLED.containsKey(root)) return;
            INSTALLED.put(root, Boolean.TRUE);
        }
        for (int i=0;i<root.getChildCount();i++) {
            View child=root.getChildAt(i);
            if (child instanceof LinearLayout) installOnContainer((LinearLayout)child);
        }
        root.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->refreshBottomStatus(root));
    }

    private static void installOnContainer(LinearLayout container) {
        container.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) {
                if (child instanceof TextView) styleText((TextView)child);
                if (child instanceof LinearLayout) installOnContainer((LinearLayout)child);
            }
            @Override public void onChildViewRemoved(View parent, View child) {}
        });
        styleToolContainer(container);
    }

    private static void ensureBottomStatus(Activity activity, FrameLayout root) {
        if (root.findViewWithTag(TAG_STATUS) != null) return;
        TextView status = new TextView(activity);
        status.setTag(TAG_STATUS);
        status.setText("Modeling • ISO • mm");
        status.setTextSize(9f);
        status.setTypeface(null, Typeface.BOLD);
        status.setTextColor(Color.rgb(75,85,99));
        status.setGravity(Gravity.CENTER);
        status.setPadding(dp(status,12),dp(status,7),dp(status,12),dp(status,7));
        status.setBackground(panel(status, Color.argb(236,255,255,255), Color.rgb(218,224,232), 13));
        status.setElevation(dp(status,4));
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,ViewGroup.LayoutParams.WRAP_CONTENT,Gravity.BOTTOM|Gravity.START);
        p.setMargins(dp(status,14),0,0,dp(status,14));
        root.addView(status,p);
    }

    private static void refreshBottomStatus(FrameLayout root) {
        View tagged=root.findViewWithTag(TAG_STATUS);
        if (!(tagged instanceof TextView)) return;
        TextView status=(TextView)tagged;
        String title="";
        for(int i=0;i<root.getChildCount();i++) {
            String text=descendantText(root.getChildAt(i));
            if (text.contains("Sketch •")) { title=text; break; }
        }
        String desired;
        if (title.contains("Sketch •")) {
            int start=title.indexOf("Sketch •");
            int end=title.indexOf('\n',start);
            desired=(end>start?title.substring(start,end):title.substring(start)).trim();
        } else {
            desired="Modeling • ISO • mm";
        }
        // setText() requests layout even if the visible value did not change.
        // Because this method is called from an OnLayoutChangeListener, writing
        // the same text continuously creates a relayout loop. Only mutate on a
        // real status transition (Modeling <-> Sketch / plane change).
        if (!desired.contentEquals(status.getText())) status.setText(desired);
    }

    private static String descendantText(View view) {
        StringBuilder out=new StringBuilder();
        collectText(view,out);
        return out.toString();
    }

    private static void collectText(View view,StringBuilder out) {
        if (view instanceof TextView) out.append(((TextView)view).getText()).append('\n');
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) collectText(group.getChildAt(i),out);
        }
    }

    private interface TextConsumer { void accept(TextView tv); }
    private static void forEachText(View view,TextConsumer consumer) {
        if (view instanceof TextView) consumer.accept((TextView)view);
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) forEachText(group.getChildAt(i),consumer);
        }
    }

    private static boolean contains(String haystack,String needle){return haystack!=null&&haystack.contains(needle);}
    private static int dp(View view,int value){return Math.round(value*view.getResources().getDisplayMetrics().density);}

    private static GradientDrawable panel(View view,int fill,int stroke,int radiusDp) {
        GradientDrawable d=new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(view,radiusDp));
        d.setStroke(Math.max(1,dp(view,1)),stroke);
        return d;
    }
}
