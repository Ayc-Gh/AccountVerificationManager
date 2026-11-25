package cn.local.accountmanager;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.*;
import cn.local.accountmanager.core.*;
import cn.local.accountmanager.storage.HistoryRepository;

/** System browser OAuth UI. Intents contain an opaque history ID only. */
public final class LoginActivity extends Activity implements LoginFlow.Listener {
    private static final int ENABLE_AUTOFILL=207;
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView status;
    private Button start,cpa,sub,finish;
    private EditText callback;
    private Spinner provider,mode;
    private String recordId;
    private LoginFlow flow;
    private LoginFlow.EndNotice lastEndNotice;
    private HistoryRepository history;
    private final HistoryRepository.Listener historyChanged=this::render;
    private final Runnable tick=new Runnable(){@Override public void run(){render();main.postDelayed(this,1000);}};
    @Override public void onCreate(Bundle state){
        super.onCreate(state);PrivacyProtection.apply(this);history=((ManagerApplication)getApplication()).history;recordId=getIntent().getStringExtra("record_id");
        flow=LoginFlow.active;if(flow!=null&&!flow.recordId.equals(recordId)){flow.close();flow=null;}
        if(flow!=null)flow.listener=this;
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);int p=(int)(20*getResources().getDisplayMetrics().density);content.setPadding(p,p,p,p);scroll.addView(content);setContentView(scroll);
        title(content,"登录并生成 CPA / sub2api",21);
        title(content,"选择服务",14);provider=picker(content,new String[]{"ChatGPT / OpenAI（Codex）","Claude / Anthropic"});provider.setContentDescription("登录服务");
        title(content,"选择登录方式",14);mode=picker(content,new String[]{"历史账号自动填充","官方网页手动登录与授权"});mode.setContentDescription("登录方式");
        title(content,"自动模式在 Chrome / Edge 的官方页面提供账号、密码和验证器 2FA 建议，点击系统建议填入。首次需启用本应用的自动填充服务；Chrome 设置中还需选择使用其他自动填充服务。\n请自行完成邮箱验证码、人机验证及网页授权。",14);
        start=button(content,"打开官方网页",this::begin);start.setTag("login-start");
        title(content,"网页未自动返回时，粘贴地址栏中的完整 localhost 回调地址：",14);
        callback=new EditText(this);callback.setSingleLine(true);callback.setSaveEnabled(false);callback.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);callback.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);callback.setContentDescription("授权回调地址");content.addView(callback);
        finish=button(content,"完成登录",()->{if(flow!=null){flow.complete(callback.getText().toString());callback.setText("");}});
        status=new TextView(this);status.setTextSize(14);status.setPadding(0,p,0,p);content.addView(status);
        if(flow==null && getLastNonConfigurationInstance() instanceof LoginFlow.EndNotice previous){
            lastEndNotice=previous;
            status.setText(previous.text);
        }
        title(content,"JSON 文件包含可用登录令牌，请妥善保管。登录结果只在内存暂存，关闭此页面后清除。",14);
        cpa=button(content,"生成 CPA 文件",()->export(false));sub=button(content,"生成 sub2api 文件",()->export(true));button(content,"关闭并清除登录结果",this::finish);
        if(flow!=null){provider.setSelection(flow.login.provider.ordinal());mode.setSelection(flow.autofill?0:1);}render();
        if(state!=null && flow==null){provider.setSelection(Math.max(0,Math.min(1,state.getInt("provider"))));mode.setSelection(Math.max(0,Math.min(1,state.getInt("mode"))));}
    }
    private void title(LinearLayout parent,String text,int sp){TextView view=new TextView(this);view.setText(text);view.setTextSize(sp);view.setPadding(0,10,0,10);parent.addView(view);}
    private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setOnClickListener(v->action.run());parent.addView(b,new LinearLayout.LayoutParams(-1,-2));return b;}
    @android.annotation.SuppressLint("ClickableViewAccessibility") // Native Spinner popup lacks FLAG_SECURE; this dialog announces its own click.
    private Spinner picker(LinearLayout parent,String[] values){
        // Keep all app-owned popup windows protected, including unit/provider pickers.
        Spinner s=new Spinner(this,Spinner.MODE_DIALOG){@Override public boolean performClick(){sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);PrivacyProtection.show(new AlertDialog.Builder(LoginActivity.this).setTitle(getContentDescription()).setSingleChoiceItems(values,getSelectedItemPosition(),(d,w)->{setSelection(w);d.dismiss();}).setNegativeButton("取消",null));return true;}};
        s.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,values));parent.addView(s,new LinearLayout.LayoutParams(-1,-2));return s;
    }
    private void begin(){
        if(!history.loaded() || (flow!=null && flow.isExporting())) return;
        lastEndNotice=null;
        HistoryEntry entry=history.failed()?null:history.find(recordId);if(entry==null||entry.account.isEmpty()){status.setText("请从包含账号的未到期历史记录开始登录。");return;}
        boolean auto=mode.getSelectedItemPosition()==0;
        if(auto&&Build.VERSION.SDK_INT<28){status.setText(R.string.login_https_unavailable);return;}
        if(auto){AutofillManager manager=getSystemService(AutofillManager.class);if(manager==null||!manager.hasEnabledAutofillServices()){
            PrivacyProtection.show(new AlertDialog.Builder(this).setTitle("启用历史账号自动填充").setMessage("系统只在你选择建议后填写。本应用仅在本次登录期间向已校验签名的 Chrome / Edge 官方登录页面提供所选记录。请在系统设置中选择本应用，再返回点击打开官方网页。")
                .setNegativeButton("取消",null).setPositiveButton("打开系统设置",(d,w)->{try{startActivityForResult(new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE,Uri.parse("package:"+getPackageName())),ENABLE_AUTOFILL);}catch(ActivityNotFoundException e){status.setText("系统不支持此自动填充入口，可选择手动登录。");}}));return;
        }}
        if(flow!=null)flow.close();flow=null;
        LoginFlow next=new LoginFlow(history,entry,OAuthLogin.Provider.values()[provider.getSelectedItemPosition()],auto);
        try{
            Intent browser=new Intent(Intent.ACTION_VIEW,Uri.parse(next.login.authorizationUrl()));browser.addCategory(Intent.CATEGORY_BROWSABLE);
            if(auto){String trusted=LoginAutofillService.browser(this);if(trusted==null)throw new IllegalArgumentException("自动填充需要官方 Chrome 或 Edge，请安装后重试，或选择手动登录。");browser.setPackage(trusted);}
            next.startServer();flow=next;LoginFlow.active=next;next.listener=this;startActivity(browser);render();
        }catch(Exception e){next.close();flow=null;status.setText(e instanceof IllegalArgumentException?e.getMessage():"无法打开网页或回调端口已被占用，请关闭其他登录窗口后重试。");}
    }
    private void render(){
        if(isDestroyed())return;
        if(flow!=null){
            flow.expireIfInvalid();
            if(flow.closed){
                lastEndNotice=flow.endNotice;
                flow=null;
                status.setText(lastEndNotice.text==null?LoginFlow.EXPIRED_NOTICE:lastEndNotice.text);
            }
        }
        boolean ready=flow!=null&&flow.credential!=null;
        boolean exporting=flow!=null&&flow.isExporting();
        cpa.setEnabled(ready&&!exporting);sub.setEnabled(ready&&!exporting);finish.setEnabled(flow!=null&&!ready&&!flow.exchanging.get());
        start.setEnabled(history.loaded()&&!exporting&&(flow==null||!flow.exchanging.get()));provider.setEnabled(flow==null);mode.setEnabled(flow==null);
        start.setText(!history.loaded()?"正在读取历史记录…":flow==null?"打开官方网页":"重新开始登录");if(flow!=null)status.setText(flow.notice);
    }
    @Override public void updated(){main.post(this::render);}
    private void export(boolean sub2api){
        LoginFlow owner=flow;
        if(owner==null) return;
        LoginFlow.ExportRequest request=owner.beginExport(sub2api);
        render();
        if(request==null || flow!=owner) return;
        Intent save=new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,(sub2api?"sub2api-":owner.login.provider==OAuthLogin.Provider.OPENAI?"codex-":"claude-")+System.currentTimeMillis()+".json");
        try{startActivityForResult(save,request.code);}
        catch(ActivityNotFoundException | SecurityException e){owner.finishExport(request,"无法打开系统文件保存器，请重试。");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(!LoginFlow.ExportRequest.ownsCode(request)) return;
        if(flow!=null){
            Uri destination=result==RESULT_OK && data!=null ? data.getData() : null;
            flow.acceptExportResult(request,destination,getApplicationContext().getContentResolver());
            render();
        }else if(result==RESULT_OK && lastEndNotice==null){
            lastEndNotice=new LoginFlow.EndNotice();
            lastEndNotice.text="登录结果已失效，未写入文件。";
            status.setText(lastEndNotice.text);
        }
    }
    @Override protected void onStart(){super.onStart();history.listen(historyChanged);render();}
    @Override protected void onStop(){history.unlisten(historyChanged);super.onStop();}
    @Override protected void onResume(){super.onResume();main.post(tick);}
    @Override public Object onRetainNonConfigurationInstance(){return flow==null?lastEndNotice:flow.endNotice;}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("provider",provider.getSelectedItemPosition());out.putInt("mode",mode.getSelectedItemPosition());super.onSaveInstanceState(out);}
    @Override protected void onPause(){main.removeCallbacks(tick);super.onPause();}
    @Override protected void onDestroy(){main.removeCallbacksAndMessages(null);if(flow!=null){if(isChangingConfigurations())flow.listener=null;else flow.close();}callback.setText("");super.onDestroy();}
}
