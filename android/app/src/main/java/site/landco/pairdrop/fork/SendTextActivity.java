// SPDX-License-Identifier: GPL-3.0-only
package site.landco.pairdrop.fork;

import android.app.Activity;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.*;
import android.widget.TextView;

/** Receives only the selection supplied by the user's explicit system menu action. */
public final class SendTextActivity extends Activity {
    private boolean bound;
    private String text;
    private final ServiceConnection connection=new ServiceConnection() {
        public void onServiceConnected(ComponentName name,IBinder binder) {
            ReceiverService service=((ReceiverService.LocalBinder)binder).getService();
            service.enqueueText(text);
            if(!service.hasPreferred()) startActivity(new Intent(SendTextActivity.this,MainActivity.class));
            finish();
        }
        public void onServiceDisconnected(ComponentName name) { finish(); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String action=getIntent().getAction();
        if(!Intent.ACTION_PROCESS_TEXT.equals(action) && !Intent.ACTION_SEND.equals(action)) { finish(); return; }
        CharSequence selection=getIntent().getCharSequenceExtra(Intent.ACTION_PROCESS_TEXT.equals(action)?Intent.EXTRA_PROCESS_TEXT:Intent.EXTRA_TEXT);
        if(selection==null || selection.length()==0 || state!=null) { finish(); return; }
        text=selection.toString();
        TextView label=new TextView(this); label.setPadding(30,24,30,24);label.setText("Отправка через PairDrop…");setContentView(label);
    }
    @Override public void onResume() {
        super.onResume(); if(text==null || bound) return;
        if(Build.VERSION.SDK_INT>=37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK")!=PackageManager.PERMISSION_GRANTED) {
            startActivity(new Intent(this,MainActivity.class).putExtra("selectedText",text)); finish(); return;
        }
        Intent intent=new Intent(this,ReceiverService.class); startForegroundService(intent);
        bound=bindService(intent,connection,BIND_AUTO_CREATE);
    }
    @Override protected void onDestroy() { if(bound) unbindService(connection);super.onDestroy(); }
}
