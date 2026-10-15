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
    private void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private JSONObject await(String event,String text) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(35);
        while(System.nanoTime()<deadline) {
            JSONObject value=events.poll(1,TimeUnit.SECONDS);if(value==null)continue;
            if(value.optString("event").equals("failure"))throw new AssertionError(value.toString());
            if(value.optString("event").equals(event) && (text==null || text.equals(value.optString("text"))))return value;
        }throw new AssertionError("Timed out: "+event+" / "+text);
    }
    private Activity open() {
        return startActivitySync(new Intent(getTargetContext(),MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }
    private String clipboard() throws Exception {
        AtomicReference<String> value=new AtomicReference<>();
        runOnMainSync(()-> { ClipData clip=((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip();
            value.set(clip==null?null:clip.getItemAt(0).coerceToText(getTargetContext()).toString()); });return value.get();
    }
    private void sendRemote(String text,boolean paired) {
        runOnMainSync(()->service.runDebugScript("window.ClipboardFixture.send("+JSONObject.quote(text)+","+paired+");"));
    }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            Activity activity=open();waitForIdleSync();
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(75);
            while(System.nanoTime()<deadline) {
                service=ReceiverService.debugInstance;
                if(service!=null && service.isReadyForTest())break;
                Thread.sleep(150);
            }
            check(service!=null && service.isReadyForTest(),"Frontend did not become ready");
            runOnMainSync(()->service.debugEvents=events::offer);
            String fixture;
            try(InputStream in=getContext().getAssets().open("native-fixture.js")) { fixture=new String(in.readAllBytes(),StandardCharsets.UTF_8); }
            String script=fixture;runOnMainSync(()->service.runDebugScript(script));
            JSONObject initialized=await("fixtureReady",null);
            runOnMainSync(()->service.setPreferredForTest(initialized.optString("stableId")));
            runOnMainSync(()->((ClipboardManager)getTargetContext().getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("seed","clipboard-seed")));
            runOnMainSync(()->activity.moveTaskToBack(true));Thread.sleep(700);
            String incoming="Mac → Android\nКириллица 👋\n  spaces  \nhttps://example.com/?q=тест";
            sendRemote(incoming,true);JSONObject receipt=await("received",incoming);
            check(receipt.optBoolean("copied"),"Paired incoming text was not copied");
            check(receipt.optBoolean("background"),"Incoming text was tested with a visible Activity");
            Activity probe=open();waitForIdleSync();Thread.sleep(700);
            check(incoming.equals(clipboard()),"OS clipboard does not contain incoming text");
            runOnMainSync(()->probe.moveTaskToBack(true));Thread.sleep(500);
            sendRemote("unpaired-must-not-copy",false);
            check(!await("received","unpaired-must-not-copy").optBoolean("copied"),"Unpaired peer auto-copied");
            runOnMainSync(()->getTargetContext().getSharedPreferences("receiver",0).edit().putBoolean("autoCopy",false).commit());
            sendRemote("disabled-must-not-copy",true);
            check(!await("received","disabled-must-not-copy").optBoolean("copied"),"Disabled auto-copy ran");
            runOnMainSync(()->getTargetContext().getSharedPreferences("receiver",0).edit().putBoolean("autoCopy",true).commit());
            String selected="Android → Mac\nВыделение 🙂\n  keep trailing spaces  ";
            Intent selection=new Intent(Intent.ACTION_PROCESS_TEXT).setClass(getTargetContext(),SendTextActivity.class).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT,selected).putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY,true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivitySync(selection);
            check(selected.equals(await("selectionReceived",selected).getString("text")),"Selected text changed");
            await("sent",selected);
            Activity finalProbe=open();waitForIdleSync();Thread.sleep(700);
            check(incoming.equals(clipboard()),"Sending selection or unpaired text replaced local clipboard");
            runOnMainSync(()->service.runDebugScript("window.ClipboardFixture.close();"));
            result.putString("stream","CLIPBOARD_INTEGRATION_PASS: API="+Build.VERSION.SDK_INT+", real WebRTC, background OS clipboard, PROCESS_TEXT, Unicode, unpaired/disabled policy, no clipboard echo\n");
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            StringWriter trace=new StringWriter();error.printStackTrace(new PrintWriter(trace));
            result.putString("stream","CLIPBOARD_INTEGRATION_FAIL: "+trace);finish(Activity.RESULT_CANCELED,result);
        }
    }
}
