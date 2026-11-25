package cn.local.accountmanager;

import android.content.ContentResolver;
import android.net.Uri;
import cn.local.accountmanager.core.*;
import cn.local.accountmanager.storage.HistoryRepository;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** A single bounded in-memory login, retained through Activity rotations. */
final class LoginFlow implements AutoCloseable {
    interface Listener { void updated(); }
    static final String EXPIRED_NOTICE = "历史记录失效或登录超时，请重新开始。";
    // A rotating Activity can retain the outcome without retaining the record or OAuth credentials.
    static final class EndNotice { volatile String text; }
    final EndNotice endNotice = new EndNotice();
    static final class ExportRequest {
        private static final int FIRST_CODE = 0x4000;
        private static final int CODE_MASK = 0x3fff;
        private static final AtomicInteger sequence = new AtomicInteger();
        final int code = FIRST_CODE + (sequence.getAndIncrement() & CODE_MASK);
        final boolean sub2api;
        private boolean writing;
        ExportRequest(boolean sub2api){this.sub2api=sub2api;}
        static boolean ownsCode(int code){return code>=FIRST_CODE && code<=FIRST_CODE+CODE_MASK;}
    }
    private volatile ExportRequest export;
    static volatile LoginFlow active;
    final OAuthLogin login;
    final String recordId, original;
    final HistoryRepository history;
    final boolean autofill;
    volatile OAuthLogin.Credential credential;
    volatile String notice="请在官方网页完成登录与授权。";
    volatile Listener listener;
    volatile boolean closed;
    final AtomicBoolean exchanging=new AtomicBoolean();
    private ServerSocket server;
    private final android.os.Handler expiry=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable checkExpiry=new Runnable(){@Override public void run(){
        expireIfInvalid();
        if (!closed) expiry.postDelayed(this,1000);
    }};
    LoginFlow(HistoryRepository history,HistoryEntry entry,OAuthLogin.Provider provider,boolean autofill){this.history=history;recordId=entry.id;original=entry.originalText();login=new OAuthLogin(provider,entry.account);this.autofill=autofill;expiry.postDelayed(checkExpiry,1000);}
    HistoryEntry current(){HistoryEntry e=history.failed()?null:history.find(recordId);return !closed&&System.currentTimeMillis()<login.deadline&&e!=null&&e.originalText().equals(original)?e:null;}
    synchronized void expireIfInvalid(){
        if (!closed && (current()==null || (credential!=null && System.currentTimeMillis()>=credential.expires))) end(EXPIRED_NOTICE);
    }
    void notifyChange(){Listener l=listener;if(l!=null)l.updated();}
    boolean isExporting(){return export!=null;}
    synchronized ExportRequest beginExport(boolean sub2api){
        expireIfInvalid();
        if(closed || credential==null || export!=null) return null;
        export=new ExportRequest(sub2api);
        return export;
    }
    synchronized void finishExport(ExportRequest request, String message){
        if(export!=request) return;
        export=null;
        if(message!=null) notice=message;
        notifyChange();
    }
    synchronized void acceptExportResult(int code, Uri destination, ContentResolver resolver){
        ExportRequest request=export;
        if(request==null || request.code!=code || request.writing) return;
        if(destination==null){finishExport(request,null);return;}
        expireIfInvalid();
        if(closed) return;
        request.writing=true;
        new Thread(()->writeExport(request,destination,resolver),"login-export").start();
    }
    private synchronized boolean canWrite(ExportRequest request){
        expireIfInvalid();
        return export==request && credential!=null;
    }
    private void writeExport(ExportRequest request, Uri destination, ContentResolver resolver){
        String message="文件写入失败或登录结果失效，请重试。";
        byte[] bytes=null;
        try{
            if(!canWrite(request)) return;
            // Opening or writing a provider document can block while the Activity is being recreated.
            try(OutputStream output=resolver.openOutputStream(destination,"wt")){
                if(output==null) throw new IOException();
                synchronized(this){
                    if(!canWrite(request)) return;
                    bytes=credential.export(request.sub2api).getBytes(StandardCharsets.UTF_8);
                }
                output.write(bytes);
            }
            message=(request.sub2api?"sub2api":"CPA")+" 文件已生成，可继续生成另一种格式。";
        }catch(Exception ignored){
            // Provider messages can include private paths. Show the fixed write error.
        }finally{
            if(bytes!=null) java.util.Arrays.fill(bytes,(byte)0);
            finishExport(request,message);
        }
    }
    void startServer()throws IOException {
        server=new ServerSocket();server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),login.port()),4);server.setSoTimeout(1000);
        new Thread(()->{
            while(!closed&&current()!=null){
                try(Socket socket=server.accept()){
                    socket.setSoTimeout(2000);InputStream input=socket.getInputStream();ByteArrayOutputStream bytes=new ByteArrayOutputStream();int value;
                    while(bytes.size()<16384&&(value=input.read())!=-1){bytes.write(value);if(bytes.size()>=4&&bytes.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n"))break;}
                    String[] request=bytes.toString(StandardCharsets.US_ASCII.name()).split("\r\n",2)[0].split(" ");String callback=request.length==3&&request[0].equals("GET")&&request[1].startsWith("/")&&!request[1].startsWith("//")?"http://localhost:"+login.port()+request[1]:"";
                    boolean valid=login.isDenial(callback);try{login.validateCallback(callback);valid=true;}catch(IllegalArgumentException e){/* A verified denial is delivered to the UI too. */}
                    byte[] body=(valid?"<!doctype html><meta charset=utf-8><title>账号验证管家</title>已收到回调，请回到账号验证管家查看结果。":"Invalid or expired callback.").getBytes(StandardCharsets.UTF_8);
                    String header="HTTP/1.1 "+(valid?"200 OK":"400 Bad Request")+"\r\nContent-Type: text/html; charset=utf-8\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'\r\nConnection: close\r\nContent-Length: "+body.length+"\r\n\r\n";
                    socket.getOutputStream().write(header.getBytes(StandardCharsets.US_ASCII));socket.getOutputStream().write(body);if(valid)complete(callback);
                }catch(IOException ignored){if(closed||server.isClosed())break;}
            }
            stopServer();
        },"login-callback").start();
    }
    synchronized void complete(String address){
        if(current()==null||credential!=null)return;
        try{login.validateCallback(address);}catch(IllegalArgumentException e){
            if(login.isDenial(address)) end(e.getMessage());
            else { notice=e.getMessage(); notifyChange(); }
            return;
        }
        if(!exchanging.compareAndSet(false,true))return;notice="正在向官方授权服务交换凭据…";notifyChange();
        new Thread(()->{
            try{
                OAuthLogin.Credential c=login.exchange(address);
                synchronized(LoginFlow.this){
                    if(current()!=null){credential=c;notice="登录成功："+c.email+"。可分别生成 CPA 和 sub2api 文件。";}
                }
            }
            catch(IllegalArgumentException e){notice=e.getMessage()+" 请重新开始登录。";}
            catch(Exception e){notice="授权交换失败，请检查网络后重新开始登录。";}
            finally{exchanging.set(false);stopServer();notifyChange();}
        },"login-exchange").start();
    }
    private void stopServer(){try{if(server!=null)server.close();}catch(IOException ignored){}}
    private synchronized void end(String reason){
        if (closed) return;
        endNotice.text=reason;
        closed=true;
        credential=null;
        export=null;
        Listener previous=listener;
        listener=null;
        expiry.removeCallbacks(checkExpiry);
        stopServer();
        if(active==this)active=null;
        if(reason!=null && previous!=null)previous.updated();
    }
    @Override public void close(){end(null);}
}
