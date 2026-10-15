// SPDX-License-Identifier: GPL-3.0-only
package site.landco.pairdrop.fork;

import android.content.*;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.*;
import java.util.*;
import org.json.*;

final class ReceiveStore {
    private record Entry(Uri uri, OutputStream stream, long size, String peer, long[] written) {}
    private final ContentResolver resolver;
    private final Map<String,Entry> entries=new HashMap<>();
    ReceiveStore(Context context) { resolver=context.getContentResolver(); }
    synchronized JSONObject begin(JSONObject data) throws Exception {
        long size=data.optLong("size",-1); if(size<0) throw new IOException("Некорректный размер файла");
        String name=data.optString("name","file").replace('\\','_').replace('/','_').replaceAll("[\\p{Cntrl}]", "_");
        if(name.isEmpty() || name.equals(".") || name.equals("..")) name="file";
        ContentValues values=new ContentValues(); values.put(MediaStore.Downloads.DISPLAY_NAME,name);
        values.put(MediaStore.Downloads.MIME_TYPE,data.optString("mime","application/octet-stream"));
        values.put(MediaStore.Downloads.RELATIVE_PATH,Environment.DIRECTORY_DOWNLOADS+"/PairDrop");
        values.put(MediaStore.Downloads.IS_PENDING,1);
        Uri uri=resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI,values);
        if(uri==null) throw new IOException("Не удалось создать файл");
        try {
            OutputStream stream=resolver.openOutputStream(uri,"w"); if(stream==null) throw new IOException("Не удалось открыть файл");
            String id=UUID.randomUUID().toString(); entries.put(id,new Entry(uri,stream,size,data.optString("peerId"),new long[]{0}));
            return new JSONObject().put("id",id);
        } catch(Exception error) { resolver.delete(uri,null,null);throw error; }
    }
    synchronized void append(JSONObject data) throws Exception {
        Entry entry=entries.get(data.optString("id")); if(entry==null) throw new IOException("Передача завершена или отменена");
        byte[] bytes=Base64.decode(data.getString("data"),Base64.DEFAULT);
        if(bytes.length>65536 || entry.written[0]+bytes.length>entry.size) throw new IOException("Некорректный размер данных");
        entry.stream.write(bytes);entry.written[0]+=bytes.length;
    }
    synchronized JSONObject finish(String id) throws Exception {
        Entry entry=entries.get(id);if(entry==null || entry.written[0]!=entry.size) throw new IOException("Файл получен не полностью");
        entry.stream.flush();entry.stream.close();
        ContentValues values=new ContentValues();values.put(MediaStore.Downloads.IS_PENDING,0);
        if(resolver.update(entry.uri,values,null,null)!=1) throw new IOException("Не удалось завершить сохранение");
        entries.remove(id);return new JSONObject().put("id",id).put("uri",entry.uri.toString());
    }
    synchronized void cancel(String peer) {
        Iterator<Map.Entry<String,Entry>> it=entries.entrySet().iterator();
        while(it.hasNext()) { Entry e=it.next().getValue();if(peer==null || e.peer.equals(peer)) {
            try { e.stream.close(); } catch(IOException ignored) {}
            resolver.delete(e.uri,null,null);it.remove();
        }}
    }
}
