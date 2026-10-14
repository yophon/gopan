package xyz.yophon.gopan;

import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.webkit.MimeTypeMap;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 系统分享接收:MainActivity 把 ACTION_SEND / ACTION_SEND_MULTIPLE 的内容交到这里,
 * 网页端(src/native/shareReceiver.ts)通过 getPending / readChunk / release 取走。
 *
 * 文件不经 bridge 整个传(base64 会把一段视频撑成几百 MB 的字符串),而是先拷进 cacheDir,
 * 网页端按 4MB 一片 readChunk 拼成 Blob 再走既有的分片上传。读完 release 删临时文件。
 */
@CapacitorPlugin(name = "ShareReceiver")
public class ShareReceiverPlugin extends Plugin {

    /** 待处理分享:Activity 在插件加载前就可能收到 intent,所以用静态槽位缓存 */
    private static final Object lock = new Object();
    private static String pendingText = null;
    private static final List<Uri> pendingUris = new ArrayList<>();
    private static ShareReceiverPlugin instance = null;

    /** 已拷到 cache 的文件:id → 路径 */
    private final Map<String, File> staged = new HashMap<>();

    @Override
    public void load() {
        synchronized (lock) {
            instance = this;
        }
    }

    /** MainActivity 调用:收下一个分享 intent,并通知网页端 */
    static void accept(Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_SEND_MULTIPLE.equals(action)) return;
        synchronized (lock) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            CharSequence subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
            StringBuilder sb = new StringBuilder();
            if (subject != null && subject.length() > 0) sb.append(subject);
            if (text != null && text.length() > 0) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(text);
            }
            if (sb.length() > 0) {
                pendingText = pendingText == null ? sb.toString() : pendingText + "\n" + sb;
            }
            if (Intent.ACTION_SEND.equals(action)) {
                Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (uri != null) pendingUris.add(uri);
            } else {
                List<Uri> uris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
                if (uris != null) pendingUris.addAll(uris);
            }
            // 同一个 intent 不要重复消费(singleTask 下 onCreate/onNewIntent 可能都看到它)
            intent.setAction(null);
            if (instance != null) instance.notifyListeners("shareReceived", new JSObject(), true);
        }
    }

    /** 取走全部待处理分享:文本原样返回,文件拷进 cache 后返回元数据与 id */
    @PluginMethod
    public void getPending(PluginCall call) {
        String text;
        List<Uri> uris;
        synchronized (lock) {
            text = pendingText;
            uris = new ArrayList<>(pendingUris);
            pendingText = null;
            pendingUris.clear();
        }
        JSObject out = new JSObject();
        if (text != null) out.put("text", text);
        JSArray files = new JSArray();
        ContentResolver cr = getContext().getContentResolver();
        for (Uri uri : uris) {
            try {
                String id = UUID.randomUUID().toString();
                String name = displayName(cr, uri);
                String type = cr.getType(uri);
                if (type == null) type = guessType(name);
                File dst = new File(getContext().getCacheDir(), "share-" + id);
                long size = copy(cr, uri, dst);
                staged.put(id, dst);
                JSObject f = new JSObject();
                f.put("id", id);
                f.put("name", name);
                f.put("type", type);
                f.put("size", size);
                files.put(f);
            } catch (Exception e) {
                // 单个文件读不出来(权限已过期等)跳过,不拖累其余
            }
        }
        out.put("files", files);
        call.resolve(out);
    }

    /** 读一片:返回 base64,eof 表示已到末尾 */
    @PluginMethod
    public void readChunk(PluginCall call) {
        String id = call.getString("id");
        Integer offset = call.getInt("offset", 0);
        Integer length = call.getInt("length", 4 * 1024 * 1024);
        File f = id == null ? null : staged.get(id);
        if (f == null) {
            call.reject("unknown share id");
            return;
        }
        try (FileInputStream in = new FileInputStream(f)) {
            long skipped = in.skip(offset);
            if (skipped < offset) {
                JSObject out = new JSObject();
                out.put("data", "");
                out.put("eof", true);
                call.resolve(out);
                return;
            }
            byte[] buf = new byte[length];
            int total = 0;
            while (total < length) {
                int n = in.read(buf, total, length - total);
                if (n < 0) break;
                total += n;
            }
            JSObject out = new JSObject();
            out.put("data", Base64.encodeToString(buf, 0, total, Base64.NO_WRAP));
            out.put("eof", offset + total >= f.length());
            call.resolve(out);
        } catch (IOException e) {
            call.reject("read failed: " + e.getMessage());
        }
    }

    /** 网页端拼完 Blob 后删临时文件 */
    @PluginMethod
    public void release(PluginCall call) {
        String id = call.getString("id");
        File f = id == null ? null : staged.remove(id);
        if (f != null) f.delete();
        call.resolve();
    }

    private static String displayName(ContentResolver cr, Uri uri) {
        String name = null;
        try (Cursor c = cr.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {
        }
        if (name == null || name.isEmpty()) {
            name = uri.getLastPathSegment();
        }
        if (name == null || name.isEmpty()) {
            String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(String.valueOf(cr.getType(uri)));
            name = "分享文件" + (ext != null ? "." + ext : "");
        }
        return name;
    }

    private static String guessType(String name) {
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase());
            if (t != null) return t;
        }
        return "application/octet-stream";
    }

    private static long copy(ContentResolver cr, Uri uri, File dst) throws IOException {
        try (InputStream in = cr.openInputStream(uri); OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new IOException("cannot open " + uri);
            byte[] buf = new byte[256 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
            }
            return total;
        }
    }
}
