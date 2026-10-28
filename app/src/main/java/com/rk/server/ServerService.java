package com.rk.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Lightweight RK Chat backend. Designed for Android API 19 and low-memory tablets. */
public class ServerService extends Service {
    public static final String ACTION_START = "com.rk.server.START";
    public static final int PORT = 8080;
    public static volatile boolean isRunning = false;
    public static volatile String lastError = "";

    private static final int MAX_MESSAGES = 5000;
    private static final int MAX_CALL_SIGNALS = 100;
    private static final int MAX_STATUSES = 1000;
    private static final int MAX_HEADER_BYTES = 16384;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final int MAX_IMAGE_BYTES = 180 * 1024;
    private static final int MAX_MEDIA_BYTES = 5 * 1024 * 1024;
    private static final Object DATA_LOCK = new Object();
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final Charset LATIN1 = Charset.forName("ISO-8859-1");

    private volatile boolean stopRequested = false;
    private volatile ServerSocket serverSocket;
    private Thread acceptThread;
    private ThreadPoolExecutor workers;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private JSONArray messages = new JSONArray();
    private JSONArray users = new JSONArray();
    private JSONArray sessions = new JSONArray();
    private JSONArray callSignals = new JSONArray();
    private JSONArray statuses = new JSONArray();
    private JSONArray savedContacts = new JSONArray();
    private File messagesFile, usersFile, sessionsFile, callsFile, statusesFile, contactsFile, imagesDir, profilesDir, featureDataDir;
    private long nextSignalId = System.currentTimeMillis();

    @Override public void onCreate() {
        super.onCreate();
        messagesFile = new File(getFilesDir(), "rk-messages.json");
        usersFile = new File(getFilesDir(), "rk-users.json");
        sessionsFile = new File(getFilesDir(), "rk-sessions.json");
        callsFile = new File(getFilesDir(), "rk-call-signals.json");
        statusesFile = new File(getFilesDir(), "rk-statuses.json");
        contactsFile = new File(getFilesDir(), "rk-contacts.json");
        imagesDir = new File(getFilesDir(), "rk-images");
        profilesDir = new File(getFilesDir(), "rk-profiles");
        featureDataDir = new File(getFilesDir(), "rk-feature-data");
        if (!featureDataDir.exists()) featureDataDir.mkdirs();
        if (!imagesDir.exists()) imagesDir.mkdirs();
        if (!profilesDir.exists()) profilesDir.mkdirs();
        loadAll();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(41, makeNotification());
        if (acceptThread == null || !acceptThread.isAlive()) startServer();
        return START_STICKY;
    }

    private Notification makeNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder builder;
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                NotificationChannel channel = new NotificationChannel("rk_server_status", "Status RK Server", NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("Menunjukkan server lokal sedang berjalan");
                manager.createNotificationChannel(channel);
            }
            builder = new Notification.Builder(this, "rk_server_status");
        } else builder = new Notification.Builder(this);
        builder.setSmallIcon(R.drawable.rk_icon).setContentTitle("RKM Server berjalan")
                .setContentText("RKM Chat, media, status dan panggilan · port " + PORT)
                .setOngoing(true).setContentIntent(pending).setWhen(System.currentTimeMillis());
        return builder.build();
    }

    private void startServer() {
        stopRequested = false; lastError = "";
        workers = new ThreadPoolExecutor(1, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<Runnable>(16), new ThreadPoolExecutor.CallerRunsPolicy());
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) { wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RKServer:Cpu"); wakeLock.acquire(); }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.isWifiEnabled()) { wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "RKServer:Wifi"); wifiLock.acquire(); }
        } catch (Exception ignored) { }
        acceptThread = new Thread(new Runnable() { @Override public void run() {
            try {
                ServerSocket socket = new ServerSocket(); socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress("0.0.0.0", PORT), 24); serverSocket = socket; isRunning = true;
                while (!stopRequested) {
                    try {
                        final Socket client = socket.accept(); client.setSoTimeout(8000);
                        try { workers.execute(new Runnable() { @Override public void run() { handleClient(client); }}); }
                        catch (Exception rejected) { closeQuietly(client); }
                    } catch (SocketException closed) { if (!stopRequested) lastError = "Koneksi server terputus"; break; }
                }
            } catch (Exception e) { if (!stopRequested) lastError = cleanError(e); }
            finally {
                isRunning = false; closeServerSocket(); releaseLocks(); ThreadPoolExecutor pool = workers;
                if (pool != null) pool.shutdownNow(); if (!stopRequested && !TextUtils.isEmpty(lastError)) stopSelf();
            }
        }}, "RKServer-Accept");
        acceptThread.setDaemon(true); acceptThread.start();
    }

    private String cleanError(Exception e) {
        String message = e.getMessage();
        if (e instanceof java.net.BindException) return "Port " + PORT + " sedang dipakai aplikasi lain";
        return TextUtils.isEmpty(message) ? e.getClass().getSimpleName() : message;
    }

    private void handleClient(Socket client) {
        try {
            InputStream input = client.getInputStream(); OutputStream output = client.getOutputStream();
            byte[] headerBytes = readHeaders(input); if (headerBytes == null) return;
            String rawHeaders = new String(headerBytes, LATIN1); String[] lines = rawHeaders.split("\r\n");
            if (lines.length == 0) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String[] request = lines[0].split(" ");
            if (request.length < 2) { sendJson(output, 400, "Bad Request", errorJson("Request tidak valid")); return; }
            String method = request[0].toUpperCase(Locale.US); String rawPath = request[1];
            String query = "", path = rawPath; int qi = rawPath.indexOf('?');
            if (qi >= 0) { query = rawPath.substring(qi + 1); path = rawPath.substring(0, qi); }
            int contentLength = 0;
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0 && "content-length".equals(lines[i].substring(0, colon).trim().toLowerCase(Locale.US))) {
                    try { contentLength = Integer.parseInt(lines[i].substring(colon + 1).trim()); } catch (NumberFormatException ignored) { contentLength = -1; }
                }
            }
            if ("OPTIONS".equals(method)) { sendResponse(output, 204, "No Content", "text/plain; charset=utf-8", new byte[0]); return; }
            if (contentLength < 0) { sendJson(output, 400, "Bad Request", errorJson("Content-Length tidak valid")); return; }
            if (contentLength > MAX_BODY_BYTES) { sendJson(output, 413, "Payload Too Large", errorJson("Data terlalu besar. Foto otomatis diperkecil dari web.")); return; }
            if ("GET".equals(method) && "/".equals(path)) {
                sendResponse(output, 200, "OK", "text/html; charset=utf-8", readAsset("index.html"));
            } else if ("GET".equals(method) && "/health".equals(path)) {
                JSONObject r = new JSONObject(); r.put("ok", true); r.put("service", "RKM Server"); r.put("version", "1.3.0");
                r.put("users", users.length()); r.put("messages", getMessageCount()); sendJson(output, 200, "OK", r.toString());
            } else if ("GET".equals(method) && "/api/me".equals(path)) {
                JSONObject u = userForToken(queryParam(query, "token"));
                if (u == null) { sendJson(output, 401, "Unauthorized", errorJson("Sesi habis. Silakan masuk lagi.")); return; }
                sendJson(output, 200, "OK", userPublic(u).toString());
            } else if ("GET".equals(method) && "/api/contacts".equals(path)) {
                getContacts(output, query);
            } else if ("GET".equals(method) && "/api/users".equals(path)) {
                JSONObject me = userForToken(queryParam(query, "token"));
                if (me == null) { sendJson(output, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
                JSONObject result = new JSONObject(); result.put("ok", true); JSONArray list = new JSONArray();
                synchronized (DATA_LOCK) { for (int i=0;i<users.length();i++) { JSONObject u=users.optJSONObject(i); if (u!=null && !u.optString("phone").equals(me.optString("phone"))) list.put(userPublic(u)); } }
                result.put("users", list); sendJson(output, 200, "OK", result.toString());
            } else if ("GET".equals(method) && "/api/messages".equals(path)) {
                getMessages(output, query);
            } else if ("GET".equals(method) && "/api/calls".equals(path)) {
                getCallSignals(output, query);
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                getStatuses(output, query);
            } else if ("GET".equals(method) && "/api/features".equals(path)) {
                sendFeatureManifest(output);
            } else if ("GET".equals(method) && "/api/contact-card".equals(path)) {
                getContactCard(output, query);
            } else if ("GET".equals(method) && "/api/media".equals(path)) {
                getMedia(output, query);
            } else if (isFeatureGetPath(path)) {
                getFeatureCollection(output, path, query);
            } else if ("GET".equals(method) && "/api/image".equals(path)) {
                getImage(output, query);
            } else if ("GET".equals(method) && "/api/voice".equals(path)) {
                getVoice(output, query);
            } else if ("GET".equals(method) && "/api/avatar".equals(path)) {
                getAvatar(output, query);
            } else if ("POST".equals(method) && (path.startsWith("/api/"))) {
                byte[] body = readBody(input, contentLength);
                if (body == null) { sendJson(output, 400, "Bad Request", errorJson("Isi permintaan tidak lengkap")); return; }
                handlePost(output, path, new String(body, UTF8));
            } else sendJson(output, 404, "Not Found", errorJson("Alamat tidak ditemukan"));
        } catch (Exception e) {
            try { sendJson(client.getOutputStream(), 500, "Internal Server Error", errorJson("Kesalahan server. Coba lagi.")); } catch (Exception ignored) { }
        } finally { closeQuietly(client); }
    }

    private void handlePost(OutputStream output, String path, String body) throws IOException {
        try {
            JSONObject data = new JSONObject(body);
            if ("/api/register".equals(path)) { register(output, data); return; }
            if ("/api/login".equals(path)) { login(output, data); return; }
            if ("/api/profile".equals(path)) { updateProfile(output, data); return; }
            if ("/api/contacts".equals(path)) { updateContact(output, data); return; }
            if ("/api/profile-photo".equals(path)) { updateProfilePhoto(output, data); return; }
            if ("/api/messages".equals(path)) { postMessage(output, data); return; }
            if ("/api/messages/edit".equals(path)) { editMessage(output, data); return; }
            if ("/api/messages/delete".equals(path)) { deleteMessage(output, data); return; }
            if ("/api/images".equals(path)) { postImage(output, data); return; }
            if ("/api/voice".equals(path)) { postVoice(output, data); return; }
            if ("/api/calls".equals(path)) { postCallSignal(output, data); return; }
            if ("/api/status".equals(path)) { postStatus(output, data); return; }
            if (handleFeaturePost(output, path, data)) return;
            sendJson(output, 404, "Not Found", errorJson("API tidak ditemukan"));
        } catch (JSONException e) { sendJson(output, 400, "Bad Request", errorJson("Format data tidak valid")); }
    }

    private void register(OutputStream out, JSONObject d) throws IOException, JSONException {
        String phone = normalizePhone(d.optString("phone", "")); String name = cleanName(d.optString("name", "")); String password = d.optString("password", "");
        if (!validPhone(phone)) { sendJson(out, 400, "Bad Request", errorJson("Nomor HP harus 8–15 angka")); return; }
        if (name.length() < 1) { sendJson(out, 400, "Bad Request", errorJson("Nama wajib diisi")); return; }
        if (password.length() < 6 || password.length() > 100) { sendJson(out, 400, "Bad Request", errorJson("Kata sandi minimal 6 karakter")); return; }
        synchronized (DATA_LOCK) {
            if (findUser(phone) != null) { sendJson(out, 409, "Conflict", errorJson("Nomor ini sudah terdaftar. Silakan masuk.")); return; }
            String salt = UUID.randomUUID().toString(); JSONObject u = new JSONObject();
            u.put("phone", phone); u.put("name", name); u.put("about", "Hai, aku menggunakan RKM Chat"); u.put("salt", salt); u.put("passwordHash", hashPassword(salt, password));
            users.put(u); saveArray(usersFile, users);
            String token = createSession(phone); JSONObject result = userPublic(u); result.put("ok", true); result.put("token", token);
            sendJson(out, 201, "Created", result.toString());
        }
    }

    private void login(OutputStream out, JSONObject d) throws IOException, JSONException {
        String phone = normalizePhone(d.optString("phone", "")); String password = d.optString("password", "");
        synchronized (DATA_LOCK) {
            JSONObject u = findUser(phone);
            if (u == null) { sendJson(out, 401, "Unauthorized", errorJson("Nomor HP atau kata sandi salah")); return; }
            String salt=u.optString("salt", ""), stored=u.optString("passwordHash", "");
            boolean valid;
            if (stored.startsWith("pbkdf2$")) valid=constantTimeEquals(stored, hashPassword(salt,password));
            else valid=constantTimeEquals(stored, legacyHashPassword(salt,password));
            if (!valid) { sendJson(out, 401, "Unauthorized", errorJson("Nomor HP atau kata sandi salah")); return; }
            if (!stored.startsWith("pbkdf2$")) { u.put("passwordHash",hashPassword(salt,password)); saveArray(usersFile,users); }
            String token = createSession(phone); JSONObject result = userPublic(u); result.put("ok", true); result.put("token", token);
            sendJson(out, 200, "OK", result.toString());
        }
    }

    private void updateProfile(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject sessionUser = userForToken(d.optString("token", ""));
        if (sessionUser == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String phone = sessionUser.optString("phone"); String name = cleanName(d.optString("name", sessionUser.optString("name")));
        String about = d.optString("about", sessionUser.optString("about", ""));
        if (name.length()==0 || name.length()>40 || about.length()>120) { sendJson(out, 400, "Bad Request", errorJson("Nama harus 1–40 karakter dan info maksimal 120 karakter")); return; }
        synchronized (DATA_LOCK) { JSONObject u=findUser(phone); if(u==null){sendJson(out,404,"Not Found",errorJson("Akun tidak ditemukan"));return;} u.put("name",name);u.put("about",about);saveArray(usersFile,users);JSONObject r=userPublic(u);r.put("ok",true);sendJson(out,200,"OK",r.toString()); }
    }

    private void getContacts(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me = userForToken(queryParam(query, "token"));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String owner = me.optString("phone"); JSONArray list = new JSONArray();
        synchronized (DATA_LOCK) {
            for (int i = 0; i < savedContacts.length(); i++) {
                JSONObject c = savedContacts.optJSONObject(i); if (c == null || !samePhone(owner, c.optString("owner"))) continue;
                String phone = c.optString("phone"); JSONObject u = findUser(phone); if (u == null) continue;
                JSONObject item = new JSONObject(); item.put("phone", phone); item.put("alias", c.optString("alias", ""));
                item.put("name", u.optString("name", phone)); item.put("about", u.optString("about", "")); list.put(item);
            }
        }
        JSONObject r = new JSONObject(); r.put("ok", true); r.put("contacts", list); sendJson(out, 200, "OK", r.toString());
    }

    private void updateContact(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String owner = me.optString("phone"), phone = normalizePhone(d.optString("phone", ""));
        String action = d.optString("action", "add"), alias = cleanName(d.optString("alias", ""));
        if (!validPhone(phone) || samePhone(owner, phone)) { sendJson(out, 400, "Bad Request", errorJson("Nomor kontak tidak valid")); return; }
        synchronized (DATA_LOCK) {
            JSONArray next = new JSONArray(); boolean found = false;
            for (int i = 0; i < savedContacts.length(); i++) {
                JSONObject c = savedContacts.optJSONObject(i); if (c == null) continue;
                boolean match = samePhone(owner, c.optString("owner")) && samePhone(phone, c.optString("phone"));
                if (match) { found = true; if ("delete".equals(action)) continue; }
                if (!match) next.put(c);
            }
            if ("delete".equals(action)) {
                savedContacts = next; saveArray(contactsFile, savedContacts);
                JSONObject r = new JSONObject(); r.put("ok", true); r.put("deleted", found); sendJson(out, 200, "OK", r.toString()); return;
            }
            JSONObject u = findUser(phone);
            if (u == null) { sendJson(out, 404, "Not Found", errorJson("Nomor itu belum terdaftar di RKM Chat")); return; }
            JSONObject c = new JSONObject(); c.put("owner", owner); c.put("phone", phone); c.put("alias", alias); c.put("time", System.currentTimeMillis());
            next.put(c); savedContacts = next; saveArray(contactsFile, savedContacts);
            JSONObject r = new JSONObject(); r.put("ok", true); r.put("contact", c); sendJson(out, 200, "OK", r.toString());
        }
    }

    private boolean hasSavedContact(String owner, String phone) {
        for (int i = 0; i < savedContacts.length(); i++) {
            JSONObject c = savedContacts.optJSONObject(i);
            if (c != null && samePhone(owner, c.optString("owner")) && samePhone(phone, c.optString("phone"))) return true;
        }
        return false;
    }

    private void postMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String sender = me.optString("phone"); String groupId = d.optString("groupId", "");
        String recipient = normalizePhone(d.optString("recipient", "")); String text = d.optString("text", "").trim();
        if (text.length()==0 || text.length()>2000) { sendJson(out, 400, "Bad Request", errorJson("Pesan harus 1–2000 karakter")); return; }
        JSONObject msg;
        if (groupId.length() > 0) {
            JSONObject group = findGroup(groupId);
            if (group == null || !isGroupMember(group, sender)) { sendJson(out, 403, "Forbidden", errorJson("Kamu bukan anggota grup ini")); return; }
            msg = new JSONObject(); msg.put("id", UUID.randomUUID().toString()); msg.put("sender", sender);
            msg.put("senderName", me.optString("name", sender)); msg.put("recipient", ""); msg.put("recipientName", "");
            msg.put("groupId", groupId); msg.put("text", text); msg.put("type", "text"); msg.put("time", timeNow());
        } else {
            if (!validPhone(recipient) || samePhone(sender, recipient) || findUser(recipient)==null) { sendJson(out, 400, "Bad Request", errorJson("Pilih nomor teman yang sudah terdaftar")); return; }
            if (isBlockedEitherWay(sender, recipient)) { sendJson(out, 403, "Forbidden", errorJson("Pesan tidak dapat dikirim karena akun diblokir")); return; }
            msg = newMessage(sender, recipient, text, "text", "");
        }
        if (d.optBoolean("once", false)) msg.put("once", true);
        if (d.has("replyTo")) msg.put("replyTo", d.optString("replyTo"));
        if (d.has("expiresInSeconds")) msg.put("expiresInSeconds", Math.max(0, d.optLong("expiresInSeconds", 0)));
        synchronized (DATA_LOCK) { appendLimited(messages, msg, MAX_MESSAGES); saveArray(messagesFile, messages); }
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void postImage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String sender=me.optString("phone"), recipient=normalizePhone(d.optString("recipient", "")), encoded=d.optString("data", "");
        if (!validPhone(recipient) || samePhone(sender,recipient) || findUser(recipient)==null) { sendJson(out,400,"Bad Request",errorJson("Pilih teman yang sudah terdaftar")); return; }
        if (isBlockedEitherWay(sender,recipient)) { sendJson(out,403,"Forbidden",errorJson("Pesan tidak dapat dikirim karena akun diblokir")); return; }
        if (encoded.length()==0 || encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)) { sendJson(out,413,"Payload Too Large",errorJson("Foto terlalu besar")); return; }
        byte[] image;
        try { image=Base64.decode(encoded,Base64.DEFAULT); } catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data gambar tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto maksimal sekitar 180 KB setelah dikompres"));return;}
        String id=UUID.randomUUID().toString().replace("-","");File file=new File(imagesDir,id+".jpg");
        FileOutputStream fos=null;try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject msg=newMessage(sender,recipient,"","image",id);
        synchronized(DATA_LOCK){appendLimited(messages,msg,MAX_MESSAGES);saveArray(messagesFile,messages);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void postVoice(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String sender=me.optString("phone"), recipient=normalizePhone(d.optString("recipient", "")), encoded=d.optString("data", "");
        String mime=d.optString("mimeType", "audio/webm");
        if(!mime.equals("audio/webm")&&!mime.equals("audio/ogg")&&!mime.equals("audio/mp4")&&!mime.equals("audio/aac")){mime="audio/webm";}
        if(!validPhone(recipient)||samePhone(sender,recipient)||findUser(recipient)==null){sendJson(out,400,"Bad Request",errorJson("Pilih teman yang sudah terdaftar"));return;}
        if(isBlockedEitherWay(sender,recipient)){sendJson(out,403,"Forbidden",errorJson("Pesan tidak dapat dikirim karena akun diblokir"));return;}
        if(encoded.length()==0||encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Pesan suara terlalu besar"));return;}
        byte[] audio;try{audio=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data pesan suara tidak valid"));return;}
        if(audio.length==0||audio.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Pesan suara maksimal sekitar 180 KB; rekam lebih singkat"));return;}
        String id=UUID.randomUUID().toString().replace("-","");File file=new File(imagesDir,id+".voice");FileOutputStream fos=null;
        try{fos=new FileOutputStream(file);fos.write(audio);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan pesan suara"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject msg=newMessage(sender,recipient,"","voice","");msg.put("voiceId",id);msg.put("mimeType",mime);
        synchronized(DATA_LOCK){appendLimited(messages,msg,MAX_MESSAGES);saveArray(messagesFile,messages);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("message",msg);sendJson(out,200,"OK",r.toString());
    }

    private void getVoice(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=queryParam(query,"id");if(id.length()!=32||!id.matches("[a-fA-F0-9]+")){sendJson(out,400,"Bad Request",errorJson("ID pesan suara tidak valid"));return;}
        boolean authorized=false;String mime="audio/webm";
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&id.equals(m.optString("voiceId"))&&(samePhone(me.optString("phone"),m.optString("sender"))||samePhone(me.optString("phone"),m.optString("recipient")))){authorized=true;mime=m.optString("mimeType","audio/webm");break;}}}
        if(!authorized){sendJson(out,404,"Not Found",errorJson("Pesan suara tidak ditemukan"));return;}
        File f=new File(imagesDir,id+".voice");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Pesan suara tidak ditemukan"));return;}
        ByteArrayOutputStream buf=new ByteArrayOutputStream();FileInputStream in=null;try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        if(!mime.equals("audio/webm")&&!mime.equals("audio/ogg")&&!mime.equals("audio/mp4")&&!mime.equals("audio/aac"))mime="audio/webm";
        sendResponseCached(out,200,"OK",mime,buf.toByteArray(),86400);
    }

    private void editMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id = d.optString("id", ""), text = d.optString("text", "").trim();
        if (id.length() == 0 || text.length() == 0 || text.length() > 2000) { sendJson(out, 400, "Bad Request", errorJson("Pesan edit tidak valid")); return; }
        synchronized (DATA_LOCK) {
            for (int i=0; i<messages.length(); i++) {
                JSONObject m=messages.optJSONObject(i);
                if (m!=null && id.equals(m.optString("id"))) {
                    if (!samePhone(me.optString("phone"), m.optString("sender"))) { sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang dapat mengedit pesan ini")); return; }
                    if (!"text".equals(m.optString("type"))) { sendJson(out,400,"Bad Request",errorJson("Hanya pesan teks yang dapat diedit")); return; }
                    m.put("text",text); m.put("edited",true); m.put("editedAt",timeNow()); saveArray(messagesFile,messages);
                    JSONObject r=new JSONObject();r.put("ok",true);r.put("message",m);sendJson(out,200,"OK",r.toString());return;
                }
            }
        }
        sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));
    }

    private void deleteMessage(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me = userForToken(d.optString("token", ""));
        if (me == null) { sendJson(out, 401, "Unauthorized", errorJson("Sesi tidak valid")); return; }
        String id=d.optString("id", ""); if(id.length()==0){sendJson(out,400,"Bad Request",errorJson("ID pesan kosong"));return;}
        synchronized(DATA_LOCK) {
            JSONArray next=new JSONArray(); boolean found=false;
            for(int i=0;i<messages.length();i++) {
                JSONObject m=messages.optJSONObject(i);
                if(m!=null && id.equals(m.optString("id"))) {
                    found=true;
                    if(!samePhone(me.optString("phone"),m.optString("sender"))) { sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang dapat menghapus pesan untuk semua")); return; }
                    continue;
                }
                if(m!=null) next.put(m);
            }
            if(!found){sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));return;}
            messages=next;saveArray(messagesFile,messages);
        }
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void updateProfilePhoto(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", ""));
        if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String encoded=d.optString("data", ""); if(encoded.length()==0 || encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Foto profil terlalu besar"));return;}
        byte[] image;try{image=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Foto profil tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto profil maksimal 180 KB"));return;}
        File file=new File(profilesDir,me.optString("phone")+".jpg");FileOutputStream fos=null;
        try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto profil"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void getAvatar(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=normalizePhone(queryParam(query,"phone"));if(!validPhone(phone)||findUser(phone)==null){sendJson(out,404,"Not Found",errorJson("Profil tidak ditemukan"));return;}
        File f=new File(profilesDir,phone+".jpg");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Foto profil belum dipasang"));return;}
        ByteArrayOutputStream buf=new ByteArrayOutputStream();FileInputStream in=null;
        try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        sendResponseCached(out,200,"OK","image/jpeg",buf.toByteArray(),60);
    }

    private JSONObject newMessage(String sender, String recipient, String text, String type, String imageId) throws JSONException {
        JSONObject s=findUser(sender), r=findUser(recipient); JSONObject msg=new JSONObject();
        msg.put("id",UUID.randomUUID().toString());msg.put("sender",sender);msg.put("recipient",recipient);
        msg.put("senderName",s==null?sender:s.optString("name",sender));msg.put("recipientName",r==null?recipient:r.optString("name",recipient));
        msg.put("text",text);msg.put("type",type);msg.put("imageId",imageId);msg.put("time",timeNow());return msg;
    }

    private void getMessages(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone"), peer=normalizePhone(queryParam(query,"peer")), groupId=queryParam(query,"groupId"); JSONArray copy=new JSONArray();
        if (groupId.length() > 0) { JSONObject g=findGroup(groupId); if (g==null || !isGroupMember(g,phone)) { sendJson(out,403,"Forbidden",errorJson("Kamu bukan anggota grup ini")); return; } }
        boolean markRead = peer.length() > 0 || groupId.length() > 0;
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;if(m.optString("localRestoreOwner").length()>0&&!samePhone(phone,m.optString("localRestoreOwner")))continue;
            if (groupId.length() > 0) { if (!groupId.equals(m.optString("groupId"))) continue; }
            else { if (m.optString("groupId").length() > 0) continue; String s=m.optString("sender"),r=m.optString("recipient");boolean allowed=(samePhone(s,phone)||samePhone(r,phone));if(!allowed)continue;if(peer.length()>0 && !((samePhone(s,phone)&&samePhone(r,peer))||(samePhone(s,peer)&&samePhone(r,phone))))continue; }
            if (!samePhone(phone,m.optString("sender"))) {
                long now=System.currentTimeMillis();
                if (groupId.length()>0) { JSONArray deliveredTo=m.optJSONArray("deliveredTo");m.put("deliveredTo",addToArray(deliveredTo,phone));m.put("delivered",true);JSONArray readBy=m.optJSONArray("readBy");if(markRead){readBy=addToArray(readBy,phone);m.put("readBy",readBy);m.put("readCount",readBy.length());JSONObject group=findGroup(groupId);JSONArray members=group==null?new JSONArray():group.optJSONArray("members");m.put("read",members!=null&&members.length()>1&&readBy.length()>=members.length()-1);m.put("readAt",now);}}
                else { if (!m.optBoolean("delivered",false)) { m.put("delivered",true); m.put("deliveredAt",now); } if (markRead && !m.optBoolean("read",false)) { m.put("read",true); m.put("readAt",now); } }
            }
            JSONObject responseMessage = new JSONObject(m.toString());
            if (responseMessage.optBoolean("once", false)) {
                boolean alreadyViewed = hasOnceView(phone, responseMessage.optString("id"));
                responseMessage.put("onceAvailable", !alreadyViewed && !samePhone(phone, responseMessage.optString("sender")));
                responseMessage.put("viewedByMe", alreadyViewed);
            }
            copy.put(responseMessage);
        } if (markRead) saveArray(messagesFile,messages); }
        JSONObject result=new JSONObject();result.put("ok",true);result.put("messages",copy);result.put("count",copy.length());sendJson(out,200,"OK",result.toString());
    }

    private boolean hasOnceView(String owner, String messageId) {
        JSONArray a = readFeatureStore("once-views");
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && samePhone(owner, x.optString("owner")) && messageId.equals(x.optString("messageId"))) return true;
        }
        return false;
    }

    private void postCallSignal(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String from=me.optString("phone"), to=normalizePhone(d.optString("to", "")), type=d.optString("type", ""), callId=d.optString("callId", "");
        if(!validPhone(to)||samePhone(from,to)||findUser(to)==null){sendJson(out,400,"Bad Request",errorJson("Nomor teman tidak valid"));return;}
        if(!(type.equals("offer")||type.equals("answer")||type.equals("ice")||type.equals("end")||type.equals("reject")||type.equals("busy"))){sendJson(out,400,"Bad Request",errorJson("Jenis sinyal tidak valid"));return;}
        if(callId.length()==0||callId.length()>80){sendJson(out,400,"Bad Request",errorJson("ID panggilan tidak valid"));return;}
        JSONObject payload=d.optJSONObject("payload"); if(payload==null)payload=new JSONObject();
        JSONObject sig=new JSONObject(); sig.put("id",nextSignalId());sig.put("from",from);sig.put("fromName",me.optString("name",from));sig.put("to",to);sig.put("type",type);sig.put("callId",callId);sig.put("payload",payload);sig.put("time",System.currentTimeMillis());
        synchronized(DATA_LOCK){appendLimited(callSignals,sig,MAX_CALL_SIGNALS);saveArray(callsFile,callSignals);}
        JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r.toString());
    }

    private void getCallSignals(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone");long since=0;try{since=Long.parseLong(queryParam(query,"since"));}catch(Exception ignored){}
        JSONArray resultList=new JSONArray();long now=System.currentTimeMillis();
        synchronized(DATA_LOCK){for(int i=0;i<callSignals.length();i++){JSONObject s=callSignals.optJSONObject(i);if(s==null)continue;long id=s.optLong("id",0);if(samePhone(s.optString("to"),phone)&&id>since&&now-s.optLong("time",now)<120000)resultList.put(s);}}
        JSONObject result=new JSONObject();result.put("ok",true);result.put("signals",resultList);sendJson(out,200,"OK",result.toString());
    }

    private void postStatus(OutputStream out, JSONObject d) throws IOException, JSONException {
        JSONObject me=userForToken(d.optString("token", "")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String text=d.optString("text", "").trim(), encoded=d.optString("data", "");
        if(text.length()>240 || (text.length()==0 && encoded.length()==0)){sendJson(out,400,"Bad Request",errorJson("Isi teks status atau pilih foto; teks maksimal 240 karakter"));return;}
        JSONObject st=new JSONObject(); st.put("id",UUID.randomUUID().toString()); st.put("phone",me.optString("phone")); st.put("name",me.optString("name")); st.put("text",text); st.put("time",System.currentTimeMillis());
        st.put("visibility", d.optString("visibility", privacyString(me.optString("phone"), "statusVisibility", "contacts")));
        JSONArray privAllowed = privacyArray(me.optString("phone"), "allowed");
        JSONArray privExcluded = privacyArray(me.optString("phone"), "excluded");
        if (d.optJSONArray("allowed") != null) st.put("allowed", d.optJSONArray("allowed")); else if (privAllowed.length() > 0) st.put("allowed", privAllowed);
        if (d.optJSONArray("excluded") != null) st.put("excluded", d.optJSONArray("excluded")); else if (privExcluded.length() > 0) st.put("excluded", privExcluded);
        if (d.optBoolean("permanent", false)) st.put("permanent", true);
        String mediaKind=d.optString("mediaType", "image");
        if(encoded.length()>0){
            if(mediaKind.equals("video") || mediaKind.equals("audio") || mediaKind.equals("file")){
                if(encoded.length()>((MAX_MEDIA_BYTES*4L/3L)+4096L)){sendJson(out,413,"Payload Too Large",errorJson("Media status maksimal 5 MB"));return;}
                byte[] media;try{media=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data media status tidak valid"));return;}
                if(media.length==0||media.length>MAX_MEDIA_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Media status maksimal 5 MB"));return;}
                String mediaId=UUID.randomUUID().toString().replace("-","");File file=new File(imagesDir,mediaId+".blob");FileOutputStream fos=null;
                try{fos=new FileOutputStream(file);fos.write(media);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan media status"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
                st.put("mediaId",mediaId);st.put("mediaType",mediaKind);st.put("mediaMime",d.optString("mimeType","application/octet-stream"));st.put("mediaSize",media.length);
            }else{
                if(encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Foto status terlalu besar"));return;}
                byte[] image; try{image=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data foto status tidak valid"));return;}
                if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto status maksimal sekitar 180 KB"));return;}
                String mediaId=UUID.randomUUID().toString().replace("-",""); File file=new File(imagesDir,mediaId+".jpg"); FileOutputStream fos=null;
                try{fos=new FileOutputStream(file);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){if(file.exists())file.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto status"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
                st.put("mediaId",mediaId); st.put("mediaType","image"); st.put("mediaMime","image/jpeg");
            }
        }
        synchronized(DATA_LOCK){appendLimited(statuses,st,MAX_STATUSES);saveArray(statusesFile,statuses);}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("status",st);sendJson(out,200,"OK",r.toString());
    }

    private void getStatuses(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone"); JSONArray list=new JSONArray();long min=System.currentTimeMillis()-24L*60L*60L*1000L;
        synchronized(DATA_LOCK){for(int i=0;i<statuses.length();i++){JSONObject st=statuses.optJSONObject(i);if(st==null||st.optBoolean("restoredArchiveOnly",false))continue;boolean permanent=st.optBoolean("permanent",false);if(!permanent&&st.optLong("time",0)<min)continue;if(canViewStatus(phone,st))list.put(st);}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("statuses",list);sendJson(out,200,"OK",r.toString());
    }

    private void getImage(OutputStream out, String query) throws IOException, JSONException {
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=queryParam(query,"id");if(id.length()<16||id.length()>64||!id.matches("[a-fA-F0-9]+")){sendJson(out,400,"Bad Request",errorJson("ID foto tidak valid"));return;}
        boolean authorized=false;String phone=me.optString("phone");
        synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m!=null&&id.equals(m.optString("imageId"))&&(samePhone(phone,m.optString("sender"))||samePhone(phone,m.optString("recipient")))){authorized=true;break;}}if(!authorized){for(int i=0;i<statuses.length();i++){JSONObject st=statuses.optJSONObject(i);if(st!=null&&id.equals(st.optString("mediaId"))&&canReadStatusMedia(phone,st)){authorized=true;break;}}}}
        if(!authorized){sendJson(out,404,"Not Found",errorJson("Foto tidak ditemukan"));return;}
        File f=new File(imagesDir,id+".jpg");if(!f.exists()||f.length()>MAX_IMAGE_BYTES){sendJson(out,404,"Not Found",errorJson("Foto tidak ditemukan"));return;}
        FileInputStream in=null;ByteArrayOutputStream buf=new ByteArrayOutputStream();try{in=new FileInputStream(f);byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)buf.write(b,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}
        sendResponseCached(out,200,"OK","image/jpeg",buf.toByteArray(),86400);
    }

    // Feature API. All metadata is persisted inside the tablet's private app storage.
    private static final String[] FEATURE_COLLECTIONS = new String[] {
        "settings","privacy","blocked","archived-contacts","favorite-contacts","pinned-contacts","chat-settings",
        "groups","message-reactions","message-stars","message-archives","message-pins","message-receipts","once-views",
        "status-comments","status-views","status-reactions","status-reposts","status-highlights","status-archive",
        "call-history","live-sessions","backup-jobs","sticker-favorites","profile-visitors","presence","forwarded-messages"
    };

    private void sendFeatureManifest(OutputStream out) throws IOException, JSONException {
        JSONObject r = new JSONObject(); JSONArray a = new JSONArray();
        String[] api = new String[] {"groups", "group admin/member management", "message reactions/stars/archive/pins/forward/export/receipts/once-view", "status privacy/delete/views/comments/reactions/repost/archive/highlights", "call history", "live session metadata", "presence", "block list", "profile visit history", "sticker favorites", "settings/privacy", "local backup export/restore", "media upload/download", "chat settings", "contact card/QR payload"};
        for (String x : api) a.put(x); r.put("ok",true); r.put("service","RKM Server"); r.put("version","1.3.0"); r.put("serverFeatures",a);
        r.put("notes","API server-side; UI and external OAuth/WebRTC/TURN still need client/service support."); sendJson(out,200,"OK",r);
    }

    private boolean isFeatureGetPath(String path) {
        return path.equals("/api/settings") || path.equals("/api/privacy") || path.equals("/api/blocked") ||
            path.equals("/api/archived-contacts") || path.equals("/api/favorite-contacts") || path.equals("/api/pinned-contacts") ||
            path.equals("/api/chat-settings") || path.equals("/api/groups") || path.equals("/api/message-reactions") ||
            path.equals("/api/message-stars") || path.equals("/api/message-archives") || path.equals("/api/message-pins") ||
            path.equals("/api/message-receipts") || path.equals("/api/once-views") || path.equals("/api/status/history") ||
            path.equals("/api/status/comments") || path.equals("/api/status/views") || path.equals("/api/status/reactions") ||
            path.equals("/api/status/reposts") || path.equals("/api/status/highlights") || path.equals("/api/status/archive") ||
            path.equals("/api/calls/history") || path.equals("/api/live") || path.equals("/api/backups") ||
            path.equals("/api/stickers/favorites") || path.equals("/api/profile/visitors") || path.equals("/api/presence") ||
            path.equals("/api/forwarded-messages") || path.equals("/api/messages/export") || path.equals("/api/backup/export") ||
            path.equals("/api/chats/export");
    }

    private String collectionForPath(String path) {
        if (path.equals("/api/settings")) return "settings";
        if (path.equals("/api/privacy")) return "privacy";
        if (path.equals("/api/blocked")) return "blocked";
        if (path.equals("/api/archived-contacts")) return "archived-contacts";
        if (path.equals("/api/favorite-contacts")) return "favorite-contacts";
        if (path.equals("/api/pinned-contacts")) return "pinned-contacts";
        if (path.equals("/api/chat-settings")) return "chat-settings";
        if (path.equals("/api/groups")) return "groups";
        if (path.equals("/api/message-reactions")) return "message-reactions";
        if (path.equals("/api/message-stars")) return "message-stars";
        if (path.equals("/api/message-archives")) return "message-archives";
        if (path.equals("/api/message-pins")) return "message-pins";
        if (path.equals("/api/message-receipts")) return "message-receipts";
        if (path.equals("/api/once-views")) return "once-views";
        if (path.equals("/api/status/comments")) return "status-comments";
        if (path.equals("/api/status/views")) return "status-views";
        if (path.equals("/api/status/reactions")) return "status-reactions";
        if (path.equals("/api/status/reposts")) return "status-reposts";
        if (path.equals("/api/status/highlights")) return "status-highlights";
        if (path.equals("/api/status/archive")) return "status-archive";
        if (path.equals("/api/calls/history")) return "call-history";
        if (path.equals("/api/live")) return "live-sessions";
        if (path.equals("/api/backups")) return "backup-jobs";
        if (path.equals("/api/stickers/favorites")) return "sticker-favorites";
        if (path.equals("/api/profile/visitors")) return "profile-visitors";
        if (path.equals("/api/presence")) return "presence";
        return "forwarded-messages";
    }

    private void getFeatureCollection(OutputStream out, String path, String query) throws IOException, JSONException {
        JSONObject me = userForToken(queryParam(query,"token"));
        if (me == null) { sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid")); return; }
        String phone = me.optString("phone"), collection = collectionForPath(path);
        if (path.equals("/api/messages/export") || path.equals("/api/chats/export")) { exportChat(out, query, me); return; }
        if (path.equals("/api/backup/export")) { exportBackup(out, me, query); return; }
        if (path.equals("/api/status/history")) {
            JSONArray outList = new JSONArray(); synchronized(DATA_LOCK) { for(int i=0;i<statuses.length();i++){JSONObject st=statuses.optJSONObject(i);if(st!=null&&samePhone(phone,st.optString("phone")))outList.put(st);} }
            JSONObject r=new JSONObject();r.put("ok",true);r.put("statuses",outList);sendJson(out,200,"OK",r);return;
        }
        JSONArray data = readFeatureStore(collection), list = new JSONArray();
        String id = queryParam(query,"id"), statusId=queryParam(query,"statusId"), groupId=queryParam(query,"groupId"), peer=queryParam(query,"peer");
        if ("groups".equals(collection)) {
            synchronized(DATA_LOCK) { for (int i=0;i<data.length();i++) { JSONObject g=data.optJSONObject(i); if(g!=null&&isGroupMember(g,phone)&&(id.length()==0||id.equals(g.optString("id")))) list.put(g); } }
        } else if ("status-comments".equals(collection) || "status-views".equals(collection) || "status-reactions".equals(collection) || "status-reposts".equals(collection)) {
            if (statusId.length() == 0) { sendJson(out,400,"Bad Request",errorJson("statusId wajib diisi untuk membaca interaksi status")); return; }
            JSONObject st = findStatus(statusId);
            if (st == null) { sendJson(out,404,"Not Found",errorJson("Status tidak ditemukan")); return; }
            boolean ownerCanSee = st != null && samePhone(phone, st.optString("phone"));
            if (st != null && !ownerCanSee && !canViewStatus(phone, st)) { sendJson(out,403,"Forbidden",errorJson("Tidak punya izin melihat interaksi status ini")); return; }
            synchronized(DATA_LOCK) { for(int i=0;i<data.length();i++){JSONObject x=data.optJSONObject(i);if(x==null)continue;if(statusId.length()>0&&!statusId.equals(x.optString("statusId")))continue;if(ownerCanSee || samePhone(phone,x.optString("owner")))list.put(x);} }
        } else if ("profile-visitors".equals(collection)) {
            synchronized(DATA_LOCK) { for(int i=0;i<data.length();i++){JSONObject x=data.optJSONObject(i);if(x!=null&&samePhone(phone,x.optString("profileOwner"))&&System.currentTimeMillis()-x.optLong("time",0)<=30L*24L*60L*60L*1000L)list.put(x);} }
        } else if ("presence".equals(collection)) {
            synchronized(DATA_LOCK) { for(int i=0;i<data.length();i++){JSONObject x=data.optJSONObject(i);if(x!=null&&System.currentTimeMillis()-x.optLong("time",0)<120000&&(samePhone(phone,x.optString("owner"))||hasSavedContact(phone,x.optString("owner"))))list.put(x);} }
        } else if ("live-sessions".equals(collection)) {
            synchronized(DATA_LOCK) { for(int i=0;i<data.length();i++){JSONObject x=data.optJSONObject(i);if(x==null||(id.length()>0&&!id.equals(x.optString("id"))))continue;boolean member=samePhone(phone,x.optString("owner"))||arrayContains(x.optJSONArray("participants"),phone);if(member||(id.length()==0&&"live".equals(x.optString("state"))&&canViewLive(phone,x)))list.put(x);} }
        } else {
            synchronized(DATA_LOCK) { for(int i=0;i<data.length();i++){JSONObject x=data.optJSONObject(i);if(x==null||!samePhone(phone,x.optString("owner")))continue;if(id.length()>0&&!id.equals(x.optString("id")))continue;if(statusId.length()>0&&!statusId.equals(x.optString("statusId")))continue;if(groupId.length()>0&&!groupId.equals(x.optString("groupId")))continue;if(peer.length()>0&&!peer.equals(x.optString("peer"))&&!peer.equals(x.optString("contact")))continue;list.put(x);} }
        }
        JSONObject r=new JSONObject();r.put("ok",true);r.put("collection",collection);r.put("items",list);r.put("count",list.length());
        String field = path.startsWith("/api/status/") ? ("/api/status/highlights".equals(path)?"highlights":"items") : ("/api/groups".equals(path)?"groups":"items");
        r.put(field,list); sendJson(out,200,"OK",r);
    }

    private boolean handleFeaturePost(OutputStream out, String path, JSONObject d) throws IOException, JSONException {
        if (path.equals("/api/media")) { postGenericMedia(out,d); return true; }
        if (path.equals("/api/groups/photo")) { updateGroupPhoto(out,d); return true; }
        if (path.equals("/api/groups")) { createGroup(out,d); return true; }
        if (path.equals("/api/groups/action")) { groupAction(out,d); return true; }
        if (path.equals("/api/messages/action") || path.equals("/api/messages/reaction") || path.equals("/api/messages/star") || path.equals("/api/messages/archive") || path.equals("/api/messages/read") || path.equals("/api/messages/forward") || path.equals("/api/messages/once/view") || path.equals("/api/messages/receipts")) { messageAction(out,d,path); return true; }
        if (path.equals("/api/status/delete") || path.equals("/api/status/privacy") || path.equals("/api/status/view") || path.equals("/api/status/comment") || path.equals("/api/status/react") || path.equals("/api/status/repost") || path.equals("/api/status/highlight") || path.equals("/api/status/archive")) { statusAction(out,d,path); return true; }
        if (path.equals("/api/settings") || path.equals("/api/privacy") || path.equals("/api/chat-settings") || path.equals("/api/stickers/favorites") || path.equals("/api/archived-contacts") || path.equals("/api/favorite-contacts") || path.equals("/api/pinned-contacts") || path.equals("/api/block") || path.equals("/api/presence") || path.equals("/api/calls/history") || path.equals("/api/live") || path.equals("/api/live/action") || path.equals("/api/backup") || path.equals("/api/profile/visit") || path.equals("/api/contacts/qr") || path.equals("/api/forwarded-messages")) { genericFeatureAction(out,d,path); return true; }
        return false;
    }

    private JSONArray readFeatureStore(String collection) {
        if (!isAllowedCollection(collection)) return new JSONArray();
        File f = new File(featureDataDir, collection + ".json");
        return loadArray(f, 10000);
    }
    private boolean isAllowedCollection(String name) { for(String s:FEATURE_COLLECTIONS) if(s.equals(name)) return true; return false; }
    private void saveFeatureStore(String collection, JSONArray a) { if(!isAllowedCollection(collection))return; synchronized(DATA_LOCK) { saveArray(new File(featureDataDir,collection+".json"),a); } }
    private JSONArray getArray(JSONObject d, String key) { JSONArray a=d.optJSONArray(key); return a==null?new JSONArray():a; }
    private boolean arrayContains(JSONArray a, String value) { if(a==null)return false;for(int i=0;i<a.length();i++)if(value.equals(a.optString(i)))return true;return false; }
    private JSONArray addToArray(JSONArray a,String value) throws JSONException { JSONArray n=new JSONArray();boolean exists=false;if(a!=null)for(int i=0;i<a.length();i++){String v=a.optString(i);if(v.equals(value))exists=true;n.put(v);}if(!exists)n.put(value);return n; }
    private JSONArray removeFromArray(JSONArray a,String value) throws JSONException { JSONArray n=new JSONArray();if(a!=null)for(int i=0;i<a.length();i++){String v=a.optString(i);if(!v.equals(value))n.put(v);}return n; }
    private JSONObject featureError(String message) throws JSONException { JSONObject r=new JSONObject();r.put("ok",false);r.put("error",message);return r; }

    private JSONObject findGroup(String id) {
        JSONArray a=readFeatureStore("groups");for(int i=0;i<a.length();i++){JSONObject g=a.optJSONObject(i);if(g!=null&&id.equals(g.optString("id")))return g;}return null;
    }
    private boolean isGroupMember(JSONObject g,String phone) { return g!=null&&(arrayContains(g.optJSONArray("members"),phone)||samePhone(g.optString("owner"),phone)); }
    private boolean isGroupAdmin(JSONObject g,String phone) { return g!=null&&(samePhone(g.optString("owner"),phone)||arrayContains(g.optJSONArray("admins"),phone)); }
    private void updateGroupPhoto(OutputStream out,JSONObject d)throws IOException,JSONException{
        JSONObject me=userForToken(d.optString("token","")); if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=d.optString("groupId",""); JSONObject g=findGroup(id); if(g==null){sendJson(out,404,"Not Found",errorJson("Grup tidak ditemukan"));return;}
        if(!isGroupAdmin(g,me.optString("phone"))){sendJson(out,403,"Forbidden",errorJson("Hanya admin yang dapat mengubah foto grup"));return;}
        String encoded=d.optString("data",""); if(encoded.length()==0||encoded.length()>((MAX_IMAGE_BYTES*4/3)+1000)){sendJson(out,413,"Payload Too Large",errorJson("Foto grup maksimal 180 KB"));return;}
        byte[] image;try{image=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Foto grup tidak valid"));return;}
        if(image.length==0||image.length>MAX_IMAGE_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Foto grup maksimal 180 KB"));return;}
        String mediaId=UUID.randomUUID().toString().replace("-",""); File f=new File(imagesDir,mediaId+".blob"); FileOutputStream fos=null;
        try{fos=new FileOutputStream(f);fos.write(image);fos.flush();fos.getFD().sync();}catch(Exception e){if(f.exists())f.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan foto grup"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONArray all=readFeatureStore("groups"); for(int i=0;i<all.length();i++){JSONObject x=all.optJSONObject(i);if(x!=null&&id.equals(x.optString("id"))){x.put("photoId",mediaId);x.put("updatedAt",System.currentTimeMillis());break;}}
        saveFeatureStore("groups",all); JSONObject r=new JSONObject();r.put("ok",true);r.put("photoId",mediaId);r.put("mediaUrl","/api/media?token="+d.optString("token")+"&id="+mediaId);sendJson(out,200,"OK",r);
    }

    private void createGroup(OutputStream out,JSONObject d) throws IOException,JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String name=d.optString("name","").trim(), info=d.optString("info","");if(name.length()<1||name.length()>60||info.length()>500){sendJson(out,400,"Bad Request",errorJson("Nama grup 1–60 karakter; info maksimal 500 karakter"));return;}
        JSONArray members=new JSONArray(), admins=new JSONArray();String owner=me.optString("phone");members.put(owner);admins.put(owner);
        JSONArray requested=getArray(d,"members");for(int i=0;i<requested.length();i++){String phone=normalizePhone(requested.optString(i));if(validPhone(phone)&&findUser(phone)!=null&&!samePhone(phone,owner)&&!arrayContains(members,phone))members.put(phone);}
        JSONObject g=new JSONObject();g.put("id",UUID.randomUUID().toString());g.put("owner",owner);g.put("name",name);g.put("info",info);g.put("members",members);g.put("admins",admins);g.put("createdAt",System.currentTimeMillis());g.put("updatedAt",System.currentTimeMillis());g.put("photoId","");g.put("pinned",false);g.put("favorite",false);g.put("muted",false);
        JSONArray all=readFeatureStore("groups");all.put(g);saveFeatureStore("groups",all);JSONObject r=new JSONObject();r.put("ok",true);r.put("group",g);sendJson(out,201,"Created",r);
    }
    private void groupAction(OutputStream out,JSONObject d) throws IOException,JSONException {
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String id=d.optString("groupId",d.optString("id","")), action=d.optString("action",""), phone=normalizePhone(d.optString("phone",""));JSONArray all=readFeatureStore("groups");
        synchronized(DATA_LOCK){for(int i=0;i<all.length();i++){JSONObject g=all.optJSONObject(i);if(g==null||!id.equals(g.optString("id")))continue;String actor=me.optString("phone");if(!isGroupMember(g,actor)){sendJson(out,403,"Forbidden",errorJson("Kamu bukan anggota grup"));return;}
            boolean admin=isGroupAdmin(g,actor);
            if(action.equals("leave")){if(samePhone(g.optString("owner"),actor)){sendJson(out,400,"Bad Request",errorJson("Pembuat grup harus pindahkan kepemilikan sebelum keluar"));return;}g.put("members",removeFromArray(g.optJSONArray("members"),actor));g.put("admins",removeFromArray(g.optJSONArray("admins"),actor));}
            else if(action.equals("transferOwner")){if(!samePhone(g.optString("owner"),actor)){sendJson(out,403,"Forbidden",errorJson("Hanya pemilik grup yang dapat memindahkan kepemilikan"));return;}if(!isGroupMember(g,phone)){sendJson(out,400,"Bad Request",errorJson("Pemilik baru harus anggota grup"));return;}g.put("owner",phone);g.put("admins",addToArray(g.optJSONArray("admins"),actor));g.put("admins",addToArray(g.optJSONArray("admins"),phone));}
            else if(action.equals("delete")){if(!samePhone(g.optString("owner"),actor)){sendJson(out,403,"Forbidden",errorJson("Hanya pembuat grup yang dapat menghapus grup"));return;}all=removeObjectAt(all,i);saveFeatureStore("groups",all);JSONObject rr=new JSONObject();rr.put("ok",true);rr.put("deleted",true);sendJson(out,200,"OK",rr);return;}
            else if(action.equals("rename")||action.equals("edit")){if(!admin){sendJson(out,403,"Forbidden",errorJson("Hanya admin grup yang boleh mengubah info"));return;}String name=d.optString("name",g.optString("name")),info=d.optString("info",g.optString("info"));if(name.trim().length()<1||name.length()>60||info.length()>500){sendJson(out,400,"Bad Request",errorJson("Nama/info grup tidak valid"));return;}g.put("name",name.trim());g.put("info",info);if(d.has("photoId"))g.put("photoId",d.optString("photoId"));}
            else if(action.equals("addMember")){if(!admin){sendJson(out,403,"Forbidden",errorJson("Hanya admin yang dapat menambahkan anggota"));return;}if(!validPhone(phone)||findUser(phone)==null){sendJson(out,404,"Not Found",errorJson("Akun calon anggota tidak ditemukan"));return;}g.put("members",addToArray(g.optJSONArray("members"),phone));}
            else if(action.equals("removeMember")){if(!admin){sendJson(out,403,"Forbidden",errorJson("Hanya admin yang dapat mengeluarkan anggota"));return;}if(!arrayContains(g.optJSONArray("members"),phone)){sendJson(out,404,"Not Found",errorJson("Anggota tidak ditemukan"));return;}if(samePhone(g.optString("owner"),phone)){sendJson(out,400,"Bad Request",errorJson("Pemilik grup tidak dapat dikeluarkan; pindahkan kepemilikan lebih dulu"));return;}g.put("members",removeFromArray(g.optJSONArray("members"),phone));g.put("admins",removeFromArray(g.optJSONArray("admins"),phone));}
            else if(action.equals("promoteAdmin")){if(!admin){sendJson(out,403,"Forbidden",errorJson("Hanya admin yang dapat menunjuk admin"));return;}if(!isGroupMember(g,phone)){sendJson(out,400,"Bad Request",errorJson("Calon admin harus menjadi anggota grup"));return;}g.put("admins",addToArray(g.optJSONArray("admins"),phone));}
            else if(action.equals("demoteAdmin")){if(!samePhone(g.optString("owner"),actor)){sendJson(out,403,"Forbidden",errorJson("Hanya pembuat grup yang dapat menurunkan admin lain"));return;}if(samePhone(g.optString("owner"),phone)){sendJson(out,400,"Bad Request",errorJson("Pembuat grup tidak dapat menurunkan dirinya sendiri"));return;}g.put("admins",removeFromArray(g.optJSONArray("admins"),phone));}
            else if(action.equals("pin")||action.equals("favorite")||action.equals("mute")){String field=action;JSONArray personal=readFeatureStore("chat-settings");JSONObject pref=new JSONObject();pref.put("id",id+":"+actor);pref.put("owner",actor);pref.put("groupId",id);pref.put(field,d.optBoolean("value",true));pref.put("time",System.currentTimeMillis());upsertFeature(personal,pref,"groupId",id,actor);saveFeatureStore("chat-settings",personal);}
            else {sendJson(out,400,"Bad Request",errorJson("Aksi grup tidak dikenal"));return;}
            g.put("updatedAt",System.currentTimeMillis());saveFeatureStore("groups",all);JSONObject r=new JSONObject();r.put("ok",true);r.put("group",g);sendJson(out,200,"OK",r);return;
        }}
        sendJson(out,404,"Not Found",errorJson("Grup tidak ditemukan"));
    }
    private JSONArray removeObjectAt(JSONArray src,int index)throws JSONException{JSONArray n=new JSONArray();for(int i=0;i<src.length();i++)if(i!=index)n.put(src.get(i));return n;}

    private boolean isBlockedEitherWay(String a,String b) {
        JSONArray blocks=readFeatureStore("blocked");for(int i=0;i<blocks.length();i++){JSONObject x=blocks.optJSONObject(i);if(x!=null&&((samePhone(a,x.optString("owner"))&&samePhone(b,x.optString("phone")))||(samePhone(b,x.optString("owner"))&&samePhone(a,x.optString("phone")))))return true;}return false;
    }
    private JSONObject findStatus(String id){if(id==null||id.length()==0)return null;synchronized(DATA_LOCK){for(int i=0;i<statuses.length();i++){JSONObject x=statuses.optJSONObject(i);if(x!=null&&id.equals(x.optString("id")))return x;}}return null;}
    private boolean canViewStatus(String viewer,JSONObject st){
        if(st==null)return false;String owner=st.optString("phone");if(samePhone(viewer,owner))return true;if(isBlockedEitherWay(owner,viewer))return false;
        String visibility=st.optString("visibility","");if(visibility.length()==0){JSONArray p=readFeatureStore("privacy");for(int i=0;i<p.length();i++){JSONObject x=p.optJSONObject(i);if(x!=null&&samePhone(owner,x.optString("owner"))&&"statusVisibility".equals(x.optString("key"))){visibility=x.optString("value","contacts");break;}}}
        if(visibility.length()==0)visibility="contacts";
        if("everyone".equals(visibility))return true;
        JSONArray allowed = st.optJSONArray("allowed"), excluded = st.optJSONArray("excluded");
        if (allowed == null || excluded == null) { if (allowed == null) allowed = privacyArray(owner, "allowed"); if (excluded == null) excluded = privacyArray(owner, "excluded"); }
        if("only".equals(visibility))return arrayContains(allowed,viewer);
        if("except".equals(visibility))return hasSavedContact(owner,viewer)&&!arrayContains(excluded,viewer);
        return hasSavedContact(owner,viewer);
    }
    private boolean canReadStatusMedia(String viewer,JSONObject st){
        if(st==null)return false;
        if(samePhone(viewer,st.optString("phone")))return true;
        if(st.optBoolean("restoredArchiveOnly",false))return false;
        long expiresAt=st.optLong("time",0)+24L*60L*60L*1000L;
        if(!st.optBoolean("permanent",false)&&System.currentTimeMillis()>expiresAt)return false;
        return canViewStatus(viewer,st);
    }
    private void statusAction(OutputStream out,JSONObject d,String path)throws IOException,JSONException{
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String phone=me.optString("phone"),id=d.optString("statusId",d.optString("id","")),action=path.substring(path.lastIndexOf('/')+1);
        if(action.equals("privacy")){String vis=d.optString("visibility","contacts");if(!vis.equals("contacts")&&!vis.equals("everyone")&&!vis.equals("except")&&!vis.equals("only")){sendJson(out,400,"Bad Request",errorJson("Pilihan privasi status tidak valid"));return;}JSONArray a=readFeatureStore("privacy");JSONObject x=new JSONObject();x.put("owner",phone);x.put("key","statusVisibility");x.put("value",vis);x.put("excluded",getArray(d,"excluded"));x.put("allowed",getArray(d,"allowed"));x.put("updatedAt",System.currentTimeMillis());upsertFeature(a,x,"key","statusVisibility",phone);saveFeatureStore("privacy",a);synchronized(DATA_LOCK){for(int si=0;si<statuses.length();si++){JSONObject existing=statuses.optJSONObject(si);if(existing!=null&&samePhone(phone,existing.optString("phone"))){existing.put("visibility",vis);existing.put("excluded",getArray(d,"excluded"));existing.put("allowed",getArray(d,"allowed"));}}saveArray(statusesFile,statuses);}JSONObject r=new JSONObject();r.put("ok",true);r.put("privacy",x);sendJson(out,200,"OK",r);return;}
        JSONObject st=findStatus(id);
        if(st==null && !action.equals("privacy")){sendJson(out,404,"Not Found",errorJson("Status tidak ditemukan"));return;}
        if(action.equals("delete")){if(!samePhone(phone,st.optString("phone"))){sendJson(out,403,"Forbidden",errorJson("Hanya pemilik status yang dapat menghapusnya"));return;}synchronized(DATA_LOCK){JSONArray n=new JSONArray();for(int i=0;i<statuses.length();i++){JSONObject x=statuses.optJSONObject(i);if(x!=null&&!id.equals(x.optString("id")))n.put(x);}statuses=n;saveArray(statusesFile,statuses);}JSONObject r=new JSONObject();r.put("ok",true);r.put("deleted",true);sendJson(out,200,"OK",r);return;}
        if((action.equals("archive")||action.equals("highlight"))&&!samePhone(phone,st.optString("phone"))){sendJson(out,403,"Forbidden",errorJson("Hanya pemilik status yang dapat mengarsipkan atau membuat sorotan"));return;}
        if(!canViewStatus(phone,st)){sendJson(out,403,"Forbidden",errorJson("Status ini bukan untuk kamu"));return;}
        if(action.equals("view")){JSONArray a=readFeatureStore("status-views");JSONObject x=null;for(int i=0;i<a.length();i++){JSONObject z=a.optJSONObject(i);if(z!=null&&samePhone(phone,z.optString("owner"))&&id.equals(z.optString("statusId"))){x=z;break;}}long now=System.currentTimeMillis();if(x==null){x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",phone);x.put("statusId",id);x.put("statusOwner",st.optString("phone"));x.put("firstViewAt",now);x.put("viewCount",1);a.put(x);}else{x.put("viewCount",x.optInt("viewCount",1)+1);x.put("lastViewAt",now);}x.put("time",now);saveFeatureStore("status-views",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("view",x);sendJson(out,200,"OK",r);return;}
        if(action.equals("comment")){String text=d.optString("text","").trim();if(text.length()<1||text.length()>500){sendJson(out,400,"Bad Request",errorJson("Komentar harus 1–500 karakter"));return;}JSONArray a=readFeatureStore("status-comments");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",phone);x.put("name",me.optString("name"));x.put("statusId",id);x.put("statusOwner",st.optString("phone"));x.put("text",text);x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("status-comments",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("comment",x);sendJson(out,201,"Created",r);return;}
        if(action.equals("react")){JSONArray a=readFeatureStore("status-reactions");String emoji=d.optString("emoji","❤️");if(emoji.length()>16)emoji="❤️";JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",phone);x.put("statusId",id);x.put("statusOwner",st.optString("phone"));x.put("emoji",emoji);x.put("time",System.currentTimeMillis());upsertFeature(a,x,"statusId",id,phone);saveFeatureStore("status-reactions",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("reaction",x);sendJson(out,200,"OK",r);return;}
        if(action.equals("repost")){JSONArray a=readFeatureStore("status-reposts");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",phone);x.put("statusId",id);x.put("statusOwner",st.optString("phone"));x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("status-reposts",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("repost",x);sendJson(out,201,"Created",r);return;}
        if(action.equals("highlight")){JSONArray a=readFeatureStore("status-highlights");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",phone);x.put("statusId",id);x.put("name",d.optString("name","Sorotan"));x.put("thumbnailId",d.optString("thumbnailId",st.optString("mediaId","")));x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("status-highlights",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("highlight",x);sendJson(out,201,"Created",r);return;}
        if(action.equals("archive")){JSONArray a=readFeatureStore("status-archive");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",phone);x.put("statusId",id);x.put("status",st);x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("status-archive",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("archived",true);sendJson(out,200,"OK",r);return;}
        sendJson(out,400,"Bad Request",errorJson("Aksi status tidak dikenal"));
    }

    private void upsertFeature(JSONArray a,JSONObject item,String key,String value,String owner)throws JSONException{
        JSONArray n=new JSONArray();for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x==null)continue;if(samePhone(owner,x.optString("owner"))&&value.equals(x.optString(key)))continue;n.put(x);}n.put(item);// Caller still owns the loaded array; replace its contents only by returning the same object is not available in Android JSON.
        // This helper mutates the provided array by rebuilding into the same indexed slots where possible.
        while(a.length()>0) a.remove(a.length()-1);for(int i=0;i<n.length();i++)a.put(n.opt(i));
    }

    private void messageAction(OutputStream out,JSONObject d,String path)throws IOException,JSONException{
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String owner=me.optString("phone"),id=d.optString("id",d.optString("messageId","")),action=d.optString("action","");if(action.length()==0){if(path.endsWith("/reaction"))action="react";else if(path.endsWith("/star"))action="star";else if(path.endsWith("/archive"))action="archive";else if(path.endsWith("/read"))action="read";else if(path.endsWith("/forward"))action="forward";else if(path.endsWith("/once/view"))action="onceView";else if(path.endsWith("/receipts"))action="receipt";}
        JSONObject msg=null;int idx=-1;synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject x=messages.optJSONObject(i);if(x!=null&&id.equals(x.optString("id"))){msg=x;idx=i;break;}}}
        if(action.equals("forward")){if(msg==null){sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));return;}String to=normalizePhone(d.optString("recipient",""));if(!validPhone(to)||findUser(to)==null||samePhone(to,owner)||isBlockedEitherWay(owner,to)){sendJson(out,400,"Bad Request",errorJson("Penerima teruskan tidak valid"));return;}JSONObject f=new JSONObject(msg.toString());f.put("id",UUID.randomUUID().toString());f.put("sender",owner);f.put("recipient",to);f.put("senderName",me.optString("name"));f.put("time",timeNow());f.put("forwarded",true);f.remove("groupId");synchronized(DATA_LOCK){appendLimited(messages,f,MAX_MESSAGES);saveArray(messagesFile,messages);}JSONObject rr=new JSONObject();rr.put("ok",true);rr.put("message",f);sendJson(out,201,"Created",rr);return;}
        if(msg==null&&!action.equals("receipt")){sendJson(out,404,"Not Found",errorJson("Pesan tidak ditemukan"));return;}
        boolean participates=msg!=null&&(samePhone(owner,msg.optString("sender"))||samePhone(owner,msg.optString("recipient"))||(msg.optString("groupId").length()>0&&isGroupMember(findGroup(msg.optString("groupId")),owner)));if(msg!=null&&!participates){sendJson(out,403,"Forbidden",errorJson("Tidak punya akses ke pesan ini"));return;}
        if(action.equals("edit")){if(!samePhone(owner,msg.optString("sender"))){sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang bisa mengedit"));return;}String text=d.optString("text","").trim();if(text.length()==0||text.length()>2000){sendJson(out,400,"Bad Request",errorJson("Teks edit tidak valid"));return;}msg.put("text",text);msg.put("edited",true);msg.put("editedAt",System.currentTimeMillis());saveArray(messagesFile,messages);}
        else if(action.equals("delete")){if(!samePhone(owner,msg.optString("sender"))){sendJson(out,403,"Forbidden",errorJson("Hanya pengirim yang bisa menghapus untuk semua"));return;}synchronized(DATA_LOCK){messages=removeObjectAt(messages,idx);saveArray(messagesFile,messages);}JSONObject rr=new JSONObject();rr.put("ok",true);rr.put("deleted",true);sendJson(out,200,"OK",rr);return;}
        else if(action.equals("read")){long now=System.currentTimeMillis();if(msg.optString("groupId").length()>0){JSONArray readBy=addToArray(msg.optJSONArray("readBy"),owner);msg.put("readBy",readBy);msg.put("readCount",readBy.length());JSONObject g=findGroup(msg.optString("groupId"));JSONArray members=g==null?new JSONArray():g.optJSONArray("members");msg.put("read",members!=null&&members.length()>1&&readBy.length()>=members.length()-1);msg.put("readAt",now);msg.put("deliveredTo",addToArray(msg.optJSONArray("deliveredTo"),owner));}else{msg.put("read",true);msg.put("readAt",now);msg.put("delivered",true);}saveArray(messagesFile,messages);}
        else if(action.equals("receipt")){String to=normalizePhone(d.optString("recipient",msg==null?"":msg.optString("sender")));JSONArray receipts=readFeatureStore("message-receipts");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("messageId",id);x.put("peer",to);x.put("state",d.optString("state","delivered"));x.put("time",System.currentTimeMillis());receipts.put(x);saveFeatureStore("message-receipts",receipts);}
        else if(action.equals("onceView")){if(!msg.optBoolean("once",false)){sendJson(out,400,"Bad Request",errorJson("Pesan ini bukan pesan sekali lihat"));return;}if(samePhone(owner,msg.optString("sender"))){sendJson(out,403,"Forbidden",errorJson("Pengirim tidak dapat membuka salinan sekali lihat sebagai penerima"));return;}if(hasOnceView(owner,id)){sendJson(out,409,"Conflict",errorJson("Pesan sekali lihat sudah dibuka di akun ini"));return;}JSONArray a=readFeatureStore("once-views");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("messageId",id);x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("once-views",a);}
        else if(action.equals("react")||action.equals("star")||action.equals("archive")||action.equals("pin")){String coll=action.equals("react")?"message-reactions":action.equals("star")?"message-stars":action.equals("archive")?"message-archives":"message-pins";JSONArray a=readFeatureStore(coll);JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("messageId",id);x.put("value",d.optString(action.equals("react")?"emoji":"value",action.equals("react")?"❤️":"true"));x.put("time",System.currentTimeMillis());String val=x.optString("value");upsertFeature(a,x,"messageId",id,owner);saveFeatureStore(coll,a);}
        else{sendJson(out,400,"Bad Request",errorJson("Aksi pesan tidak dikenal"));return;}
        JSONObject rr=new JSONObject();rr.put("ok",true);rr.put("message",msg==null?JSONObject.NULL:msg);if(action.equals("onceView"))rr.put("viewAllowed",true);sendJson(out,200,"OK",rr);
    }

    private void genericFeatureAction(OutputStream out,JSONObject d,String path)throws IOException,JSONException{
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String owner=me.optString("phone"),action=d.optString("action","set"),collection=collectionForPath(path);
        if(path.equals("/api/block")){collection="blocked";action=d.optString("action","block");}
        if(path.equals("/api/profile/visit")){collection="profile-visitors";String target=normalizePhone(d.optString("phone",d.optString("profileOwner","")));if(!validPhone(target)||samePhone(owner,target)){sendJson(out,400,"Bad Request",errorJson("Profil tujuan tidak valid"));return;}JSONObject targetUser=findUser(target);if(targetUser==null){sendJson(out,404,"Not Found",errorJson("Profil tidak ditemukan"));return;}if(isPrivacyEnabled(target,"profileVisits",true)&&isPrivacyEnabled(owner,"profileVisits",false)){JSONArray a=readFeatureStore(collection);JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("visitor",owner);x.put("visitorName",me.optString("name"));x.put("profileOwner",target);x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore(collection,a);}JSONObject r=new JSONObject();r.put("ok",true);r.put("visible",isPrivacyEnabled(owner,"profileVisits",false)&&isPrivacyEnabled(target,"profileVisits",true));sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/contacts/qr")){JSONObject card=new JSONObject();card.put("phone",owner);card.put("name",me.optString("name"));card.put("about",me.optString("about",""));card.put("payload","RKM1:"+owner);JSONObject r=new JSONObject();r.put("ok",true);r.put("card",card);sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/presence")){JSONArray a=readFeatureStore("presence");JSONObject x=new JSONObject();x.put("id",owner);x.put("owner",owner);x.put("online",d.optBoolean("online",true));x.put("time",System.currentTimeMillis());upsertFeature(a,x,"owner",owner,owner);saveFeatureStore("presence",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("presence",x);sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/calls/history")){JSONArray a=readFeatureStore(collection);JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("peer",normalizePhone(d.optString("peer","")));x.put("direction",d.optString("direction","outgoing"));x.put("media",d.optString("media","audio"));x.put("result",d.optString("result","completed"));x.put("duration",Math.max(0,d.optLong("duration",0)));x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore(collection,a);JSONObject r=new JSONObject();r.put("ok",true);r.put("call",x);sendJson(out,201,"Created",r);return;}
        if(path.equals("/api/live")||path.equals("/api/live/action")){if(path.endsWith("/action"))collection="live-sessions";JSONArray a=readFeatureStore("live-sessions");String liveId=d.optString("id",d.optString("liveId",""));JSONObject x=null;for(int i=0;i<a.length();i++){JSONObject z=a.optJSONObject(i);if(z!=null&&liveId.equals(z.optString("id"))){x=z;break;}}
            if(action.equals("start")||path.equals("/api/live")){x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("hostName",me.optString("name"));x.put("title",d.optString("title","Live"));x.put("visibility",d.optString("visibility","contacts"));x.put("state","live");x.put("participants",new JSONArray().put(owner));x.put("viewerCount",0);x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("live-sessions",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("live",x);sendJson(out,201,"Created",r);return;}
            if(x==null){sendJson(out,404,"Not Found",errorJson("Sesi live tidak ditemukan"));return;}
            if("comment".equals(action)){if(!canViewLive(owner,x)){sendJson(out,403,"Forbidden",errorJson("Live tidak tersedia"));return;}String commentText=d.optString("text","").trim();if(commentText.length()==0||commentText.length()>500){sendJson(out,400,"Bad Request",errorJson("Komentar live harus 1–500 karakter"));return;}JSONArray cs=x.optJSONArray("comments");if(cs==null)cs=new JSONArray();JSONObject c=new JSONObject();c.put("owner",owner);c.put("name",me.optString("name"));c.put("text",commentText);c.put("time",System.currentTimeMillis());cs.put(c);x.put("comments",cs);}
            else if("request".equals(action)){if(!canViewLive(owner,x)){sendJson(out,403,"Forbidden",errorJson("Live tidak tersedia"));return;}JSONArray rq=x.optJSONArray("joinRequests");if(rq==null)rq=new JSONArray();JSONObject req=new JSONObject();req.put("phone",owner);req.put("name",me.optString("name"));req.put("time",System.currentTimeMillis());rq.put(req);x.put("joinRequests",rq);}
            else if("accept".equals(action)||"decline".equals(action)){if(!samePhone(owner,x.optString("owner"))){sendJson(out,403,"Forbidden",errorJson("Hanya host yang dapat menyetujui permintaan"));return;}String target=normalizePhone(d.optString("phone",""));if(!validPhone(target)){sendJson(out,400,"Bad Request",errorJson("Nomor pemohon tidak valid"));return;}JSONArray rq=x.optJSONArray("joinRequests"),filtered=new JSONArray();if(rq!=null)for(int qi=0;qi<rq.length();qi++){JSONObject req=rq.optJSONObject(qi);if(req!=null&&!samePhone(target,req.optString("phone")))filtered.put(req);}x.put("joinRequests",filtered);if("accept".equals(action))x.put("participants",addToArray(x.optJSONArray("participants"),target));}
            else if("join".equals(action)){if(!canViewLive(owner,x)){sendJson(out,403,"Forbidden",errorJson("Live tidak tersedia"));return;}x.put("participants",addToArray(x.optJSONArray("participants"),owner));x.put("viewerCount",Math.max(0,x.optInt("viewerCount",0)+1));}
            else if("leave".equals(action)){x.put("participants",removeFromArray(x.optJSONArray("participants"),owner));x.put("viewerCount",Math.max(0,x.optInt("viewerCount",0)-1));}
            else if("pause".equals(action)||"resume".equals(action)||"end".equals(action)){if(!samePhone(owner,x.optString("owner"))){sendJson(out,403,"Forbidden",errorJson("Hanya host yang dapat mengendalikan siaran"));return;}x.put("state",action.equals("pause")?"paused":action.equals("resume")?"live":"ended");if(action.equals("end"))x.put("endedAt",System.currentTimeMillis());}
            else {sendJson(out,400,"Bad Request",errorJson("Aksi live tidak dikenal"));return;}saveFeatureStore("live-sessions",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("live",x);sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/backup")){String baction=d.optString("action","export");if("restore".equals(baction)){restoreBackup(out,d,me);return;}JSONArray a=readFeatureStore("backup-jobs");JSONObject x=new JSONObject();x.put("id",UUID.randomUUID().toString());x.put("owner",owner);x.put("action",baction);x.put("provider",d.optString("provider","local"));x.put("schedule",d.optString("schedule","manual"));x.put("status","local-metadata-only");x.put("time",System.currentTimeMillis());a.put(x);saveFeatureStore("backup-jobs",a);JSONObject r=new JSONObject();r.put("ok",true);r.put("backup",x);r.put("warning","Server menyimpan data di tablet. Jadwal bisa dicatat di sini, tetapi upload otomatis Google Drive memerlukan OAuth dan scheduler dari aplikasi klien.");if("export".equals(baction)){r.put("data",buildBackup(me));}sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/stickers/favorites")){String stickerId=d.optString("stickerId",d.optString("id",""));if(stickerId.length()==0){sendJson(out,400,"Bad Request",errorJson("ID stiker kosong"));return;}JSONArray a=readFeatureStore(collection);JSONObject x=new JSONObject();x.put("id",stickerId);x.put("owner",owner);x.put("stickerId",stickerId);x.put("sticker",d.optJSONObject("sticker")==null?new JSONObject():d.optJSONObject("sticker"));x.put("time",System.currentTimeMillis());if("remove".equals(action)||"delete".equals(action)){JSONArray n=new JSONArray();for(int i=0;i<a.length();i++){JSONObject z=a.optJSONObject(i);if(z!=null&&!(samePhone(owner,z.optString("owner"))&&stickerId.equals(z.optString("stickerId"))))n.put(z);}saveFeatureStore(collection,n);}else{upsertFeature(a,x,"stickerId",stickerId,owner);saveFeatureStore(collection,a);}JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/block")){String target=normalizePhone(d.optString("phone",""));if(!validPhone(target)||samePhone(owner,target)){sendJson(out,400,"Bad Request",errorJson("Nomor yang diblokir tidak valid"));return;}JSONArray a=readFeatureStore(collection);if(action.equals("unblock")||action.equals("delete")){JSONArray n=new JSONArray();for(int i=0;i<a.length();i++){JSONObject z=a.optJSONObject(i);if(z!=null&&!(samePhone(owner,z.optString("owner"))&&samePhone(target,z.optString("phone"))))n.put(z);}saveFeatureStore(collection,n);}else{JSONObject x=new JSONObject();x.put("id",target);x.put("owner",owner);x.put("phone",target);x.put("time",System.currentTimeMillis());upsertFeature(a,x,"phone",target,owner);saveFeatureStore(collection,a);}JSONObject r=new JSONObject();r.put("ok",true);r.put("action",action);sendJson(out,200,"OK",r);return;}
        if(path.equals("/api/backup")) return;
        String key=d.optString("key","");if(key.length()==0)key=d.optString("contact",d.optString("peer",d.optString("phone","")));
        JSONArray a=readFeatureStore(collection);
        if(path.equals("/api/archived-contacts")||path.equals("/api/favorite-contacts")||path.equals("/api/pinned-contacts")||path.equals("/api/chat-settings")){
            String contact=normalizePhone(d.optString("contact",d.optString("phone",d.optString("peer",""))));if(!validPhone(contact)||samePhone(contact,owner)){sendJson(out,400,"Bad Request",errorJson("Kontak tidak valid"));return;}if(!"/api/chat-settings".equals(path)&&("delete".equals(action)||"remove".equals(action)||d.has("value")&&!d.optBoolean("value",true))){JSONArray n=new JSONArray();for(int i=0;i<a.length();i++){JSONObject z=a.optJSONObject(i);if(z!=null&&!(samePhone(owner,z.optString("owner"))&&samePhone(contact,z.optString("contact"))))n.put(z);}saveFeatureStore(collection,n);}else{JSONObject x=new JSONObject();x.put("id",contact);x.put("owner",owner);x.put("contact",contact);x.put("value",d.opt("value"));x.put("muted",d.optBoolean("muted",false));x.put("archived",d.optBoolean("archived",false));x.put("pinned",d.optBoolean("pinned",false));x.put("favorite",d.optBoolean("favorite",false));x.put("time",System.currentTimeMillis());upsertFeature(a,x,"contact",contact,owner);saveFeatureStore(collection,a);}JSONObject r=new JSONObject();r.put("ok",true);sendJson(out,200,"OK",r);return;}
        if(!isAllowedCollection(collection)){sendJson(out,400,"Bad Request",errorJson("Jenis data tidak didukung"));return;}
        JSONObject item=new JSONObject();item.put("id",d.optString("id",UUID.randomUUID().toString()));item.put("owner",owner);item.put("time",System.currentTimeMillis());String[] keys={"key","value","enabled","fontScale","theme","lastSeen","onlineVisibility","photoVisibility","statusVisibility","statusExcluded","readReceipts","profileVisits","temporaryMessages","once","schedule","provider","peer","contact","text","mediaId","emoji","messageId","statusId","groupId","name","title","type","target","payload"};for(String k:keys)if(d.has(k))item.put(k,d.opt(k));
        if(path.equals("/api/privacy")||path.equals("/api/settings")){if(key.length()==0){sendJson(out,400,"Bad Request",errorJson("Kunci pengaturan wajib"));return;}item.put("key",key);upsertFeature(a,item,"key",key,owner);}else a.put(item);saveFeatureStore(collection,a);JSONObject r=new JSONObject();r.put("ok",true);r.put("item",item);sendJson(out,201,"Created",r);
    }

    private boolean isPrivacyEnabled(String phone,String key,boolean fallback){JSONArray a=readFeatureStore("privacy");for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&samePhone(phone,x.optString("owner"))&&key.equals(x.optString("key")))return x.optBoolean("value",fallback);}return fallback;}
    private String privacyString(String phone,String key,String fallback){JSONArray a=readFeatureStore("privacy");for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&samePhone(phone,x.optString("owner"))&&key.equals(x.optString("key")))return x.optString("value",fallback);}return fallback;}
    private JSONArray privacyArray(String phone,String field){JSONArray a=readFeatureStore("privacy");for(int i=0;i<a.length();i++){JSONObject x=a.optJSONObject(i);if(x!=null&&samePhone(phone,x.optString("owner"))&&"statusVisibility".equals(x.optString("key"))){JSONArray value=x.optJSONArray(field);return value==null?new JSONArray():value;}}return new JSONArray();}
    private boolean canViewLive(String phone,JSONObject x){String visibility=x.optString("visibility","contacts");return samePhone(phone,x.optString("owner"))||"everyone".equals(visibility)||("contacts".equals(visibility)&&hasSavedContact(x.optString("owner"),phone));}

    private void postGenericMedia(OutputStream out,JSONObject d)throws IOException,JSONException{
        JSONObject me=userForToken(d.optString("token",""));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}
        String owner=me.optString("phone"),recipient=normalizePhone(d.optString("recipient","")),groupId=d.optString("groupId",""),kind=d.optString("kind",d.optString("type","file")),mime=d.optString("mimeType","application/octet-stream"),encoded=d.optString("data","");
        if(groupId.length()>0){JSONObject g=findGroup(groupId);if(g==null||!isGroupMember(g,owner)){sendJson(out,403,"Forbidden",errorJson("Bukan anggota grup"));return;}}
        else if(!validPhone(recipient)||samePhone(owner,recipient)||findUser(recipient)==null||isBlockedEitherWay(owner,recipient)){sendJson(out,400,"Bad Request",errorJson("Penerima media tidak valid"));return;}
        if (kind.equals("location") || kind.equals("contact")) {
            if (kind.equals("location") && (!d.has("latitude") || !d.has("longitude") || Math.abs(d.optDouble("latitude")) > 90 || Math.abs(d.optDouble("longitude")) > 180)) { sendJson(out,400,"Bad Request",errorJson("Koordinat lokasi tidak valid")); return; }
            JSONObject special=new JSONObject();special.put("id",UUID.randomUUID().toString());special.put("sender",owner);special.put("senderName",me.optString("name"));special.put("recipient",recipient);special.put("groupId",groupId);special.put("type",kind);special.put("text",d.optString("caption",d.optString("text","")));special.put("time",timeNow());
            if(kind.equals("location")){special.put("latitude",d.optDouble("latitude"));special.put("longitude",d.optDouble("longitude"));special.put("locationName",d.optString("locationName","Lokasi"));special.put("liveLocationSeconds",Math.max(0,d.optLong("liveLocationSeconds",0)));}
            else {special.put("contactCard",d.optJSONObject("contactCard")==null?new JSONObject():d.optJSONObject("contactCard"));}
            if(d.optBoolean("once",false))special.put("once",true);if(d.has("replyTo"))special.put("replyTo",d.optString("replyTo"));synchronized(DATA_LOCK){appendLimited(messages,special,MAX_MESSAGES);saveArray(messagesFile,messages);}JSONObject outJson=new JSONObject();outJson.put("ok",true);outJson.put("message",special);sendJson(out,201,"Created",outJson);return;
        }
        if(encoded.length()==0||encoded.length()>((MAX_MEDIA_BYTES*4L/3L)+4096L)){sendJson(out,413,"Payload Too Large",errorJson("Media maksimal 5 MB. Kompres video sebelum dikirim."));return;}
        byte[] bytes;try{bytes=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){sendJson(out,400,"Bad Request",errorJson("Data media tidak valid"));return;}if(bytes.length==0||bytes.length>MAX_MEDIA_BYTES){sendJson(out,413,"Payload Too Large",errorJson("Media maksimal 5 MB"));return;}
        String id=UUID.randomUUID().toString().replace("-","");File f=new File(imagesDir,id+".blob");FileOutputStream fos=null;try{fos=new FileOutputStream(f);fos.write(bytes);fos.flush();fos.getFD().sync();}catch(Exception e){if(f.exists())f.delete();sendJson(out,500,"Internal Server Error",errorJson("Gagal menyimpan media"));return;}finally{if(fos!=null)try{fos.close();}catch(Exception ignored){}}
        JSONObject m=new JSONObject();m.put("id",UUID.randomUUID().toString());m.put("sender",owner);m.put("senderName",me.optString("name"));m.put("recipient",recipient);m.put("groupId",groupId);m.put("text",d.optString("caption",d.optString("text","")));m.put("type",kind);m.put("mediaKind",kind);m.put("mediaId",id);m.put("mimeType",mime);m.put("size",bytes.length);m.put("time",timeNow());if(d.optBoolean("once",false))m.put("once",true);if(d.has("latitude"))m.put("latitude",d.optDouble("latitude"));if(d.has("longitude"))m.put("longitude",d.optDouble("longitude"));if(d.has("locationName"))m.put("locationName",d.optString("locationName"));
        synchronized(DATA_LOCK){appendLimited(messages,m,MAX_MESSAGES);saveArray(messagesFile,messages);}JSONObject r=new JSONObject();r.put("ok",true);r.put("message",m);r.put("mediaUrl","/api/media?token="+d.optString("token")+"&id="+id);sendJson(out,201,"Created",r);
    }
    private void getMedia(OutputStream out,String query)throws IOException,JSONException{
        JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String id=queryParam(query,"id"),phone=me.optString("phone");if(id.length()!=32||!id.matches("[a-fA-F0-9]+")){sendJson(out,400,"Bad Request",errorJson("ID media tidak valid"));return;}
        boolean ok=false;String mime="application/octet-stream";synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null||!(id.equals(m.optString("mediaId"))||id.equals(m.optString("imageId"))||id.equals(m.optString("voiceId"))))continue;if(samePhone(phone,m.optString("sender"))||samePhone(phone,m.optString("recipient"))||(m.optString("groupId").length()>0&&isGroupMember(findGroup(m.optString("groupId")),phone))){ok=true;mime=m.optString("mimeType",m.optString("mediaMime","application/octet-stream"));break;}}if(!ok){for(int i=0;i<statuses.length();i++){JSONObject st=statuses.optJSONObject(i);if(st!=null&&id.equals(st.optString("mediaId"))&&canReadStatusMedia(phone,st)){ok=true;mime=st.optString("mediaMime","application/octet-stream");break;}}}if(!ok){JSONArray groups=readFeatureStore("groups");for(int i=0;i<groups.length();i++){JSONObject g=groups.optJSONObject(i);if(g!=null&&id.equals(g.optString("photoId"))&&isGroupMember(g,phone)){ok=true;mime="image/jpeg";break;}}}}
        if(!ok){sendJson(out,404,"Not Found",errorJson("Media tidak ditemukan"));return;}File f=new File(imagesDir,id+".blob");if(!f.exists())f=new File(imagesDir,id+".jpg");if(!f.exists())f=new File(imagesDir,id+".voice");if(!f.exists()||f.length()>MAX_MEDIA_BYTES){sendJson(out,404,"Not Found",errorJson("Media tidak ditemukan"));return;}if("application/octet-stream".equals(mime)){if(f.getName().endsWith(".jpg"))mime="image/jpeg";else if(f.getName().endsWith(".voice"))mime="audio/webm";}ByteArrayOutputStream b=new ByteArrayOutputStream();FileInputStream in=null;try{in=new FileInputStream(f);byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1)b.write(chunk,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}if(mime.length()>100||!mime.matches("[A-Za-z0-9.+-]+/[A-Za-z0-9.+-]+"))mime="application/octet-stream";sendResponseCached(out,200,"OK",mime,b.toByteArray(),86400);
    }

    private void restoreBackup(OutputStream out,JSONObject d,JSONObject me)throws IOException,JSONException{
        JSONObject backup=d.optJSONObject("backup");if(backup==null)backup=d.optJSONObject("data");if(backup==null){sendJson(out,400,"Bad Request",errorJson("Objek backup JSON wajib disertakan"));return;}String owner=me.optString("phone");int cCount=0,mCount=0,sCount=0,gCount=0,fileCount=0;
        JSONArray savedGroups=readFeatureStore("groups"),importGroups=backup.optJSONArray("groups");if(importGroups!=null){for(int i=0;i<importGroups.length();i++){JSONObject g=importGroups.optJSONObject(i);if(g==null||!isGroupMember(g,owner))continue;boolean exists=false;for(int j=0;j<savedGroups.length();j++){JSONObject old=savedGroups.optJSONObject(j);if(old!=null&&g.optString("id").equals(old.optString("id"))){exists=true;break;}}if(!exists){savedGroups.put(g);gCount++;}}saveFeatureStore("groups",savedGroups);}
        JSONArray importedContacts=backup.optJSONArray("contacts");if(importedContacts!=null){for(int i=0;i<importedContacts.length();i++){JSONObject c=importedContacts.optJSONObject(i);if(c==null||!samePhone(owner,c.optString("owner")))continue;String cp=normalizePhone(c.optString("phone"));if(!validPhone(cp)||samePhone(cp,owner))continue;boolean exists=false;for(int j=0;j<savedContacts.length();j++){JSONObject old=savedContacts.optJSONObject(j);if(old!=null&&samePhone(owner,old.optString("owner"))&&samePhone(cp,old.optString("phone"))){exists=true;break;}}if(!exists){savedContacts.put(c);cCount++;}}saveArray(contactsFile,savedContacts);}
        JSONArray importedMessages=backup.optJSONArray("messages");if(importedMessages!=null){for(int i=0;i<importedMessages.length();i++){JSONObject m=importedMessages.optJSONObject(i);if(m==null)continue;String groupId=m.optString("groupId");boolean owns=samePhone(owner,m.optString("sender"))||samePhone(owner,m.optString("recipient"));if(groupId.length()>0)owns=isGroupMember(findGroup(groupId),owner);if(!owns)continue;String id=m.optString("id");if(id.length()==0)continue;boolean exists=false;for(int j=0;j<messages.length();j++){JSONObject old=messages.optJSONObject(j);if(old!=null&&id.equals(old.optString("id"))){exists=true;break;}}if(!exists){m.put("localRestoreOwner",owner);messages.put(m);mCount++;}}saveArray(messagesFile,messages);}
        JSONArray importedStatuses=backup.optJSONArray("statuses");if(importedStatuses!=null){for(int i=0;i<importedStatuses.length();i++){JSONObject st=importedStatuses.optJSONObject(i);if(st==null||!samePhone(owner,st.optString("phone")))continue;String id=st.optString("id");boolean exists=false;for(int j=0;j<statuses.length();j++){JSONObject old=statuses.optJSONObject(j);if(old!=null&&id.equals(old.optString("id"))){exists=true;break;}}if(!exists){st.put("restoredArchiveOnly",true);statuses.put(st);sCount++;}}saveArray(statusesFile,statuses);}
        for(String collection:new String[]{"settings","privacy","blocked","archived-contacts","favorite-contacts","pinned-contacts","chat-settings","message-stars","message-archives","message-pins","status-archive","status-highlights","sticker-favorites","call-history","message-reactions","message-receipts","once-views","status-comments","status-views","status-reactions","status-reposts","profile-visitors"}){JSONArray src=backup.optJSONArray(collection);if(src==null)continue;JSONArray target=readFeatureStore(collection);for(int i=0;i<src.length();i++){JSONObject item=src.optJSONObject(i);if(item==null||!samePhone(owner,item.optString("owner")))continue;String id=item.optString("id");boolean exists=false;for(int j=0;j<target.length();j++){JSONObject old=target.optJSONObject(j);if(old!=null&&id.length()>0&&id.equals(old.optString("id"))){exists=true;break;}}if(!exists)target.put(item);}saveFeatureStore(collection,target);}
        JSONArray media=backup.optJSONArray("mediaFiles");if(media!=null){for(int i=0;i<media.length();i++){JSONObject f=media.optJSONObject(i);if(f==null)continue;String id=f.optString("id"),ext=f.optString("extension",".blob"),encoded=f.optString("dataBase64","");if(!id.matches("[a-fA-F0-9]{32}")||!(ext.equals(".blob")||ext.equals(".jpg")||ext.equals(".voice"))||encoded.length()==0||encoded.length()>((MAX_MEDIA_BYTES*4L/3L)+4096L))continue;byte[] bytes;try{bytes=Base64.decode(encoded,Base64.DEFAULT);}catch(Exception e){continue;}if(bytes.length==0||bytes.length>MAX_MEDIA_BYTES)continue;File dest=new File(imagesDir,id+ext);FileOutputStream fo=null;try{fo=new FileOutputStream(dest);fo.write(bytes);fo.flush();fo.getFD().sync();fileCount++;}finally{if(fo!=null)try{fo.close();}catch(Exception ignored){}}}}
        JSONObject r=new JSONObject();r.put("ok",true);r.put("restored",true);r.put("contacts",cCount);r.put("messages",mCount);r.put("statuses",sCount);r.put("groups",gCount);r.put("mediaFiles",fileCount);r.put("note","Pesan dan status hasil pemulihan ditandai arsip lokal agar tidak terkirim ulang atau muncul sebagai status baru ke kontak lain.");sendJson(out,200,"OK",r);
    }

    private JSONObject buildBackup(JSONObject me)throws JSONException{
        String owner=me.optString("phone");JSONObject result=new JSONObject();result.put("format","RKM-LOCAL-BACKUP-1");result.put("createdAt",System.currentTimeMillis());result.put("account",userPublic(me));JSONArray cs=new JSONArray(),ms=new JSONArray(),ss=new JSONArray();
        synchronized(DATA_LOCK){for(int i=0;i<savedContacts.length();i++){JSONObject x=savedContacts.optJSONObject(i);if(x!=null&&samePhone(owner,x.optString("owner")))cs.put(x);}for(int i=0;i<messages.length();i++){JSONObject x=messages.optJSONObject(i);if(x!=null&&(samePhone(owner,x.optString("sender"))||samePhone(owner,x.optString("recipient"))||(x.optString("groupId").length()>0&&isGroupMember(findGroup(x.optString("groupId")),owner))))ms.put(x);}for(int i=0;i<statuses.length();i++){JSONObject x=statuses.optJSONObject(i);if(x!=null&&samePhone(owner,x.optString("phone")))ss.put(x);}}
        result.put("contacts",cs);result.put("messages",ms);result.put("statuses",ss);JSONArray userGroups=new JSONArray(),allGroups=readFeatureStore("groups");for(int i=0;i<allGroups.length();i++){JSONObject g=allGroups.optJSONObject(i);if(g!=null&&isGroupMember(g,owner))userGroups.put(g);}result.put("groups",userGroups);for(String c:new String[]{"settings","privacy","blocked","archived-contacts","favorite-contacts","pinned-contacts","chat-settings","message-stars","message-archives","message-pins","status-archive","status-highlights","sticker-favorites","call-history","message-reactions","message-receipts","once-views","status-comments","status-views","status-reactions","status-reposts","profile-visitors"}){JSONArray selected=new JSONArray(),all=readFeatureStore(c);for(int i=0;i<all.length();i++){JSONObject x=all.optJSONObject(i);if(x!=null&&samePhone(owner,x.optString("owner")))selected.put(x);}result.put(c,selected);}return result;
    }
    private void exportBackup(OutputStream out,JSONObject me,String query)throws IOException,JSONException{
        JSONObject backup=buildBackup(me),r=new JSONObject();r.put("ok",true);r.put("backup",backup);boolean includeMedia="true".equalsIgnoreCase(queryParam(query,"includeMedia"));JSONArray files=new JSONArray();long total=0;
        if(includeMedia){JSONArray allMessages=backup.optJSONArray("messages"),allStatuses=backup.optJSONArray("statuses"),allGroups=backup.optJSONArray("groups"),sources=new JSONArray();if(allMessages!=null)for(int i=0;i<allMessages.length();i++)sources.put(allMessages.opt(i));if(allStatuses!=null)for(int i=0;i<allStatuses.length();i++)sources.put(allStatuses.opt(i));if(allGroups!=null)for(int i=0;i<allGroups.length();i++)sources.put(allGroups.opt(i));
            for(int i=0;i<sources.length();i++){JSONObject x=sources.optJSONObject(i);if(x==null)continue;String firstExt="image".equals(x.optString("mediaType"))?".jpg":".blob";String[] ids={x.optString("mediaId"),x.optString("imageId"),x.optString("voiceId"),x.optString("photoId")};String[] exts={firstExt,".jpg",".voice",".blob"};for(int j=0;j<ids.length;j++){String mid=ids[j];if(mid.length()!=32||!mid.matches("[a-fA-F0-9]+"))continue;File f=new File(imagesDir,mid+exts[j]);if(!f.exists()||total+f.length()>4L*1024L*1024L)continue;ByteArrayOutputStream b=new ByteArrayOutputStream();FileInputStream in=null;try{in=new FileInputStream(f);byte[] chunk=new byte[8192];int n;while((n=in.read(chunk))!=-1)b.write(chunk,0,n);}finally{if(in!=null)try{in.close();}catch(Exception ignored){}}JSONObject mf=new JSONObject();mf.put("id",mid);mf.put("extension",exts[j]);mf.put("dataBase64",Base64.encodeToString(b.toByteArray(),Base64.NO_WRAP));files.put(mf);total+=f.length();break;}}
        }
        backup.put("mediaFiles",files);r.put("includesMediaFiles",includeMedia&&files.length()>0);r.put("mediaLimitBytes",4*1024*1024);r.put("note","Ekspor media di satu respons dibatasi 4 MB agar aman untuk tablet RAM kecil. Upload Google Drive membutuhkan OAuth klien dan tidak dilakukan langsung oleh server tablet.");sendJson(out,200,"OK",r);
    }
    private void exportChat(OutputStream out,String query,JSONObject me)throws IOException,JSONException{
        String owner=me.optString("phone"),peer=normalizePhone(queryParam(query,"peer")),groupId=queryParam(query,"groupId");if(peer.length()==0&&groupId.length()==0){sendJson(out,400,"Bad Request",errorJson("Isi peer atau groupId untuk mengekspor chat"));return;}if(groupId.length()>0){JSONObject g=findGroup(groupId);if(g==null||!isGroupMember(g,owner)){sendJson(out,403,"Forbidden",errorJson("Bukan anggota grup"));return;}}JSONArray list=new JSONArray();synchronized(DATA_LOCK){for(int i=0;i<messages.length();i++){JSONObject m=messages.optJSONObject(i);if(m==null)continue;if(m.optString("localRestoreOwner").length()>0&&!samePhone(owner,m.optString("localRestoreOwner")))continue;if(groupId.length()>0){if(groupId.equals(m.optString("groupId")))list.put(m);}else if((samePhone(owner,m.optString("sender"))&&samePhone(peer,m.optString("recipient")))||(samePhone(peer,m.optString("sender"))&&samePhone(owner,m.optString("recipient"))))list.put(m);}}JSONObject r=new JSONObject();r.put("ok",true);r.put("format","RKM-CHAT-EXPORT-1");r.put("peer",peer);r.put("groupId",groupId);r.put("messages",list);boolean includeMedia="true".equalsIgnoreCase(queryParam(query,"includeMedia"));JSONArray mediaFiles=new JSONArray();long mediaTotal=0;
        if(includeMedia){for(int i=0;i<list.length();i++){JSONObject m=list.optJSONObject(i);if(m==null)continue;String[] ids={m.optString("mediaId"),m.optString("imageId"),m.optString("voiceId")};String[] exts={".blob",".jpg",".voice"};for(int j=0;j<ids.length;j++){String mid=ids[j];if(mid.length()!=32||!mid.matches("[a-fA-F0-9]+"))continue;File f=new File(imagesDir,mid+exts[j]);if(!f.exists())continue;if(mediaTotal+f.length()>6L*1024L*1024L){r.put("mediaLimitReached",true);break;}ByteArrayOutputStream mb=new ByteArrayOutputStream();FileInputStream fi=null;try{fi=new FileInputStream(f);byte[] chunk=new byte[8192];int nn;while((nn=fi.read(chunk))!=-1)mb.write(chunk,0,nn);}finally{if(fi!=null)try{fi.close();}catch(Exception ignored){}}JSONObject mf=new JSONObject();mf.put("id",mid);mf.put("extension",exts[j]);mf.put("mimeType",m.optString("mimeType",m.optString("mediaMime","application/octet-stream")));mf.put("dataBase64",Base64.encodeToString(mb.toByteArray(),Base64.NO_WRAP));mediaFiles.put(mf);mediaTotal+=f.length();break;}}}
        r.put("mediaFiles",mediaFiles);r.put("includesMediaBytes",includeMedia&&!mediaFiles.isNull(0));r.put("mediaEndpoint","/api/media?token=TOKEN&id=MEDIA_ID");sendJson(out,200,"OK",r);
    }
    private void getContactCard(OutputStream out,String query)throws IOException,JSONException{JSONObject me=userForToken(queryParam(query,"token"));if(me==null){sendJson(out,401,"Unauthorized",errorJson("Sesi tidak valid"));return;}String phone=normalizePhone(queryParam(query,"phone"));if(phone.length()==0)phone=me.optString("phone");JSONObject u=findUser(phone);if(u==null){sendJson(out,404,"Not Found",errorJson("Kontak tidak ditemukan"));return;}JSONObject c=new JSONObject();c.put("phone",phone);c.put("name",u.optString("name"));c.put("about",u.optString("about",""));c.put("payload","RKM1:"+phone);JSONObject r=new JSONObject();r.put("ok",true);r.put("card",c);sendJson(out,200,"OK",r);}

    private void loadAll(){ synchronized(DATA_LOCK){messages=loadArray(messagesFile,MAX_MESSAGES);users=loadArray(usersFile,1000);sessions=loadArray(sessionsFile,2000);callSignals=loadArray(callsFile,MAX_CALL_SIGNALS);statuses=loadArray(statusesFile,MAX_STATUSES);savedContacts=loadArray(contactsFile,5000);for(int i=0;i<callSignals.length();i++){JSONObject s=callSignals.optJSONObject(i);if(s!=null)nextSignalId=Math.max(nextSignalId,s.optLong("id",0));}} }
    private JSONArray loadArray(File file,int limit){if(file==null||!file.exists())return new JSONArray();try{JSONArray a=new JSONArray(readFile(file));JSONArray b=new JSONArray();int from=Math.max(0,a.length()-limit);for(int i=from;i<a.length();i++)b.put(a.get(i));return b;}catch(Exception e){return new JSONArray();}}
    private String readFile(File f)throws IOException{FileInputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();return new String(out.toByteArray(),UTF8);}
    private void saveArray(File f,JSONArray a){File temp=new File(getFilesDir(),f.getName()+".tmp");try{FileOutputStream o=new FileOutputStream(temp);o.write(a.toString().getBytes(UTF8));o.flush();o.getFD().sync();o.close();if(f.exists()&&!f.delete())throw new IOException("replace failed");if(!temp.renameTo(f))throw new IOException("rename failed");}catch(Exception e){lastError="Gagal menyimpan data";try{if(temp.exists())temp.delete();}catch(Exception ignored){}}}
    private void appendLimited(JSONArray a,JSONObject item,int max)throws JSONException{JSONArray n=new JSONArray();int from=Math.max(0,a.length()-max+1);for(int i=from;i<a.length();i++)n.put(a.get(i));n.put(item);if(a==messages)messages=n;else if(a==callSignals)callSignals=n;else if(a==statuses)statuses=n;else if(a==sessions)sessions=n;else if(a==users)users=n;}
    private JSONObject findUser(String phone){if(phone==null)return null;for(int i=0;i<users.length();i++){JSONObject u=users.optJSONObject(i);if(u!=null&&samePhone(phone,u.optString("phone")))return u;}return null;}
    private JSONObject userForToken(String token){if(token==null||token.length()==0)return null; synchronized(DATA_LOCK){for(int i=0;i<sessions.length();i++){JSONObject s=sessions.optJSONObject(i);if(s!=null&&constantTimeEquals(token,s.optString("token", ""))){JSONObject u=findUser(s.optString("phone"));if(u!=null)return u;}}}return null;}
    private String createSession(String phone)throws JSONException{String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");JSONObject s=new JSONObject();s.put("phone",phone);s.put("token",token);s.put("time",System.currentTimeMillis());appendLimited(sessions,s,2000);saveArray(sessionsFile,sessions);return token;}
    private JSONObject userPublic(JSONObject u)throws JSONException{JSONObject r=new JSONObject();String phone=u.optString("phone");r.put("phone",phone);r.put("name",u.optString("name"));r.put("about",u.optString("about",""));r.put("qrPayload","RKM1:"+phone);JSONArray p=readFeatureStore("presence");for(int i=0;i<p.length();i++){JSONObject x=p.optJSONObject(i);if(x!=null&&samePhone(phone,x.optString("owner"))){r.put("online",x.optBoolean("online",false)&&System.currentTimeMillis()-x.optLong("time",0)<120000);r.put("lastSeen",x.optLong("time",0));break;}}return r;}
    private String normalizePhone(String p){if(p==null)return "";return p.replaceAll("[^0-9]", "");}
    private boolean validPhone(String p){return p!=null&&p.matches("[0-9]{8,15}");}
    private String cleanName(String n){if(n==null)return "";n=n.trim();if(n.length()>40)n=n.substring(0,40);return n;}
    private String hashPassword(String salt,String password){try{PBEKeySpec spec=new PBEKeySpec(password.toCharArray(),salt.getBytes(UTF8),30000,256);byte[] b=SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).getEncoded();spec.clearPassword();StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));return "pbkdf2$30000$"+s.toString();}catch(Exception e){return "";}}
    private String legacyHashPassword(String salt,String password){try{MessageDigest md=MessageDigest.getInstance("SHA-256");byte[] b=md.digest((salt+":"+password).getBytes(UTF8));StringBuilder s=new StringBuilder();for(byte x:b)s.append(String.format(Locale.US,"%02x",x&0xff));return s.toString();}catch(Exception e){return "";}}
    private boolean constantTimeEquals(String a,String b){if(a==null||b==null)return false;int diff=a.length()^b.length();int n=Math.max(a.length(),b.length());for(int i=0;i<n;i++){char x=i<a.length()?a.charAt(i):0,y=i<b.length()?b.charAt(i):0;diff|=x^y;}return diff==0;}
    private boolean samePhone(String a,String b){return a!=null&&b!=null&&a.equals(b);}
    private long nextSignalId(){nextSignalId=Math.max(nextSignalId+1,System.currentTimeMillis());return nextSignalId;}
    private String timeNow(){return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(new Date());}
    private String queryParam(String query,String wanted){if(query==null||query.length()==0)return "";String[] pairs=query.split("&");for(String pair:pairs){int e=pair.indexOf('=');String k=e>=0?pair.substring(0,e):pair,v=e>=0?pair.substring(e+1):"";try{k=URLDecoder.decode(k,"UTF-8");v=URLDecoder.decode(v,"UTF-8");}catch(Exception ignored){}if(wanted.equals(k))return v;}return "";}
    private JSONObject errorJson(String msg){JSONObject o=new JSONObject();try{o.put("ok",false);o.put("error",msg);}catch(JSONException ignored){}return o;}
    private int getMessageCount(){synchronized(DATA_LOCK){return messages.length();}}
    private byte[] readHeaders(InputStream input)throws IOException{ByteArrayOutputStream b=new ByteArrayOutputStream();int state=0;while(b.size()<MAX_HEADER_BYTES){int c=input.read();if(c<0)return null;b.write(c);if(state==0&&c=='\r')state=1;else if(state==1&&c=='\n')state=2;else if(state==2&&c=='\r')state=3;else if(state==3&&c=='\n')return b.toByteArray();else if(c=='\r')state=1;else state=0;}throw new IOException("Header terlalu besar");}
    private byte[] readBody(InputStream input,int length)throws IOException{byte[] r=new byte[length];int total=0;while(total<length){int n=input.read(r,total,length-total);if(n<0)return null;total+=n;}return r;}
    private byte[] readAsset(String name)throws IOException{InputStream in=getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1)out.write(b,0,n);in.close();return out.toByteArray();}
    private void sendJson(OutputStream out,int code,String reason,String json)throws IOException{sendResponse(out,code,reason,"application/json; charset=utf-8",json.getBytes(UTF8));}
    // Build fix: accept JSONObject responses and serialize them to JSON text.
    private void sendJson(OutputStream out,int code,String reason,JSONObject json)throws IOException{sendJson(out,code,reason,json==null?"{}":json.toString());}
    private void sendResponseCached(OutputStream out,int code,String reason,String type,byte[] body,int maxAge)throws IOException{String header="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: private, max-age="+Math.max(0,maxAge)+"\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n";out.write(header.getBytes(LATIN1));out.write(body);out.flush();}
    private void sendResponse(OutputStream out,int code,String reason,String type,byte[] body)throws IOException{String header="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+"\r\nConnection: close\r\nCache-Control: no-store\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nAccess-Control-Allow-Headers: Content-Type\r\n\r\n";out.write(header.getBytes(LATIN1));out.write(body);out.flush();}
    private void closeServerSocket(){ServerSocket s=serverSocket;serverSocket=null;if(s!=null)try{s.close();}catch(Exception ignored){}}
    private void releaseLocks(){try{if(wakeLock!=null&&wakeLock.isHeld())wakeLock.release();}catch(Exception ignored){}try{if(wifiLock!=null&&wifiLock.isHeld())wifiLock.release();}catch(Exception ignored){}wakeLock=null;wifiLock=null;}
    private void closeQuietly(Socket s){if(s!=null)try{s.close();}catch(Exception ignored){}}
    @Override public void onDestroy(){stopRequested=true;isRunning=false;closeServerSocket();releaseLocks();ThreadPoolExecutor p=workers;if(p!=null)p.shutdownNow();if(acceptThread!=null)acceptThread.interrupt();stopForeground(true);super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
