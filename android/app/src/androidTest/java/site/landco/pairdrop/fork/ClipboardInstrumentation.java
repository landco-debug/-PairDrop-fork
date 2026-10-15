// SPDX-License-Identifier: GPL-3.0-only
package site.landco.pairdrop.fork;

import android.app.*;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.*;

/** Runs real WebRTC + production selected-text Activity + OS clipboard in API 37. */
public final class ClipboardInstrumentation extends Instrumentation {
    private final BlockingQueue<JSONObject> events=new LinkedBlockingQueue<>();
    private ReceiverService service;
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments);start(); }
    private void trace(String stage) { Bundle status=new Bundle();status.putString("stream","CLIPBOARD_STAGE: "+stage+"\n");sendStatus(0,status); }
    private void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private JSONObject await(String event,String text) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        while(System.nanoTime()<deadline) {
            JSONObject value=events.poll(1,TimeUnit.SECONDS);if(value==null)continue;
            if(value.optString("event").equals("failure"))throw new AssertionError(value.toString());
            if(value.optString("event").equals(event) && (text==null || text.equals(value.optString("text"))))return value;
        }throw new AssertionError("Timed out: "+event+" / "+text);
    }
    private Activity open() throws Exception {
        ActivityMonitor monitor=new ActivityMonitor(MainActivity.class.getName(),null,false);
        addMonitor(monitor);
        runOnMainSync(()->getTargetContext().startActivity(new Intent(getTargetContext(),MainActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_MULTIPLE_TASK)));
        Activity activity=monitor.waitForActivityWithTimeout(20000);
        removeMonitor(monitor);
        check(activity!=null,"Clipboard probe Activity did not launch");
        AtomicReference<Boolean> focused=new AtomicReference<>(false);
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<deadline) {
            runOnMainSync(()->focused.set(activity.hasWindowFocus()));
            if(focused.get()) { trace("probe-focused");return activity; }
            Thread.sleep(100);
        }
        throw new AssertionError("Clipboard probe did not receive window focus");
    }
    private String clipboard() throws Exception {
        AtomicReference<String> value=new AtomicReference<>();
        runOnMainSync(()-> { ClipData clip=((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
            value.set(clip==null?null:clip.getItemAt(0).coerceToText(getTargetContext()).toString()); });return value.get();
    }
    private void sendRemote(String text,boolean paired) {
        runOnMainSync(()->service.runDebugScript("window.ClipboardFixture.send("+JSONObject.quote(text)+","+paired+");"));
    }
    private void background(Activity activity) throws Exception {
        runOnMainSync(()->activity.moveTaskToBack(true));
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        AtomicReference<Boolean> hidden=new AtomicReference<>(false);
        while(System.nanoTime()<deadline) {
            runOnMainSync(()->hidden.set(!((MainActivity)activity).visible));
            if(hidden.get())return;
            Thread.sleep(100);
        }
        throw new AssertionError("Activity did not reach onStop before background test");
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            // Test-driver privilege for reopening probe windows; clipboard access stays ordinary.
            getUiAutomation().adoptShellPermissionIdentity("android.permission.START_ACTIVITIES_FROM_BACKGROUND");
            trace("open-main");
            Activity activity=open();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);
            while(System.nanoTime()<deadline) {
                service=ReceiverService.debugInstance;
                if(service!=null && service.isReadyForTest())break;
                Thread.sleep(150);
            }
            check(service!=null && service.isReadyForTest(),"Frontend did not become ready");
            trace("frontend-ready");
            runOnMainSync(()->service.debugEvents=events::offer);
            String fixture;
            try(InputStream in=getContext().getAssets().open("native-fixture.js")) { fixture=new String(in.readAllBytes(),StandardCharsets.UTF_8); }
            String script=fixture;runOnMainSync(()->service.runDebugScript(script));
            JSONObject initialized=await("fixtureReady",null);
            trace("rtc-ready");
            runOnMainSync(()->service.setPreferredForTest(initialized.optString("stableId")));
            runOnMainSync(()->((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("seed","clipboard-seed")));
            background(activity);
            trace("window-stopped");
            String incoming="Mac → Android\nКириллица 👋\n  spaces  \nhttps://example.com/?q=тест";
            sendRemote(incoming,true);JSONObject receipt=await("received",incoming);
            check(receipt.optBoolean("copied"),"Paired incoming text was not copied");
            check(receipt.optBoolean("background"),"Incoming text was tested with a visible Activity");
            trace("background-copy-reported");
            Activity probe=open();
            check(incoming.equals(clipboard()),"OS clipboard does not contain incoming text");
            trace("os-clipboard-verified");
            background(probe);
            sendRemote("unpaired-must-not-copy",false);
            check(!await("received","unpaired-must-not-copy").optBoolean("copied"),"Unpaired peer auto-copied");
            runOnMainSync(()->getTargetContext().getSharedPreferences("receiver",0).edit().putBoolean("autoCopy",false).commit());
            sendRemote("disabled-must-not-copy",true);
            check(!await("received","disabled-must-not-copy").optBoolean("copied"),"Disabled auto-copy ran");
            runOnMainSync(()->getTargetContext().getSharedPreferences("receiver",0).edit().putBoolean("autoCopy",true).commit());
            trace("clipboard-policy-verified");
            String selected="Android → Mac\nВыделение 🙂\n  keep trailing spaces  ";
            Intent menu=new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain").setPackage(getTargetContext().getPackageName());
            check(getTargetContext().getPackageManager().resolveActivity(menu,0)!=null,"Selected-text handler is missing from the system menu registry");
            Intent selection=new Intent(Intent.ACTION_PROCESS_TEXT).setClass(getTargetContext(),SendTextActivity.class).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT,selected).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            open(); // The user invokes a selection action from a visible source application.
            runOnMainSync(()->getTargetContext().startActivity(selection));
            check(selected.equals(await("selectionReceived",selected).getString("text")),"Selected text changed");
            await("sent",selected);
            trace("selection-delivered");
            Activity finalProbe=open();
            check(incoming.equals(clipboard()),"Sending selection or unpaired text replaced local clipboard");
            runOnMainSync(()->service.runDebugScript("window.ClipboardFixture.close();"));
            background(finalProbe);
            runOnMainSync(()->getTargetContext().startService(new Intent(getTargetContext(),ReceiverService.class).setAction("stop")));
            long stopped=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(ReceiverService.debugInstance!=null && System.nanoTime()<stopped)Thread.sleep(100);
            check(ReceiverService.debugInstance==null,"Stop action left a hidden bound receiver running");
            getUiAutomation().dropShellPermissionIdentity();
            result.putString("stream","CLIPBOARD_INTEGRATION_PASS: API="+Build.VERSION.SDK_INT+", real WebRTC, background OS clipboard, PROCESS_TEXT, Unicode, unpaired/disabled policy, no clipboard echo, explicit stop\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            try { getUiAutomation().dropShellPermissionIdentity(); } catch(Exception ignored) {}
            StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));
            result.putString("stream","CLIPBOARD_INTEGRATION_FAIL: "+trace);finish(Activity.RESULT_CANCELED,result);
        }
    }
}
