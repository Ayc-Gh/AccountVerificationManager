package cn.local.accountmanager.core;

import java.util.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import static cn.local.accountmanager.core.LoginJson.*;

/** OAuth PKCE and the upstream CPA/sub2api file formats. No persisted tokens. */
public final class OAuthLogin {
    public enum Provider { OPENAI, CLAUDE }
    public final Provider provider;
    public final String expectedEmail,state,verifier;
    public final long deadline=System.currentTimeMillis()+900000;
    private boolean used;
    public OAuthLogin(Provider provider,String email){this.provider=Objects.requireNonNull(provider);expectedEmail=email==null?"":email;state=random();verifier=random();}
    private static String random(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);return Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
    public int port(){return provider==Provider.OPENAI?1455:54545;}
    public String redirect(){return "http://localhost:"+port()+(provider==Provider.OPENAI?"/auth/callback":"/callback");}
    public String clientId(){return provider==Provider.OPENAI?"app_EMoamEEZ73f0CkXaXp7hrann":"9d1c250a-e61b-44d9-88ed-5944d1962f5e";}
    public String tokenEndpoint(){return provider==Provider.OPENAI?"https://auth.openai.com/oauth/token":"https://platform.claude.com/v1/oauth/token";}
    public String authorizationUrl(){
        String challenge;try{challenge=Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
        Map<String,Object> p=object("client_id",clientId(),"response_type","code","redirect_uri",redirect(),"state",state,"code_challenge",challenge,"code_challenge_method","S256");
        p.put("scope",provider==Provider.OPENAI?"openid email profile offline_access":"org:create_api_key user:profile user:inference user:sessions:claude_code user:mcp_servers user:file_upload");
        if(provider==Provider.OPENAI){p.put("prompt","login");p.put("id_token_add_organizations","true");p.put("codex_cli_simplified_flow","true");if(!expectedEmail.isEmpty())p.put("login_hint",expectedEmail);}else p.put("code","true");
        return (provider==Provider.OPENAI?"https://auth.openai.com/oauth/authorize?":"https://claude.ai/oauth/authorize?")+form(p);
    }
    private Map<String,String> callbackParameters(String text){
        if(System.currentTimeMillis()>=deadline)throw new IllegalArgumentException("登录已超时，请重新开始。");
        URI uri;try{uri=new URI(text==null?"":text.trim());}catch(URISyntaxException e){throw new IllegalArgumentException("请粘贴完整 localhost 回调地址。");}
        if(text==null||text.length()>16384||!"http".equals(uri.getScheme())||!"localhost".equals(uri.getHost())||uri.getPort()!=port()||!URI.create(redirect()).getRawPath().equals(uri.getRawPath())||uri.getRawUserInfo()!=null||uri.getRawFragment()!=null)throw new IllegalArgumentException("请粘贴本次登录的完整 localhost 回调地址。");
        Map<String,String> p=query(uri.getRawQuery());
        if(!MessageDigest.isEqual(state.getBytes(StandardCharsets.US_ASCII),p.getOrDefault("state","").getBytes(StandardCharsets.UTF_8)))throw new IllegalArgumentException("回调不属于本次登录，请重新授权。");
        return p;
    }
    public boolean isDenial(String text){try{return callbackParameters(text).containsKey("error");}catch(IllegalArgumentException e){return false;}}
    public synchronized String validateCallback(String text){
        Map<String,String> p=callbackParameters(text);
        if(p.containsKey("error")){
            if("access_denied".equals(p.get("error")))throw new IllegalArgumentException("本次授权已被拒绝，请重新登录。");
            throw new IllegalArgumentException("官方网页未授予授权，请重新登录。");
        }
        String code=p.getOrDefault("code","");if(code.trim().isEmpty()||code.length()>8192)throw new IllegalArgumentException("回调缺少授权码。");
        if(used)throw new IllegalArgumentException("本次授权码已处理，请重新登录。");return code;
    }
    public synchronized Map<String,Object> takeExchange(String callback){String code=validateCallback(callback);used=true;Map<String,Object> p=object("grant_type","authorization_code","code",code,"redirect_uri",redirect(),"client_id",clientId(),"code_verifier",verifier);if(provider==Provider.CLAUDE)p.put("state",state);return p;}
    public static Map<String,String> query(String query){Map<String,String> p=new LinkedHashMap<>();if(query==null)return p;for(String part:query.split("&")){if(part.isEmpty())continue;int at=part.indexOf('=');String k=unescape(at<0?part:part.substring(0,at));String v=unescape(at<0?"":part.substring(at+1));if(p.put(k,v)!=null)throw new IllegalArgumentException("回调包含重复参数。");}return p;}
    private static String escape(String s){try{return URLEncoder.encode(s,"UTF-8");}catch(UnsupportedEncodingException e){throw new IllegalStateException(e);}}
    private static String unescape(String s){try{return URLDecoder.decode(s,"UTF-8");}catch(UnsupportedEncodingException e){throw new IllegalStateException(e);}}
    public static String form(Map<String,Object> p){List<String> parts=new ArrayList<>();for(var entry:p.entrySet())parts.add(escape(entry.getKey())+"="+escape(entry.getValue().toString()));return String.join("&",parts);}
    private static Map<String,Object> claims(String token){try{String[] p=token.split("\\.",-1);if(p.length!=3||p[1].length()>131072)return Collections.emptyMap();return map(decode(new String(Base64.getUrlDecoder().decode(p[1]),StandardCharsets.UTF_8)));}catch(IllegalArgumentException e){return Collections.emptyMap();}}
    public Credential parseTokens(String json,long now){
        Map<String,Object> p=map(decode(json));String access=string(p,"access_token"),refresh=string(p,"refresh_token"),id=string(p,"id_token");long seconds;
        try{seconds=Long.parseLong(String.valueOf(p.get("expires_in")));}catch(NumberFormatException e){throw new IllegalArgumentException("授权服务未返回有效期限。");}
        if(access.trim().isEmpty()||refresh.trim().isEmpty()||seconds<1||seconds>31536000)throw new IllegalArgumentException("授权服务未返回完整、可刷新的凭据。");
        Map<String,Object> c=claims(id),a=claims(access),auth=map(c.get("https://api.openai.com/auth"));if(auth.isEmpty())auth=map(a.get("https://api.openai.com/auth"));
        String email=string(c,"email"),account=string(auth,"chatgpt_account_id"),user=string(auth,"chatgpt_user_id"),plan=string(auth,"chatgpt_plan_type"),org="";
        if(email.isEmpty())email=string(map(a.get("https://api.openai.com/profile")),"email");
        if(provider==Provider.CLAUDE){Map<String,Object> ac=map(p.get("account"));email=string(ac,"email_address");account=string(ac,"uuid");org=string(map(p.get("organization")),"uuid");}
        if(email.isEmpty()||account.isEmpty()||(provider==Provider.OPENAI&&id.isEmpty()))throw new IllegalArgumentException("授权结果缺少账号身份信息，未生成文件。");
        if(!expectedEmail.isEmpty()&&!expectedEmail.trim().equalsIgnoreCase(email.trim()))throw new IllegalArgumentException("登录账号与选择的历史账号不同，未保留此次凭据。请退出网页中的其他账号后重试。");
        return new Credential(provider,email,access,refresh,id,account,user,plan,org,now,Math.addExact(now,seconds*1000),clientId());
    }
    public Credential exchange(String callback)throws IOException {
        Map<String,Object> p=takeExchange(callback);byte[] body=(provider==Provider.OPENAI?form(p):encode(p)).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection=(HttpURLConnection)new URL(tokenEndpoint()).openConnection();connection.setRequestMethod("POST");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(30000);connection.setReadTimeout(30000);connection.setDoOutput(true);connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("Content-Type",provider==Provider.OPENAI?"application/x-www-form-urlencoded":"application/json");connection.setFixedLengthStreamingMode(body.length);
        try{try(OutputStream output=connection.getOutputStream()){output.write(body);}int status=connection.getResponseCode();if(status!=200)throw new IOException("授权交换失败（HTTP "+status+"），请重新登录。");try(InputStream input=connection.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=input.read(b))!=-1){if(bytes.size()+n>1048576)throw new IOException("授权响应过大。");bytes.write(b,0,n);}return parseTokens(bytes.toString(StandardCharsets.UTF_8.name()),System.currentTimeMillis());}}finally{Arrays.fill(body,(byte)0);connection.disconnect();}
    }
    public static boolean allowsFill(Provider p,URI uri){if(uri==null||!"https".equals(uri.getScheme())||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getRawUserInfo()!=null)return false;String h=uri.getHost();return p==Provider.OPENAI?"auth.openai.com".equals(h):Arrays.asList("claude.ai","platform.claude.com","console.anthropic.com").contains(h==null?"":h);}
    public static final class Credential {
        public final Provider provider;public final String email,access,refresh,idToken,accountId,userId,plan,organization,clientId;public final long created,expires;
        public Credential(Provider p,String email,String access,String refresh,String id,String account,String user,String plan,String org,long created,long expires,String clientId){provider=p;this.email=email;this.access=access;this.refresh=refresh;idToken=id;accountId=account;userId=user;this.plan=plan;organization=org;this.created=created;this.expires=expires;this.clientId=clientId;}
        private static String stamp(long ms){return Instant.ofEpochMilli(ms).toString();}
        public String export(boolean sub2api){
            if(System.currentTimeMillis()>=expires)throw new IllegalArgumentException("登录令牌已到期，请重新登录后导出。");
            Map<String,Object> p=object("access_token",access,"refresh_token",refresh,"id_token",idToken,"email",email);
            if(!sub2api){p.put("type",provider==Provider.OPENAI?"codex":"claude");p.put("last_refresh",stamp(created));p.put("expired",stamp(expires));if(provider==Provider.OPENAI){p.put("account_id",accountId);if(!plan.isEmpty())p.put("plan_type",plan);}else{p.put("account_uuid",accountId);if(!organization.isEmpty())p.put("organization_uuid",organization);}return encode(p);}
            p.put("client_id",clientId);p.put("expires_at",stamp(expires));if(provider==Provider.OPENAI){p.put("chatgpt_account_id",accountId);if(!userId.isEmpty())p.put("chatgpt_user_id",userId);if(!plan.isEmpty())p.put("plan_type",plan);}else{p.put("account_uuid",accountId);if(!organization.isEmpty())p.put("org_uuid",organization);}
            Map<String,Object> a=object("name",email,"platform",provider==Provider.OPENAI?"openai":"anthropic","type","oauth","credentials",p,"extra",object("email",email),"concurrency",1,"priority",1,"rate_multiplier",1,"auto_pause_on_expired",true);
            return encode(object("type","sub2api-data","version",1,"exported_at",stamp(System.currentTimeMillis()),"proxies",Collections.emptyList(),"accounts",Collections.singletonList(a)));
        }
    }
}
