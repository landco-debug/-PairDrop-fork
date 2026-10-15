// SPDX-License-Identifier: GPL-3.0-only
package site.landco.pairdrop.fork;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.webkit.ValueCallback;
import android.widget.*;
import java.util.ArrayList;

public final class MainActivity extends Activity {
    boolean visible;
    private ReceiverService service;
    private FrameLayout content;
    private TextView status;
    private boolean bound;
    private String pendingText;
    private ValueCallback<Uri[]> fileChooser;
    private final ServiceConnection connection = new ServiceConnection() {
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((ReceiverService.LocalBinder) binder).getService();
            service.attach(MainActivity.this);
            if (pendingText != null) { service.enqueueText(pendingText); pendingText = null; }
        }
        public void onServiceDisconnected(ComponentName name) { service = null; }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        pendingText = state == null ? getIntent().getStringExtra("selectedText") : state.getString("selectedText");
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        LinearLayout bar = new LinearLayout(this);
        status = new TextView(this); status.setText("PairDrop Fork · v4"); status.setPadding(20,16,4,16);
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button menu = new Button(this); menu.setText("Меню"); bar.addView(menu);
        menu.setOnClickListener(v -> {
            PopupMenu popup = new PopupMenu(this, menu);
            boolean auto = getSharedPreferences("receiver",0).getBoolean("autoCopy",true);
            popup.getMenu().add(0,1,0,"Копировать входящий текст").setCheckable(true).setChecked(auto);
            popup.getMenu().add(0,2,1,"Сбросить получателя");
            popup.getMenu().add(0,3,2,"Остановить фоновый приём");
            popup.getMenu().add(0,4,3,"Приём при выключенном экране…");
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId()==1) getSharedPreferences("receiver",0).edit().putBoolean("autoCopy",!auto).apply();
                if (item.getItemId()==2) getSharedPreferences("receiver",0).edit().remove("preferred").apply();
                if (item.getItemId()==3) { stopService(new Intent(this,ReceiverService.class)); finish(); }
                if (item.getItemId()==4) configureScreenOff();
                return true;
            }); popup.show();
        });
        layout.addView(bar); content = new FrameLayout(this);
        layout.addView(content,new LinearLayout.LayoutParams(-1,0,1)); setContentView(layout);
        requestAccess();
    }
    private void configureScreenOff() {
        PowerManager power=getSystemService(PowerManager.class);
        boolean exempt=power.isIgnoringBatteryOptimizations(getPackageName());
        new AlertDialog.Builder(this).setTitle("Приём при выключенном экране")
            .setMessage(exempt?"Системное исключение уже включено. На Samsung также проверьте, что PairDrop Fork отсутствует в списке спящих приложений. Длительный приём расходует батарею.":"Android может отключать сеть в глубоком сне. Чтобы принимать текст без открытого окна, разрешите PairDrop работать без оптимизации батареи. Это увеличивает фоновый расход; его величину нужно измерить на телефоне.")
            .setPositiveButton(exempt?"Настройки приложения":"Разрешить",(d,w)-> {
                Intent settings=new Intent(exempt?android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS:android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,Uri.parse("package:"+getPackageName()));
                try { startActivity(settings); } catch(ActivityNotFoundException error) { startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))); }
            }).setNegativeButton("Закрыть",null).show();
    }
    private void requestAccess() {
        ArrayList<String> missing = new ArrayList<>();
        if (Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            missing.add(Manifest.permission.POST_NOTIFICATIONS);
        if (Build.VERSION.SDK_INT>=37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED)
            missing.add("android.permission.ACCESS_LOCAL_NETWORK");
        if (!missing.isEmpty()) requestPermissions(missing.toArray(new String[0]),7); else connect();
    }
    @Override public void onRequestPermissionsResult(int request,String[] names,int[] results) {
        super.onRequestPermissionsResult(request,names,results);
        if (request==7) {
            if (Build.VERSION.SDK_INT>=37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this).setMessage("Для передачи между Mac и телефоном разрешите доступ к локальной сети в настройках приложения.")
                    .setPositiveButton("Настройки",(d,w)->startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName()))))
                    .setNegativeButton("Закрыть",(d,w)->finish()).show();
            } else connect();
        }
    }
    private void connect() {
        if (bound) return;
        Intent intent = new Intent(this,ReceiverService.class);
        startForegroundService(intent); bound = bindService(intent,connection,BIND_AUTO_CREATE);
    }
    @Override protected void onStart() { super.onStart();visible=true;if(service!=null)service.attach(this); }
    @Override protected void onStop() { visible=false;if(service!=null)service.hideDialogs();super.onStop(); }
    void showReceiver(android.webkit.WebView view) {
        if (view.getParent() instanceof ViewGroup) ((ViewGroup)view.getParent()).removeView(view);
        content.removeAllViews(); content.addView(view,new FrameLayout.LayoutParams(-1,-1));
        status.setText("PairDrop Fork · приём включён");
    }
    boolean openFileChooser(ValueCallback<Uri[]> callback) {
        if (fileChooser!=null) fileChooser.onReceiveValue(null);
        fileChooser=callback;
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); startActivityForResult(intent,19); return true;
    }
    @Override public void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if (request!=19 || fileChooser==null) return;
        ArrayList<Uri> uris=new ArrayList<>();
        if (result==RESULT_OK && data!=null) {
            if (data.getClipData()!=null) for(int i=0;i<data.getClipData().getItemCount();i++) uris.add(data.getClipData().getItemAt(i).getUri());
            else if(data.getData()!=null) uris.add(data.getData());
        }
        fileChooser.onReceiveValue(uris.isEmpty()?null:uris.toArray(new Uri[0])); fileChooser=null;
    }
    @Override protected void onSaveInstanceState(Bundle state) { super.onSaveInstanceState(state); state.putString("selectedText",pendingText); }
    @Override protected void onDestroy() {
        if(service!=null) service.detach(this);
        if(bound) unbindService(connection);
        if(fileChooser!=null) fileChooser.onReceiveValue(null);
        super.onDestroy();
    }
}
