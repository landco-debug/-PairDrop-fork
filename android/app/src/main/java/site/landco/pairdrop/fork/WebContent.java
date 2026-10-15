// SPDX-License-Identifier: GPL-3.0-only
package site.landco.pairdrop.fork;

import android.content.Context;
import android.net.Uri;
import android.webkit.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Reserved HTTPS origin; no external page or subframe receives the native bridge. */
final class WebContent {
    static final String ORIGIN="https://appassets.androidplatform.net";
    static boolean trusted(Uri url) { return "https".equals(url.getScheme()) && "appassets.androidplatform.net".equals(url.getHost()) && (url.getPort()==-1 || url.getPort()==443); }
    static WebResourceResponse response(Context context,Uri url) {
        if(!trusted(url)) return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Map.of(),new ByteArrayInputStream(new byte[0]));
        String path=url.getPath();
        if(path==null || path.contains("..") || path.contains("\\") || path.indexOf(0)>=0) return null;
        path=path.equals("/")?"index.html":path.substring(1);
        try {
            InputStream stream=context.getAssets().open("web/"+path);
            String type=path.endsWith(".js")?"application/javascript":path.endsWith(".css")?"text/css":path.endsWith(".html")?"text/html":path.endsWith(".json")?"application/json":path.endsWith(".svg")?"image/svg+xml":path.endsWith(".png")?"image/png":"application/octet-stream";
            if(path.equals("index.html")) {
                String html=new String(stream.readAllBytes(),StandardCharsets.UTF_8);stream.close();
                try(InputStream bootstrap=context.getAssets().open("host-bootstrap.js")) {
                    html=html.replace("<head>","<head><script>"+new String(bootstrap.readAllBytes(),StandardCharsets.UTF_8)+"</script>");
                }
                stream=new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8));
            }
            return new WebResourceResponse(type,"UTF-8",200,"OK",Map.of("Cache-Control","no-store","Content-Security-Policy","frame-src 'none'; object-src 'none'; base-uri 'self'","X-Content-Type-Options","nosniff"),stream);
        } catch(IOException error) {
            return new WebResourceResponse("text/plain","UTF-8",404,"Not Found",Map.of(),new ByteArrayInputStream(new byte[0]));
        }
    }
}
