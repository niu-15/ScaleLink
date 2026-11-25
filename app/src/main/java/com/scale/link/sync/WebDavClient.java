package com.scale.link.sync;

import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebDAV 客户端：纯 Java + 原生 Socket，无第三方库。
 *
 * 为什么不用 HttpURLConnection：Android 底层只允许
 * OPTIONS/GET/HEAD/POST/PUT/DELETE/TRACE/PATCH 这几种方法，
 * WebDAV 必需的 MKCOL / PROPFIND 会抛
 * "Expected one of [...] but was MKCOL"，导致坚果云第一步就失败。
 * 这里直接写 HTTP/1.1 报文，方法不受限制。
 *
 * 支持 坚果云 / 群晖 / Nextcloud / 通用 WebDAV。
 */
public class WebDavClient {

    public static final String PRESET_NUTSTORE = "https://dav.jianguoyun.com/dav/";

    private static final String TAG = "WebDav";
    private static final int CONNECT_TIMEOUT = 15000;
    private static final int READ_TIMEOUT = 25000;
    private static final int MAX_REDIRECTS = 5;

    private final String baseUrl;
    private final String user;
    private final String pass;
    private final String auth;

    public WebDavClient(String baseUrl, String user, String pass) {
        String b = baseUrl == null ? "" : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        // 统一保留结尾斜杠：WebDAV 服务端对集合目录缺少斜杠会返回 301
        this.baseUrl = b.isEmpty() ? "" : b + "/";
        this.user = user == null ? "" : user.trim();
        this.pass = pass == null ? "" : pass;
        this.auth = Base64.encodeToString((this.user + ":" + this.pass).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                Base64.NO_WRAP);
    }

    public String getBaseUrl() { return baseUrl; }

    private String urlFor(String path) {
        StringBuilder sb = new StringBuilder(baseUrl);
        for (String seg : path.split("/")) {
            if (seg.isEmpty()) continue;
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '/') sb.append('/');
            sb.append(encode(seg));
        }
        return sb.toString();
    }

    private static String encode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return s;
        }
    }

    // ---------------- HTTP 报文层 ----------------

    private static final class Resp {
        final int code;
        final String reason;
        final Map<String, String> headers;
        final byte[] body;
        Resp(int code, String reason, Map<String, String> headers, byte[] body) {
            this.code = code;
            this.reason = reason;
            this.headers = headers;
            this.body = body;
        }
    }

    private Resp request(String method, String urlStr, byte[] body, String contentType,
                         String extraHeaders) throws IOException {
        String current = urlStr;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL u = new URL(current);
            String scheme = u.getProtocol().toLowerCase(Locale.US);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                throw new IOException("不支持的地址类型：" + scheme);
            }
            String host = u.getHost();
            if (host == null || host.isEmpty()) throw new IOException("地址缺少主机名");
            int port = u.getPort() > 0 ? u.getPort() : (scheme.equals("https") ? 443 : 80);
            String path = u.getPath();
            if (path == null || path.isEmpty()) path = "/";
            if (u.getQuery() != null && !u.getQuery().isEmpty()) path += "?" + u.getQuery();

            Socket plain = new Socket();
            Socket sock = null;
            try {
                plain.connect(new InetSocketAddress(host, port), CONNECT_TIMEOUT);
                plain.setSoTimeout(READ_TIMEOUT);
                if (scheme.equals("https")) {
                    SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                    sock = factory.createSocket(plain, host, port, true);
                    SSLSocket ssl = (SSLSocket) sock;
                    // 校验域名，避免中间人；标准 HTTPS 行为
                    SSLParameters params = ssl.getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                    ssl.setSSLParameters(params);
                    ssl.startHandshake();
                } else {
                    sock = plain;
                }
                sock.setSoTimeout(READ_TIMEOUT);

                StringBuilder req = new StringBuilder(256);
                req.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
                req.append("Host: ").append(host);
                if ((scheme.equals("https") && port != 443) || (scheme.equals("http") && port != 80)) {
                    req.append(':').append(port);
                }
                req.append("\r\n");
                req.append("Authorization: Basic ").append(auth).append("\r\n");
                req.append("User-Agent: ScaleLink/3.2 (Android)\r\n");
                req.append("Accept: */*\r\n");
                req.append("Connection: close\r\n");
                if (extraHeaders != null && !extraHeaders.isEmpty()) req.append(extraHeaders);
                if (body != null) {
                    req.append("Content-Type: ")
                            .append(contentType == null ? "application/octet-stream" : contentType)
                            .append("\r\n");
                    req.append("Content-Length: ").append(body.length).append("\r\n");
                }
                req.append("\r\n");

                OutputStream os = sock.getOutputStream();
                os.write(req.toString().getBytes("US-ASCII"));
                if (body != null && body.length > 0) os.write(body);
                os.flush();

                InputStream in = sock.getInputStream();
                String status = readLine(in);
                if (status == null) throw new IOException("服务器没有返回响应");
                String[] sp = status.trim().split("\\s+", 3);
                int code = 0;
                try { code = Integer.parseInt(sp[1]); } catch (Exception ignored) {}
                String reason = sp.length > 2 ? sp[2] : "";

                Map<String, String> headers = new HashMap<>();
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.isEmpty()) break;
                    int colon = line.indexOf(':');
                    if (colon > 0) {
                        headers.put(line.substring(0, colon).trim().toLowerCase(Locale.US),
                                line.substring(colon + 1).trim());
                    }
                }
                Log.d(TAG, method + " " + path + " -> " + code);

                if (code >= 300 && code < 400 && headers.containsKey("location")) {
                    // WebDAV 的重定向必须保持原方法，不能像浏览器那样改成 GET
                    current = new URL(new URL(current), headers.get("location")).toString();
                    continue;
                }

                byte[] data = readBody(in, headers);
                return new Resp(code, reason, headers, data);
            } finally {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                try { plain.close(); } catch (Exception ignored) {}
            }
        }
        throw new IOException("重定向次数过多，请检查服务器地址");
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') bos.write(b);
        }
        if (b == -1 && bos.size() == 0) return null;
        return new String(bos.toByteArray(), "ISO-8859-1");
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) break;
                int semi = sizeLine.indexOf(';');
                if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
                int size;
                try {
                    size = Integer.parseInt(sizeLine.trim(), 16);
                } catch (Exception e) {
                    throw new IOException("服务器返回了无效的 chunk 长度");
                }
                if (size == 0) {
                    while (true) {
                        String tail = readLine(in);
                        if (tail == null || tail.isEmpty()) break;
                    }
                    break;
                }
                byte[] chunk = new byte[size];
                int off = 0;
                while (off < size) {
                    int n = in.read(chunk, off, size - off);
                    if (n < 0) throw new IOException("响应体提前结束");
                    off += n;
                }
                bos.write(chunk);
                readLine(in); // chunk 后的 CRLF
            }
            return bos.toByteArray();
        }
        String cl = headers.get("content-length");
        if (cl != null) {
            int len;
            try { len = Integer.parseInt(cl.trim()); } catch (Exception e) { len = 0; }
            if (len <= 0) return new byte[0];
            byte[] buf = new byte[len];
            int off = 0;
            while (off < len) {
                int n = in.read(buf, off, len - off);
                if (n < 0) break;
                off += n;
            }
            if (off < len) {
                byte[] trimmed = new byte[off];
                System.arraycopy(buf, 0, trimmed, 0, off);
                return trimmed;
            }
            return buf;
        }
        // 没有 Content-Length：Connection: close 模式下读到 EOF
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private IOException fail(int code, String reason, byte[] body, String action) {
        String hint;
        switch (code) {
            case 0:      hint = "没有收到服务器响应"; break;
            case 401:    hint = "账号或应用密码不正确（坚果云必须用应用密码）"; break;
            case 403:    hint = "没有权限，检查应用密码是否勾选了读写权限"; break;
            case 404:    hint = "路径不存在"; break;
            case 405:    hint = "服务器不支持该操作"; break;
            case 409:    hint = "目录冲突"; break;
            case 507:    hint = "云端空间不足"; break;
            default:     hint = "服务器返回 " + code + (reason.isEmpty() ? "" : " " + reason);
        }
        String msg = action + "失败：" + hint;
        if (body != null && body.length > 0) {
            String text = new String(body, java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ").trim();
            if (!text.isEmpty()) {
                if (text.length() > 120) text = text.substring(0, 120) + "…";
                msg += "（" + text + "）";
            }
        }
        return new IOException(msg);
    }

    // ---------------- WebDAV 操作 ----------------

    /** 测试连通性：PROPFIND 根目录 */
    public void test() throws IOException {
        byte[] body = ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<d:propfind xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:displayname/></d:prop></d:propfind>")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Resp r = request("PROPFIND", urlFor(""), body,
                "application/xml; charset=utf-8", "Depth: 0\r\n");
        if (r.code == 401 || r.code == 403 || (r.code != 207 && r.code != 200)) {
            throw fail(r.code, r.reason, r.body, "连接");
        }
    }

    /** 创建目录（已存在视为成功） */
    public void ensureDir(String dir) throws IOException {
        String path = urlFor(dir);
        if (!path.endsWith("/")) path += "/";
        Resp r = request("MKCOL", path, null, null, null);
        // 201 新建 / 200、204 服务端自定义成功 / 405、409 已存在
        if (r.code == 201 || r.code == 200 || r.code == 204 || r.code == 405 || r.code == 409) return;
        throw fail(r.code, r.reason, r.body, "创建目录");
    }

    /** 上传（覆盖） */
    public void put(String path, byte[] data) throws IOException {
        Resp r = request("PUT", urlFor(path), data,
                "application/json; charset=utf-8", null);
        if (r.code != 200 && r.code != 201 && r.code != 204) {
            throw fail(r.code, r.reason, r.body, "上传");
        }
    }

    /** 下载；文件不存在返回 null */
    public byte[] get(String path) throws IOException {
        Resp r = request("GET", urlFor(path), null, null, null);
        if (r.code == 404) return null;
        if (r.code != 200) throw fail(r.code, r.reason, r.body, "下载");
        return r.body;
    }
}
