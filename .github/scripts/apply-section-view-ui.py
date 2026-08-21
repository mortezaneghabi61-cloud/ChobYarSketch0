from pathlib import Path

path = Path('app/src/main/java/ir/chobyar/sketch/ChobYarActivity.java')
s = path.read_text(encoding='utf-8')

if 'private final SectionViewController sectionView=' not in s:
    old = '    private final CadAppearanceController appearance=new CadAppearanceController();\n'
    new = old + '    private final SectionViewController sectionView=new SectionViewController();\n'
    if old not in s:
        raise SystemExit('appearance field anchor not found')
    s = s.replace(old, new, 1)

old_sync = '        double[] mesh=cad.gpuMesh();gpuSurface.setMesh(mesh);cad.setGpuBodyRendering(mesh.length>=9);syncGpuCamera();'
new_sync = '        double[] mesh=cad.gpuMesh();gpuSurface.setMesh(sectionView.apply(mesh));cad.setGpuBodyRendering(mesh.length>=9);syncGpuCamera();'
if old_sync in s:
    s = s.replace(old_sync, new_sync, 1)
elif new_sync not in s:
    raise SystemExit('syncGpuMesh anchor not found')

body_anchor = '            adaptive.addView(tool("◉","Material",this::showMaterialPalette));\n            adaptive.addView(tool("∪","Boolean",cad::showSolidManager));'
body_replacement = '            adaptive.addView(tool("◉","Material",this::showMaterialPalette));\n            adaptive.addView(tool("◫","Section",this::showSectionViewPanel));\n            adaptive.addView(tool("∪","Boolean",cad::showSolidManager));'
if body_anchor in s:
    s = s.replace(body_anchor, body_replacement, 1)
elif body_replacement not in s:
    raise SystemExit('BODY adaptive anchor not found')

tools_anchor = '        adaptive.addView(tool("◉","Material",this::showMaterialPalette));\n        adaptive.addView(tool("⌖","Measure",cad::showSketchMeasureInspector));'
tools_replacement = '        adaptive.addView(tool("◉","Material",this::showMaterialPalette));\n        adaptive.addView(tool("◫","Section",this::showSectionViewPanel));\n        adaptive.addView(tool("⌖","Measure",cad::showSketchMeasureInspector));'
if tools_anchor in s:
    s = s.replace(tools_anchor, tools_replacement, 1)
elif tools_replacement not in s:
    raise SystemExit('Tools palette anchor not found')

if 'private void showSectionViewPanel()' not in s:
    marker = '    private void showMaterialPalette(){\n'
    if marker not in s:
        raise SystemExit('material palette method anchor not found')
    method = '''    private void showSectionViewPanel(){
        String[] choices={"خاموش","XY • محور Z","YZ • محور X","XZ • محور Y","Flip side"};
        LinearLayout box=plain(true);box.setPadding(dp(18),dp(4),dp(18),0);
        TextView offsetLabel=label("Offset • "+String.format(java.util.Locale.US,"%.1f mm",sectionView.offsetMm()),11,true);box.addView(offsetLabel);
        EditText offsetInput=new EditText(this);offsetInput.setSingleLine(true);offsetInput.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL|InputType.TYPE_NUMBER_FLAG_SIGNED);
        offsetInput.setText(String.format(java.util.Locale.US,"%.1f",sectionView.offsetMm()));box.addView(offsetInput,new LinearLayout.LayoutParams(dp(280),dp(48)));
        new AlertDialog.Builder(this).setTitle("Section View")
                .setMessage("این برش فقط نمای رندر را کلیپ می‌کند؛ هندسه OCCT، History، ابعاد و Export تغییر نمی‌کنند.")
                .setView(box).setSingleChoiceItems(choices,sectionView.selectedIndex(),(d,w)->{
                    if(w==0)sectionView.disable();
                    else if(w==1)sectionView.enable(SectionViewController.Axis.Z);
                    else if(w==2)sectionView.enable(SectionViewController.Axis.X);
                    else if(w==3)sectionView.enable(SectionViewController.Axis.Y);
                    else sectionView.flip();
                    syncGpuMesh();status(sectionView.summary());
                }).setPositiveButton("اعمال فاصله",(d,w)->{
                    try{sectionView.setOffsetMm(Double.parseDouble(offsetInput.getText().toString().trim()));}
                    catch(Exception ignored){status("Offset نامعتبر بود");return;}
                    if(!sectionView.isEnabled())sectionView.enable(SectionViewController.Axis.Z);
                    syncGpuMesh();status(sectionView.summary());
                }).setNegativeButton("بستن",null).show();
    }

'''
    s = s.replace(marker, method + marker, 1)

path.write_text(s, encoding='utf-8')
