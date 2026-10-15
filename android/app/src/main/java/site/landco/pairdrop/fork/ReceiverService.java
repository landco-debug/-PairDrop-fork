// SPDX-License-Identifier: GPL-3.0-only
package site.landco.pairdrop.fork;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.net.*;
import android.os.*;
import android.webkit.*;
import androidx.webkit.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;

public final class ReceiverService extends Service {
    public final class LocalBinder extends Binder { public ReceiverService getService() { return ReceiverService.this; } }
    public static volatile ReceiverService debugInstance;
    public Consumer<JSONObject> debugEvents;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService disk=Executors.newSingleThreadExecutor();
    private final LocalBinder binder=new LocalBinder();
    private final ArrayDeque<JSONObject> pending=new ArrayDeque<>();
    private final Map<String,JSONObject> active=new LinkedHashMap<>(), requests=new LinkedHashMap<>();
    private WeakReference<MainActivity> activity=new WeakReference<>(null);
    private SharedPreferences prefs;
    private NotificationManager notifications;
    private ReceiveStore store;
    private WebView web;
    private JSONObject preparing;
    private JSONArray choices;
    private volatile boolean ready;
    private boolean chooserVisible,requestVisible,destroyed;
    private int rendererRestarts;
    private ConnectivityManager.NetworkCallback networkCallback;

    @Override public void onCreate() {
        super.onCreate();if(BuildConfig.DEBUG) debugInstance=this;
        prefs=getSharedPreferences("receiver",0);store=new ReceiveStore(this);
        notifications=getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel("receiver","Фоновый приём",NotificationManager.IMPORTANCE_LOW));
        notifications.createNotificationChannel(new NotificationChannel("events","Передачи и буфер обмена",NotificationManager.IMPORTANCE_DEFAULT));
        Intent stop=new Intent(this,ReceiverService.class).setAction("stop");
        Notification ongoing=new Notification.Builder(this,"receiver").setSmallIcon(R.drawable.app_icon).setContentTitle("PairDrop — приём включён")
            .setContentText("Окно приложения можно свернуть").setContentIntent(open()).setOngoing(true)
            .addAction(new Notification.Action.Builder(null,"Остановить",PendingIntent.getService(this,1,stop,PendingIntent.FLAG_IMMUTABLE)).build()).build();
        startForeground(1,ongoing,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        createWebView();
        networkCallback=new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { main.post(()-> { if(ready) web.evaluateJavascript("pairDrop.server._reconnectAttempts=0;pairDrop.server._connect();",null); }); }
        };
        getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(networkCallback);
    }
    private PendingIntent open() { return PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE); }
    private void createWebView() {
        web=new WebView(this);web.getSettings().setJavaScriptEnabled(true);web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setAllowFileAccess(false);web.getSettings().setAllowContentAccess(false);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT,false);
        if(!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) { error("Обновите Android System WebView");stopSelf();return; }
        WebViewCompat.addWebMessageListener(web,"nativePairDrop",Set.of(WebContent.ORIGIN),(view,message,origin,isMainFrame,reply)-> {
            if(!isMainFrame || !WebContent.trusted(origin)) return;
            try {
                JSONObject envelope=new JSONObject(message.getData());
                String kind=envelope.getString("kind");JSONObject data=envelope.getJSONObject("data");long id=envelope.getLong("id");
                if(Set.of("beginFile","appendFile","finishFile").contains(kind)) {
                    disk.execute(()-> { try {
                        Object result=true;
                        if(kind.equals("beginFile")) result=store.begin(data);
                        if(kind.equals("appendFile")) store.append(data);
                        if(kind.equals("finishFile")) result=store.finish(data.getString("id"));
                        Object value=result;main.post(()->reply(reply,id,value,null));
                    } catch(Exception e) { main.post(()->reply(reply,id,null,e.getMessage())); }});
                } else {
                    try { reply(reply,id,handle(kind,data),null); }
                    catch(Exception error) { reply(reply,id,null,error.getMessage()); }
                }
            } catch(Exception error) { error(error.getMessage()); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view,WebResourceRequest request) { return WebContent.response(ReceiverService.this,request.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request) {
                if(WebContent.trusted(request.getUrl())) return false;
                if(request.hasGesture() && request.isForMainFrame() && Set.of("http","https").contains(request.getUrl().getScheme())) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW,request.getUrl()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch(ActivityNotFoundException ignored) {}
                } return true;
            }
            @Override public boolean onRenderProcessGone(WebView view,RenderProcessGoneDetail detail) {
                ready=false;view.destroy();disk.execute(()->store.cancel(null));
                for(JSONObject command:active.values()) error("Соединение перезапущено; повторите незавершённую отправку вручную");
                active.clear();if(preparing!=null) { error("Подготовка отправки прервана");preparing=null;choices=null; }
                if(++rendererRestarts<=3) main.postDelayed(()-> { if(!destroyed) { createWebView();MainActivity a=host();if(a!=null)a.showReceiver(web); } },1000L<<rendererRestarts);
                else error("Приёмник остановлен после ошибок WebView. Откройте приложение заново.");
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params) {
                MainActivity a=host();if(a!=null)return a.openFileChooser(callback);callback.onReceiveValue(null);return true;
            }
        });
        web.loadUrl(WebContent.ORIGIN+"/");
    }
    private void reply(JavaScriptReplyProxy reply,long id,Object data,String error) {
        if(destroyed)return;
        try { JSONObject value=new JSONObject().put("id",id).put("ok",error==null);
            if(error==null)value.put("data",data==null?JSONObject.NULL:data);else value.put("error",error);
            reply.postMessage(value.toString());
        } catch(Exception e) { error(e.getMessage()); }
    }
    private Object handle(String kind,JSONObject data) throws Exception {
        switch(kind) {
            case "ready": ready=true;drain();break;
            case "connection": case "peers": drain();break;
            case "text": {
                String text=data.optString("text");boolean paired=data.optBoolean("paired");
                boolean copied=paired && prefs.getBoolean("autoCopy",true);
                if(copied) copy(text);
                notifyText(copied?"Текст скопирован — можно вставлять":"Получен текст",text,copied);
                debug(new JSONObject().put("event","received").put("text",text).put("copied",copied).put("background",host()==null));break;
            }
            case "prepared": {
                if(preparing==null || !preparing.getString("id").equals(data.optString("commandId")))break;
                JSONObject result=data.getJSONObject("result");
                if(!result.optBoolean("ok")) { error(result.optString("error","Получатель недоступен"));preparing=null;drain();break; }
                choices=result.optJSONArray("peers");if(choices==null || choices.length()==0) { error("Получатель недоступен");preparing=null;drain();break; }
                if(!result.optBoolean("needsSelection")) for(int i=0;i<choices.length();i++) {
                    JSONObject peer=choices.getJSONObject(i);
                    if(peer.optString("stableId").equals(prefs.getString("preferred",""))) { dispatch(peer);return true; }
                }
                showChooser();break;
            }
            case "dispatchResult": {
                if(!data.optBoolean("ok",true)) failed(data.optString("commandId"),data.optString("error","Не удалось отправить"));break;
            }
            case "sent": {
                JSONObject sent=active.remove(data.optString("requestId"));
                if(sent!=null) debug(new JSONObject().put("event","sent").put("text",sent.optString("text")));
                break;
            }
            case "failed": failed(data.optString("requestId"),"Передача отклонена или соединение прервано");break;
            case "request": {
                String id=data.getString("id");requests.put(id,data);
                if(BuildConfig.DEBUG && debugEvents!=null && data.optString("peerId").equals("ci-remote")) respond(id,true);
                else { notifyRequest(id,data);showIncoming(); }break;
            }
            case "requestExpired": { String id=data.optString("id");requests.remove(id);notifications.cancel("request-"+id,7);break; }
            case "peerLeft": {
                String peer=data.optString("peerId");disk.execute(()->store.cancel(peer));break;
            }
            case "show": break;
            case "testEvent": if(!BuildConfig.DEBUG || debugEvents==null) throw new SecurityException("Test only");debug(data);break;
            default: throw new IllegalArgumentException("Unknown native event: "+kind);
        }
        return true;
    }
    private void debug(JSONObject data) { if(BuildConfig.DEBUG && debugEvents!=null) debugEvents.accept(data); }
    void copy(String text) { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("PairDrop",text)); }
    public void enqueueText(String text) {
        if(text==null || text.isEmpty())return;
        try { pending.add(new JSONObject().put("id",UUID.randomUUID().toString()).put("text",text));drain(); }
        catch(JSONException error) { error(error.getMessage()); }
    }
    public boolean hasPreferred() { return prefs.contains("preferred"); }
    private void drain() {
        if(!ready || preparing!=null || pending.isEmpty())return;
        preparing=pending.removeFirst();choices=null;
        try { JSONObject options=new JSONObject().put("preferred",prefs.contains("preferred")?prefs.getString("preferred",null):JSONObject.NULL);
            invoke("prepareNativeSend",options,"prepared",preparing.getString("id"));
        } catch(Exception e) { error(e.getMessage());preparing=null; }
    }
    private void invoke(String method,JSONObject argument,String event,String commandId) {
        String id=JSONObject.quote(commandId), replyEvent=JSONObject.quote(event);
        String script="(async()=>{try{const result=await window.PairDropBridge."+method+"("+argument+");await window.PairDropHost.call("+replyEvent+",{commandId:"+id+",result,ok:true});}catch(error){await window.PairDropHost.call("+replyEvent+",{commandId:"+id+",ok:false,error:error.message,result:{ok:false,error:error.message}});}})();";
        web.evaluateJavascript(script,null);
    }
    private void dispatch(JSONObject peer) throws Exception {
        JSONObject command=preparing;command.put("to",peer.getString("id"));active.put(command.getString("id"),command);
        preparing=null;choices=null;invoke("send",command,"dispatchResult",command.getString("id"));drain();
    }
    private void failed(String id,String message) { if(active.remove(id)!=null)error(message); }
    private MainActivity host() { MainActivity a=activity.get();return a!=null && a.visible && !a.isFinishing() && !a.isDestroyed()?a:null; }
    void attach(MainActivity a) { activity=new WeakReference<>(a);a.showReceiver(web);showChooser();showIncoming(); }
    void detach(MainActivity a) {
        if(activity.get()==a) { activity.clear();if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web); }
    }
    void showChooser() {
        if(choices==null || preparing==null || chooserVisible)return;
        MainActivity a=host();if(a==null) { notification("Выберите получателя","Откройте PairDrop для подтверждения устройства",null);return; }
        try {
            String[] labels=new String[choices.length()];JSONArray inventory=choices;String commandId=preparing.getString("id");
            for(int i=0;i<labels.length;i++) { JSONObject peer=inventory.getJSONObject(i);labels[i]=peer.optString("name")+" — "+peer.optString("deviceName")+(peer.optBoolean("paired")?" • сопряжено":""); }
            chooserVisible=true;
            new AlertDialog.Builder(a).setTitle("Отправить текст устройству")
                .setItems(labels,(dialog,index)-> { try {
                    if(preparing==null || !commandId.equals(preparing.optString("id")))return;
                    JSONObject peer=inventory.getJSONObject(index);
                    if(peer.optBoolean("paired"))prefs.edit().putString("preferred",peer.getString("stableId")).apply();
                    dispatch(peer);
                }catch(Exception e){error(e.getMessage());} })
                .setNegativeButton("Отмена",(dialog,which)-> { preparing=null;choices=null; })
                .setOnCancelListener(dialog->{preparing=null;choices=null;})
                .setOnDismissListener(dialog->{chooserVisible=false;drain();}).show();
        }catch(Exception e){error(e.getMessage());}
    }
    private void showIncoming() {
        MainActivity a=host();if(a==null || requestVisible || requests.isEmpty())return;
        String id=requests.keySet().iterator().next();JSONObject request=requests.get(id);requestVisible=true;
        new AlertDialog.Builder(a).setTitle("Принять файлы?").setMessage(request.optJSONArray("names").toString())
            .setPositiveButton("Принять",(d,w)->respond(id,true)).setNegativeButton("Отклонить",(d,w)->respond(id,false))
            .setOnDismissListener(d->{requestVisible=false;}).show();
    }
    private void respond(String id,boolean accepted) {
        if(!requests.containsKey(id) || !ready)return;
        web.evaluateJavascript("window.PairDropBridge.respondToRequest("+JSONObject.quote(id)+","+accepted+");",null);
    }
    private void notifyRequest(String id,JSONObject data) {
        Notification.Builder builder=base("Запрос файлов",data.optString("sender","PairDrop"));
        for(boolean accept:new boolean[]{true,false}) {
            Intent action=new Intent(this,ReceiverService.class).setAction(accept?"accept":"decline").setData(Uri.parse("pairdrop-action://request/"+id)).putExtra("request",id);
            builder.addAction(new Notification.Action.Builder(null,accept?"Принять":"Отклонить",PendingIntent.getService(this,accept?2:3,action,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
        }
        try { notifications.notify("request-"+id,7,builder.build()); }catch(SecurityException ignored){}
    }
    private Notification.Builder base(String title,String body) { return new Notification.Builder(this,"events").setSmallIcon(R.drawable.app_icon).setContentTitle(title).setContentText(body).setContentIntent(open()).setAutoCancel(true); }
    private void notification(String title,String body,String unused) { try { notifications.notify(UUID.randomUUID().toString(),8,base(title,body).build()); }catch(SecurityException ignored){} }
    private void notifyText(String title,String text,boolean copied) {
        Notification.Builder builder=base(title,text);
        if(!copied) {
            Intent action=new Intent(this,ReceiverService.class).setAction("copy").setData(Uri.parse("pairdrop-action://copy/"+UUID.randomUUID())).putExtra("text",text);
            builder.addAction(new Notification.Action.Builder(null,"Копировать",PendingIntent.getService(this,4,action,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE)).build());
        }
        try { notifications.notify(UUID.randomUUID().toString(),8,builder.build()); }catch(SecurityException ignored){}
    }
    private void error(String text) { notification("PairDrop",text==null?"Не удалось выполнить действие":text,null); }
    @Override public IBinder onBind(Intent intent) { return binder; }
    @Override public int onStartCommand(Intent intent,int flags,int id) {
        if(intent!=null) {
            if("stop".equals(intent.getAction()))stopSelf();
            if("accept".equals(intent.getAction()))respond(intent.getStringExtra("request"),true);
            if("decline".equals(intent.getAction()))respond(intent.getStringExtra("request"),false);
            if("copy".equals(intent.getAction()))copy(intent.getStringExtra("text"));
        }return START_NOT_STICKY;
    }
    public void runDebugScript(String script) { if(!BuildConfig.DEBUG)throw new SecurityException("Test only");web.evaluateJavascript(script,null); }
    public boolean isReadyForTest() { return ready; }
    public void setPreferredForTest(String value) { if(!BuildConfig.DEBUG)throw new SecurityException("Test only");prefs.edit().putString("preferred",value).apply(); }
    @Override public void onDestroy() {
        destroyed=true;ready=false;
        if(networkCallback!=null)getSystemService(ConnectivityManager.class).unregisterNetworkCallback(networkCallback);
        if(web!=null) { if(web.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)web.getParent()).removeView(web);web.destroy(); }
        disk.execute(()->store.cancel(null));disk.shutdown();notifications.cancelAll();if(BuildConfig.DEBUG)debugInstance=null;super.onDestroy();
    }
}
