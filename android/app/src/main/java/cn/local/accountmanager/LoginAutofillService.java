package cn.local.accountmanager;

import android.app.*;
import android.app.assist.AssistStructure;
import android.content.*;
import android.content.pm.*;
import android.os.*;
import android.service.autofill.*;
import android.util.Base64;
import android.view.ViewStructure.HtmlInfo;
import android.view.autofill.*;
import android.widget.RemoteViews;
import cn.local.accountmanager.core.OAuthLogin;
import java.net.URI;
import java.security.MessageDigest;
import java.util.*;

/** Suggestions exist only during a user-started login, for a signed browser and exact HTTPS origin. */
public final class LoginAutofillService extends AutofillService {
    // SHA-512 certificate pins from Microsoft's Android MSAL browser safelist.
    private static final Map<String,List<String>> PINS=new LinkedHashMap<>();
    static {
        PINS.put("com.android.chrome",Collections.singletonList("7fmduHKTdHHrlMvldlEqAIlSfii1tl35bxj1OXN5Ve8c4lU6URVu4xtSHc3BVZxS6WWJnxMDhIfQN0N0K2NDJg=="));
        PINS.put("com.microsoft.emmx",Arrays.asList("Ivy-Rk6ztai_IudfbyUrSHugzRqAtHWslFvHT0PTvLMsEKLUIgv7ZZbVxygWy_M5mOPpfjZrd3vOx3t-cA6fVQ==","KxJRZ8RFW-6BQa-e4xNE7UmeGU6BWIR_6dzgaAOQWh0rWVENxsXU5TjnWuTR9GqOFbCKMilXKIu7as6VJRjuSw=="));
    }
    @SuppressWarnings("deprecation") static boolean trusted(Context context,String name){
        if(!PINS.containsKey(name))return false;
        try{
            PackageInfo info=context.getPackageManager().getPackageInfo(name,Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);
            android.content.pm.Signature[] signatures=Build.VERSION.SDK_INT>=28?(info.signingInfo.hasMultipleSigners()?info.signingInfo.getApkContentsSigners():info.signingInfo.getSigningCertificateHistory()):info.signatures;
            if(signatures==null||signatures.length==0)return false;
            // A multiple-signer APK must have every signer recognized.
            boolean found=false;for(android.content.pm.Signature signature:signatures){String hash=Base64.encodeToString(MessageDigest.getInstance("SHA-512").digest(signature.toByteArray()),Base64.URL_SAFE|Base64.NO_WRAP);boolean match=PINS.get(name).contains(hash);if(Build.VERSION.SDK_INT>=28&&info.signingInfo.hasMultipleSigners()&&!match)return false;found|=match;}return found;
        }catch(Exception e){return false;}
    }
    static String browser(Context context){for(String name:PINS.keySet())if(trusted(context,name))return name;return null;}
    @Override public void onFillRequest(FillRequest request,CancellationSignal cancellation,FillCallback callback){
        LoginFlow flow=LoginFlow.active;
        if(cancellation.isCanceled()||flow==null||!flow.autofill||flow.credential!=null||flow.current()==null||request.getFillContexts().isEmpty()){callback.onSuccess(null);return;}
        cn.local.accountmanager.core.HistoryEntry selected=flow.current();if(selected==null){callback.onSuccess(null);return;}
        AssistStructure structure=request.getFillContexts().get(request.getFillContexts().size()-1).getStructure();
        if(structure.getActivityComponent()==null||!trusted(this,structure.getActivityComponent().getPackageName())){callback.onSuccess(null);return;}
        ArrayList<AutofillId> ids=new ArrayList<>();ArrayList<String> roles=new ArrayList<>();
        StringBuilder labels=new StringBuilder();for(int i=0;i<structure.getWindowNodeCount();i++)labels(structure.getWindowNodeAt(i).getRootViewNode(),flow.login.provider,null,labels,0);
        String page=labels.toString().toLowerCase(Locale.ROOT);boolean totp=page.matches("(?s).*(authenticator|authentication app|身份验证器|身份验证应用|验证器).*")&&!page.matches("(?s).*(email.{0,30}code|code.{0,30}email|邮箱验证码|邮件验证码).*");
        for(int i=0;i<structure.getWindowNodeCount();i++)scan(structure.getWindowNodeAt(i).getRootViewNode(),flow.login.provider,null,totp,ids,roles,0);
        int single=Collections.frequency(roles,"otp-single"),whole=Collections.frequency(roles,"otp");
        if(single>0){int digit=0;for(int i=roles.size()-1;i>=0;i--)if(roles.get(i).equals("otp-single")){if(whole==0&&single==selected.otp.digits)roles.set(i,"otp:"+(single-1-digit++));else{roles.remove(i);ids.remove(i);}}}
        if(ids.isEmpty()||cancellation.isCanceled()||flow.current()==null){callback.onSuccess(null);return;}
        RemoteViews presentation=new RemoteViews(getPackageName(),android.R.layout.simple_list_item_1);presentation.setTextViewText(android.R.id.text1,"填入此次选择的历史账号");
        Intent auth=new Intent(this,LoginFillActivity.class).putExtra("state",flow.login.state).putParcelableArrayListExtra("ids",ids).putStringArrayListExtra("roles",roles);
        auth.setData(android.net.Uri.parse("avm-login://fill/"+flow.login.state));
        PendingIntent pending=PendingIntent.getActivity(this,flow.login.state.hashCode(),auth,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        Dataset.Builder dataset=new Dataset.Builder(presentation);for(AutofillId id:ids)dataset.setValue(id,null);
        dataset.setAuthentication(pending.getIntentSender());
        callback.onSuccess(new FillResponse.Builder().addDataset(dataset.build()).build());
    }
    private static void labels(AssistStructure.ViewNode node,OAuthLogin.Provider provider,String inherited,StringBuilder labels,int depth){
        if(depth>100||labels.length()>50000)return;String origin=inherited;
        if(node.getWebDomain()!=null)origin=Build.VERSION.SDK_INT>=28&&node.getWebScheme()!=null?node.getWebScheme()+"://"+node.getWebDomain():null;
        try{if(origin!=null&&OAuthLogin.allowsFill(provider,URI.create(origin))&&node.getAutofillType()!=android.view.View.AUTOFILL_TYPE_TEXT&&node.getText()!=null)labels.append(' ').append(node.getText());}catch(IllegalArgumentException ignored){}
        for(int i=0;i<node.getChildCount();i++)labels(node.getChildAt(i),provider,origin,labels,depth+1);
    }
    private static void scan(AssistStructure.ViewNode node,OAuthLogin.Provider provider,String inherited,boolean otpContext,ArrayList<AutofillId> ids,ArrayList<String> roles,int depth){
        if(depth>100||ids.size()>=30)return;
        String domain=node.getWebDomain(),scheme=Build.VERSION.SDK_INT>=28?node.getWebScheme():null;String origin=inherited;
        if(domain!=null)origin=(scheme==null?"":scheme)+"://"+domain;
        if(domain!=null&&scheme==null)origin=null; // Never guess HTTPS for compatibility structures.
        String text=node.getText()==null?"":node.getText().toString().toLowerCase(Locale.ROOT);
        boolean totp=otpContext||text.matches("(?s).*(authenticator|authentication app|身份验证器|身份验证应用|验证器).*?");
        if(text.matches("(?s).*(email.{0,30}code|code.{0,30}email|邮箱验证码|邮件验证码).*"))totp=false;
        boolean allowed=false;try{allowed=origin!=null&&OAuthLogin.allowsFill(provider,URI.create(origin));}catch(IllegalArgumentException ignored){}
        if(allowed&&node.getAutofillId()!=null&&node.getAutofillType()==android.view.View.AUTOFILL_TYPE_TEXT){
            Map<String,String> attrs=new HashMap<>();HtmlInfo html=node.getHtmlInfo();if(html!=null&&html.getAttributes()!=null)for(android.util.Pair<String,String> a:html.getAttributes())attrs.put(a.first.toLowerCase(Locale.ROOT),a.second==null?"":a.second.toLowerCase(Locale.ROOT));
            String hints=String.join(" ",node.getAutofillHints()==null?new String[0]:node.getAutofillHints()).toLowerCase(Locale.ROOT);
            String auto=attrs.getOrDefault("autocomplete",""),name=attrs.getOrDefault("name","")+" "+attrs.getOrDefault("id","");String type=attrs.getOrDefault("type","");String role="";
            if(totp&&(auto.equals("one-time-code")||name.matches(".*(totp|otp|code).*")||attrs.getOrDefault("maxlength","").equals("1")))role=attrs.getOrDefault("maxlength","").equals("1")?"otp-single":"otp";
            else if(!auto.contains("new-password")&&!hints.contains("newpassword")&&(type.equals("password")||hints.contains("password")))role="password";
            else if(type.equals("email")||auto.equals("username")||auto.equals("email")||hints.contains("email")||hints.contains("username"))role="account";
            if(!role.isEmpty()){ids.add(node.getAutofillId());roles.add(role);}
        }
        // Domain changes on iframe nodes establish a new origin, so cross-origin
        // children cannot inherit permission from the top page.
        for(int i=0;i<node.getChildCount();i++)scan(node.getChildAt(i),provider,origin,totp,ids,roles,depth+1);
    }
    @Override public void onSaveRequest(SaveRequest request,SaveCallback callback){callback.onSuccess();}
}
