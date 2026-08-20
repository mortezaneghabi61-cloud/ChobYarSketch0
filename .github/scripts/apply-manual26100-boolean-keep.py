from pathlib import Path

TARGET = Path('app/src/main/java/ir/chobyar/sketch/ParametricHistorySolidCadCanvasView.java')
text = TARGET.read_text(encoding='utf-8')


def replace_once(old, new, label):
    global text
    if new in text:
        return
    if old not in text:
        raise SystemExit(f'{label}: anchor not found')
    text = text.replace(old, new, 1)


replace_once(
'''    private static final class BooleanFeature extends Feature {
        final String operation;
        final Object leftBody;
        final Object rightBody;
        BooleanFeature(int id,String operation,Object left,Object right) {
            super(id,operation);
            this.operation=operation;
            this.leftBody=left;
            this.rightBody=right;
        }
        @Override String detail() {
            String fa="UNION".equals(operation)?"Union":"SUBTRACT".equals(operation)?"Subtract":"Intersect";
            return fa+(broken?" • ⚠":"");
        }
    }''',
'''    private static final class BooleanFeature extends Feature {
        final String operation;
        final Object leftBody;
        final Object rightBody;
        final boolean keepLeft;
        final boolean keepRight;
        BooleanFeature(int id,String operation,Object left,Object right) {
            this(id,operation,left,right,false,false);
        }
        BooleanFeature(int id,String operation,Object left,Object right,boolean keepLeft,boolean keepRight) {
            super(id,operation);
            this.operation=operation;
            this.leftBody=left;
            this.rightBody=right;
            this.keepLeft=keepLeft;
            this.keepRight=keepRight;
        }
        @Override String detail() {
            String fa="UNION".equals(operation)?"Union":"SUBTRACT".equals(operation)?"Subtract":"Intersect";
            String keep=keepLeft&&keepRight?" • Keep Originals":keepLeft?" • Keep Target":keepRight?" • Keep Tool":"";
            return fa+keep+(broken?" • ⚠":"");
        }
    }''', 'BooleanFeature keep flags')

replace_once(
'''                .setItems(names,(d,w)->toast(applyHistoryBoolean(op,primary,options.get(w))))
                .setNegativeButton("لغو",null).show();
    }

    private String applyHistoryBoolean(String op,Object left,Object right) {''',
'''                .setItems(names,(d,w)->showBooleanKeepOptions(op,primary,options.get(w)))
                .setNegativeButton("لغو",null).show();
    }

    private void showBooleanKeepOptions(String op,Object target,Object tool) {
        String[] choices={
                "حذف هر دو ورودی",
                "Keep Originals • حفظ هر دو",
                "Keep Target • حفظ Body اصلی",
                "Keep Tool • حفظ Body دوم"
        };
        new AlertDialog.Builder(getContext())
                .setTitle(op+" • Keep Originals")
                .setMessage("Target: "+bodyName(target)+"\nTool: "+bodyName(tool))
                .setItems(choices,(d,w)->{
                    boolean keepLeft=w==1||w==2;
                    boolean keepRight=w==1||w==3;
                    toast(applyHistoryBoolean(op,target,tool,keepLeft,keepRight));
                })
                .setNegativeButton("لغو",null).show();
    }

    private String applyHistoryBoolean(String op,Object left,Object right) {''', 'Boolean keep UI')

replace_once(
'''    private String applyHistoryBoolean(String op,Object left,Object right) {
        try{
            Object before=selectedBody();
            setSelectedBody(left);
            String result=String.valueOf(applyBooleanMethod.invoke(this,op,left,right));
            Object out=selectedBody();
            if(out!=null && out!=left && out!=right && !result.contains("تغییر نکردند")){
                BooleanFeature f=new BooleanFeature(featureSerial++,op,left,right);
                f.outputBody=out;
                history.add(f);
                producerByBody.put(out,f);
                redoHistory.clear();
            } else if(before!=null) setSelectedBody(before);
            return result;
        }catch(Exception e){return "Boolean انجام نشد";}
    }''',
'''    private String applyHistoryBoolean(String op,Object left,Object right) {
        return applyHistoryBoolean(op,left,right,false,false);
    }

    private String applyHistoryBoolean(String op,Object left,Object right,boolean keepLeft,boolean keepRight) {
        try{
            Object before=selectedBody();
            setSelectedBody(left);
            String result=String.valueOf(applyBooleanMethod.invoke(this,op,left,right));
            Object out=selectedBody();
            if(out!=null && out!=left && out!=right && !result.contains("تغییر نکردند")){
                List<Object> current=bodies();
                if(keepLeft&&!current.contains(left))current.add(left);
                if(keepRight&&!current.contains(right))current.add(right);
                BooleanFeature f=new BooleanFeature(featureSerial++,op,left,right,keepLeft,keepRight);
                f.outputBody=out;
                history.add(f);
                producerByBody.put(out,f);
                redoHistory.clear();
                if(keepLeft||keepRight){
                    result += keepLeft&&keepRight?" • Keep Originals":keepLeft?" • Keep Target":" • Keep Tool";
                }
            } else if(before!=null) setSelectedBody(before);
            return result;
        }catch(Exception e){return "Boolean انجام نشد";}
    }''', 'History Boolean keep implementation')

replace_once(
'''    public String applyHistoryBooleanByIndex(String operation,int leftNumber,int rightNumber) {
        String op=operation==null?"":operation.trim().toUpperCase(Locale.US);
        if(!"UNION".equals(op)&&!"SUBTRACT".equals(op)&&!"INTERSECT".equals(op))
  return "عملیات Boolean نامعتبر است";
        List<Object> current=bodies();
        if(current.size()<2)return "برای Boolean حداقل دو Body لازم است";
        if(leftNumber<1||rightNumber<1||leftNumber>current.size()||rightNumber>current.size())
  return "شماره Body باید بین 1 تا "+current.size()+" باشد";
        if(leftNumber==rightNumber)return "دو Body متفاوت لازم است";
        return applyHistoryBoolean(op,current.get(leftNumber-1),current.get(rightNumber-1));
    }''',
'''    public String applyHistoryBooleanByIndex(String operation,int leftNumber,int rightNumber) {
        return applyHistoryBooleanByIndex(operation,leftNumber,rightNumber,false,false);
    }

    public String applyHistoryBooleanByIndex(String operation,int leftNumber,int rightNumber,boolean keepLeft,boolean keepRight) {
        String op=operation==null?"":operation.trim().toUpperCase(Locale.US);
        if(!"UNION".equals(op)&&!"SUBTRACT".equals(op)&&!"INTERSECT".equals(op))
  return "عملیات Boolean نامعتبر است";
        List<Object> current=bodies();
        if(current.size()<2)return "برای Boolean حداقل دو Body لازم است";
        if(leftNumber<1||rightNumber<1||leftNumber>current.size()||rightNumber>current.size())
  return "شماره Body باید بین 1 تا "+current.size()+" باشد";
        if(leftNumber==rightNumber)return "دو Body متفاوت لازم است";
        return applyHistoryBoolean(op,current.get(leftNumber-1),current.get(rightNumber-1),keepLeft,keepRight);
    }''', 'Boolean by-index overload')

replace_once(
'''      if(booleanOp&&a.length>1){
          if(a.length!=3)return op+" — دو شماره Body لازم است؛ مثال: "+op+" 1 2";
          try{
              return applyHistoryBooleanByIndex(op,Integer.parseInt(a[1]),Integer.parseInt(a[2]));
          }catch(NumberFormatException e){
              return "شماره Body باید عدد صحیح باشد";
          }
      }''',
'''      if(booleanOp&&a.length>1){
          if(a.length<3||a.length>4)return op+" — مثال: "+op+" 1 2 [KEEP|KEEP_TARGET|KEEP_TOOL]";
          try{
              boolean keepLeft=false,keepRight=false;
              if(a.length==4){
                  String keep=a[3].toUpperCase(Locale.US);
                  if("KEEP".equals(keep)||"KEEP_BOTH".equals(keep)||"KEEP_ORIGINALS".equals(keep)){keepLeft=true;keepRight=true;}
                  else if("KEEP_TARGET".equals(keep)||"KEEP_LEFT".equals(keep))keepLeft=true;
                  else if("KEEP_TOOL".equals(keep)||"KEEP_RIGHT".equals(keep))keepRight=true;
                  else return "گزینه Keep نامعتبر است";
              }
              return applyHistoryBooleanByIndex(op,Integer.parseInt(a[1]),Integer.parseInt(a[2]),keepLeft,keepRight);
          }catch(NumberFormatException e){
              return "شماره Body باید عدد صحیح باشد";
          }
      }''', 'Boolean KEEP command parser')

replace_once(
'''        if(f instanceof BooleanFeature){
            BooleanFeature b=(BooleanFeature)f;
            bs.remove(b.leftBody);bs.remove(b.rightBody);
        }''',
'''        if(f instanceof BooleanFeature){
            BooleanFeature b=(BooleanFeature)f;
            if(!b.keepLeft)bs.remove(b.leftBody);
            if(!b.keepRight)bs.remove(b.rightBody);
        }''', 'Boolean redo keep semantics')

TARGET.write_text(text, encoding='utf-8')
print('Manual 26.100 Boolean Keep Originals patch applied')
