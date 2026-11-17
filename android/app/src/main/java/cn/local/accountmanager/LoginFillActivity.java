package cn.local.accountmanager;

import android.app.*;
import android.content.Intent;
import android.os.Bundle;
import android.service.autofill.Dataset;
import android.view.autofill.*;
import android.widget.RemoteViews;
import cn.local.accountmanager.core.HistoryEntry;
import java.util.ArrayList;

/** Revalidate the record and calculate TOTP at selection time, not suggestion time. */
public final class LoginFillActivity extends Activity {
    @SuppressWarnings("deprecation") @Override public void onCreate(Bundle saved){
        super.onCreate(saved);PrivacyProtection.apply(this);setResult(RESULT_CANCELED);
        try{
            LoginFlow flow=LoginFlow.active;String state=getIntent().getStringExtra("state");
            HistoryEntry entry=flow==null?null:flow.current();
            if(entry==null||!flow.autofill||flow.credential!=null||!flow.login.state.equals(state)){finish();return;}
            ArrayList<AutofillId> ids=getIntent().getParcelableArrayListExtra("ids");ArrayList<String> roles=getIntent().getStringArrayListExtra("roles");
            if(ids==null||roles==null||ids.size()!=roles.size()||ids.isEmpty()||ids.size()>30){finish();return;}
            RemoteViews presentation=new RemoteViews(getPackageName(),android.R.layout.simple_list_item_1);presentation.setTextViewText(android.R.id.text1,"已选择的历史账号");Dataset.Builder result=new Dataset.Builder(presentation);
            String code=entry.currentCode(System.currentTimeMillis()/1000);
            for(int i=0;i<ids.size();i++){String role=roles.get(i);String value=switch(role){case "account"->entry.account;case "password"->entry.password;case "otp"->code;default->"";};if(role.matches("otp:[0-7]")){int index=role.charAt(4)-'0';if(index<code.length())value=code.substring(index,index+1);}if(!value.isEmpty())result.setValue(ids.get(i),AutofillValue.forText(value));}
            setResult(RESULT_OK,new Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT,result.build()));
        }catch(Exception ignored){/* A stale or malformed autofill request reveals no credentials. */}
        finish();
    }
}
