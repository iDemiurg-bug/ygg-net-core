package io.github.idemiurg.yggdroid;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * YggdroidTun - единый класс для прямого TUN с Yggdrasil
 * 
 * Содержит:
 * - VPN сервис (наследник VpnService) 
 * - Управление ядром Yggdrasil через рефлексию
 * - Потоки чтения/записи пакетов
 * - Python API через pyjnius
 */
public class YggdroidTun {
    private static final String TAG = "YggdroidTun";
    private static final String CHANNEL_ID = "yggdroid_tun";
    private static final int NOTIFICATION_ID = 1001;
    
    private static YggdroidTun instance;
    private final Context context;
    private Object yggdrasil; // Экземпляр Yggdrasil из отдельного AAR
    private ParcelFileDescriptor tunFd;
    private FileInputStream tunInput;
    private FileOutputStream tunOutput;
    private Thread readerThread;
    private Thread writerThread;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private String configPath;
    private TunCallback callback;
    
    // Кэшированные методы для рефлексии
    private Method startJSONMethod;
    private Method stopMethod;
    private Method addressStringMethod;
    private Method subnetStringMethod;
    private Method publicKeyStringMethod;
    private Method peersJSONMethod;
    private Method sendBufferMethod;
    private Method recvBufferMethod;
    private Method generateConfigJSONMethod;
    private Method addPeerMethod;
    private Method removePeerMethod;
    private Method retryPeersNowMethod;
    
    public interface TunCallback {
        void onStarted();
        void onStopped();
        void onError(String error);
        void onPeerConnected(String peerUri);
        void onPeerDisconnected(String peerUri);
    }
    
    private YggdroidTun(Context context) {
        this.context = context.getApplicationContext();
        this.configPath = new File(context.getFilesDir(), "yggdrasil.conf").getAbsolutePath();
    }
    
    public static synchronized YggdroidTun getInstance(Context context) {
        if (instance == null) {
            instance = new YggdroidTun(context);
        }
        return instance;
    }
    
    /**
     * Устанавливает экземпляр Yggdrasil из отдельного AAR
     * Должен быть вызван до startTun()
     */
    public void setYggdrasil(Object yggdrasilInstance) {
        this.yggdrasil = yggdrasilInstance;
        cacheMethods();
        Log.i(TAG, "Yggdrasil instance set and methods cached");
    }
    
    /**
     * Кэшируем методы Yggdrasil для быстрого вызова
     */
    private void cacheMethods() {
        if (yggdrasil == null) return;
        
        try {
            Class<?> clazz = yggdrasil.getClass();
            
            // Пробуем разные варианты имён методов (AddPeer/addPeer и т.д.)
            startJSONMethod = findMethod(clazz, "startJSON", byte[].class);
            stopMethod = findMethod(clazz, "stop");
            addressStringMethod = findMethod(clazz, "addressString");
            subnetStringMethod = findMethod(clazz, "subnetString");
            publicKeyStringMethod = findMethod(clazz, "publicKeyString");
            peersJSONMethod = findMethod(clazz, "peersJSON");
            sendBufferMethod = findMethod(clazz, "sendBuffer", byte[].class, int.class);
            recvBufferMethod = findMethod(clazz, "recvBuffer", byte[].class);
            generateConfigJSONMethod = findMethod(clazz, "generateConfigJSON");
            
            // Методы управления пирами
            addPeerMethod = findMethod(clazz, "AddPeer", String.class);
            if (addPeerMethod == null) {
                addPeerMethod = findMethod(clazz, "addPeer", String.class);
            }
            
            removePeerMethod = findMethod(clazz, "RemovePeer", String.class);
            if (removePeerMethod == null) {
                removePeerMethod = findMethod(clazz, "removePeer", String.class);
            }
            
            retryPeersNowMethod = findMethod(clazz, "RetryPeersNow");
            if (retryPeersNowMethod == null) {
                retryPeersNowMethod = findMethod(clazz, "retryPeersNow");
            }
            
            Log.i(TAG, "Methods cached successfully");
        } catch (Exception e) {
            Log.e(TAG, "Error caching methods", e);
        }
    }
    
    /**
     * Поиск метода с игнорированием регистра
     */
    private Method findMethod(Class<?> clazz, String methodName, Class<?>... parameterTypes) {
        try {
            return clazz.getMethod(methodName, parameterTypes);
        } catch (NoSuchMethodException e) {
            // Пробуем с другим регистром
            String altName = methodName.substring(0, 1).toLowerCase() + methodName.substring(1);
            try {
                return clazz.getMethod(altName, parameterTypes);
            } catch (NoSuchMethodException ex) {
                return null;
            }
        }
    }
    
    /**
     * Безопасный вызов метода Yggdrasil через рефлексию
     */
    private Object callYggdrasilMethod(Method method, Object... args) {
        if (method == null) {
            Log.e(TAG, "Method is null");
            return null;
        }
        if (yggdrasil == null) {
            Log.e(TAG, "Yggdrasil not set");
            return null;
        }
        
        try {
            return method.invoke(yggdrasil, args);
        } catch (Exception e) {
            Log.e(TAG, "Error calling " + method.getName(), e);
            return null;
        }
    }
    
    public void setCallback(TunCallback callback) {
        this.callback = callback;
    }
    
    // ========== VPN Service ==========
    
    /**
     * VPN сервис - наследник VpnService (обязательно для Android) 
     */
    public static class YggdroidVpnService extends VpnService {
        
        public static final String ACTION_START = "io.github.idemiurg.yggdroid.START";
        public static final String ACTION_STOP = "io.github.idemiurg.yggdroid.STOP";
        public static final String ACTION_ADD_PEER = "io.github.idemiurg.yggdroid.ADD_PEER";
        public static final String ACTION_REMOVE_PEER = "io.github.idemiurg.yggdroid.REMOVE_PEER";
        public static final String EXTRA_PEER_URI = "peer_uri";
        
        private YggdroidTun core;
        
        @Override
        public void onCreate() {
            super.onCreate();
            core = YggdroidTun.getInstance(this);
            createNotificationChannel();
        }
        
        @Override
        public int onStartCommand(Intent intent, int flags, int startId) {
            if (intent == null) return START_NOT_STICKY;
            
            String action = intent.getAction();
            Log.i(TAG, "Received action: " + action);
            
            if (ACTION_START.equals(action)) {
                startForeground(NOTIFICATION_ID, createNotification());
                core.startTunInternal(this);
                
            } else if (ACTION_STOP.equals(action)) {
                core.stopTunInternal();
                stopForeground(true);
                stopSelf();
                
            } else if (ACTION_ADD_PEER.equals(action)) {
                String peerUri = intent.getStringExtra(EXTRA_PEER_URI);
                if (peerUri != null) {
                    core.addPeerInternal(peerUri);
                }
                
            } else if (ACTION_REMOVE_PEER.equals(action)) {
                String peerUri = intent.getStringExtra(EXTRA_PEER_URI);
                if (peerUri != null) {
                    core.removePeerInternal(peerUri);
                }
            }
            
            return START_STICKY;
        }
        
        @Override
        public void onDestroy() {
            core.stopTunInternal();
            super.onDestroy();
        }
        
        @Override
        public void onRevoke() {
            // Вызывается, когда система отзывает разрешение VPN 
            Log.i(TAG, "VPN permission revoked");
            core.stopTunInternal();
            stopForeground(true);
            stopSelf();
        }
        
        private void createNotificationChannel() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Yggdroid TUN Service",
                    NotificationManager.IMPORTANCE_LOW
                );
                NotificationManager manager = getSystemService(NotificationManager.class);
                manager.createNotificationChannel(channel);
            }
        }
        
        private Notification createNotification() {
            return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Yggdroid")
                .setContentText("TUN interface is running")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
        }
    }
    
    // ========== Внутренние методы TUN ==========
    
    /**
     * Создание и настройка TUN интерфейса 
     */
    private void setupTunInterface(VpnService vpnService) throws Exception {
        VpnService.Builder builder = new VpnService.Builder();
        
        // Получаем адрес из Yggdrasil
        String address = (String) callYggdrasilMethod(addressStringMethod);
        if (address == null || address.isEmpty()) {
            throw new Exception("No address from Yggdrasil");
        }
        
        Log.i(TAG, "Setting up TUN with address: " + address);
        
        // Настройка TUN интерфейса 
        builder.setSession("Yggdroid")
               .setMtu(1280) // Рекомендуемый MTU для Yggdrasil
               .addAddress(address, 7) // IPv6 адрес с префиксом /7
               .addRoute("200::", 3); // Маршрут для всей сети Yggdrasil
        
        // На Android 10+ можно настроить metered статус
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false); // Наследуем статус от underlying сети
        }
        
        // Создаём TUN интерфейс 
        tunFd = builder.establish();
        if (tunFd == null) {
            throw new Exception("Failed to establish TUN interface");
        }
        
        // Создаём потоки для чтения/записи
        tunInput = new FileInputStream(tunFd.getFileDescriptor());
        tunOutput = new FileOutputStream(tunFd.getFileDescriptor());
        
        Log.i(TAG, "TUN interface established successfully");
    }
    
    /**
     * Запуск потоков для обработки пакетов
     */
    private void startPacketThreads() {
        // Поток чтения из TUN -> отправка в Yggdrasil
        readerThread = new Thread(() -> {
            byte[] buffer = new byte[65535];
            Log.i(TAG, "Reader thread started");
            
            while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    int read = tunInput.read(buffer);
                    if (read > 0) {
                        // Отправляем пакет в ядро Yggdrasil
                        callYggdrasilMethod(sendBufferMethod, buffer, read);
                    }
                } catch (IOException e) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error reading from TUN", e);
                    }
                    break;
                }
            }
            Log.i(TAG, "Reader thread stopped");
        }, "Yggdroid-Reader");
        
        // Поток получения из Yggdrasil -> запись в TUN
        writerThread = new Thread(() -> {
            byte[] buffer = new byte[65535];
            Log.i(TAG, "Writer thread started");
            
            while (isRunning.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Integer len = (Integer) callYggdrasilMethod(recvBufferMethod, buffer);
                    if (len != null && len > 0) {
                        tunOutput.write(buffer, 0, len);
                        tunOutput.flush();
                    } else {
                        // Нет данных, небольшая пауза
                        Thread.sleep(10);
                    }
                } catch (IOException | InterruptedException e) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Error writing to TUN", e);
                    }
                    break;
                }
            }
            Log.i(TAG, "Writer thread stopped");
        }, "Yggdroid-Writer");
        
        readerThread.start();
        writerThread.start();
    }
    
    /**
     * Внутренний метод запуска TUN
     */
    private void startTunInternal(VpnService vpnService) {
        try {
            if (yggdrasil == null) {
                throw new Exception("Yggdrasil not set. Call setYggdrasil() first");
            }
            
            // Загружаем конфигурацию
            byte[] config = loadConfig();
            if (config == null) {
                Log.i(TAG, "No config found, generating new one");
                config = (byte[]) callYggdrasilMethod(generateConfigJSONMethod);
                saveConfig(config);
            }
            
            // Запускаем ядро Yggdrasil
            Log.i(TAG, "Starting Yggdrasil core");
            callYggdrasilMethod(startJSONMethod, config);
            
            // Настраиваем TUN интерфейс
            setupTunInterface(vpnService);
            
            // Запускаем потоки
            isRunning.set(true);
            startPacketThreads();
            
            Log.i(TAG, "Yggdroid TUN started successfully");
            if (callback != null) callback.onStarted();
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to start TUN", e);
            if (callback != null) callback.onError(e.getMessage());
            stopTunInternal();
        }
    }
    
    /**
     * Внутренний метод остановки TUN
     */
    private void stopTunInternal() {
        isRunning.set(false);
        
        // Останавливаем потоки
        try {
            if (readerThread != null) {
                readerThread.interrupt();
                readerThread.join(1000);
            }
            if (writerThread != null) {
                writerThread.interrupt();
                writerThread.join(1000);
            }
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted while stopping threads", e);
        }
        
        // Останавливаем ядро Yggdrasil
        try {
            callYggdrasilMethod(stopMethod);
        } catch (Exception e) {
            Log.e(TAG, "Error stopping Yggdrasil", e);
        }
        
        // Закрываем ресурсы
        try {
            if (tunInput != null) tunInput.close();
            if (tunOutput != null) tunOutput.close();
            if (tunFd != null) tunFd.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing TUN resources", e);
        }
        
        Log.i(TAG, "Yggdroid TUN stopped");
        if (callback != null) callback.onStopped();
    }
    
    // ========== Управление пирами ==========
    
    /**
     * Добавление пира в рантайме
     */
    private void addPeerInternal(String peerUri) {
        if (!isRunning.get()) {
            Log.w(TAG, "TUN not running, peer will be added to config only");
            addPeerToConfig(peerUri);
            return;
        }
        
        callYggdrasilMethod(addPeerMethod, peerUri);
        Log.i(TAG, "Peer added: " + peerUri);
        addPeerToConfig(peerUri);
        if (callback != null) callback.onPeerConnected(peerUri);
    }
    
    /**
     * Удаление пира
     */
    private void removePeerInternal(String peerUri) {
        if (!isRunning.get()) {
            removePeerFromConfig(peerUri);
            return;
        }
        
        callYggdrasilMethod(removePeerMethod, peerUri);
        Log.i(TAG, "Peer removed: " + peerUri);
        removePeerFromConfig(peerUri);
        if (callback != null) callback.onPeerDisconnected(peerUri);
    }
    
    /**
     * Принудительное переподключение ко всем пирам
     */
    public void retryPeers() {
        if (!isRunning.get()) return;
        callYggdrasilMethod(retryPeersNowMethod);
        Log.i(TAG, "Retrying peers");
    }
    
    // ========== Управление конфигурацией ==========
    
    private void addPeerToConfig(String peerUri) {
        try {
            byte[] configData = loadConfig();
            if (configData == null) return;
            
            String configStr = new String(configData);
            org.json.JSONObject config = new org.json.JSONObject(configStr);
            
            org.json.JSONArray peers = config.optJSONArray("Peers");
            if (peers == null) {
                peers = new org.json.JSONArray();
                config.put("Peers", peers);
            }
            
            // Проверяем, нет ли уже такого пира
            for (int i = 0; i < peers.length(); i++) {
                if (peers.getString(i).equals(peerUri)) {
                    return;
                }
            }
            
            peers.put(peerUri);
            saveConfig(config.toString().getBytes());
            Log.i(TAG, "Peer added to config: " + peerUri);
            
        } catch (Exception e) {
            Log.e(TAG, "Error adding peer to config", e);
        }
    }
    
    private void removePeerFromConfig(String peerUri) {
        try {
            byte[] configData = loadConfig();
            if (configData == null) return;
            
            String configStr = new String(configData);
            org.json.JSONObject config = new org.json.JSONObject(configStr);
            
            org.json.JSONArray peers = config.optJSONArray("Peers");
            if (peers == null) return;
            
            org.json.JSONArray newPeers = new org.json.JSONArray();
            for (int i = 0; i < peers.length(); i++) {
                String peer = peers.getString(i);
                if (!peer.equals(peerUri)) {
                    newPeers.put(peer);
                }
            }
            
            config.put("Peers", newPeers);
            saveConfig(config.toString().getBytes());
            Log.i(TAG, "Peer removed from config: " + peerUri);
            
        } catch (Exception e) {
            Log.e(TAG, "Error removing peer from config", e);
        }
    }
    
    /**
     * Сохранение конфига
     */
    public boolean saveConfig(byte[] config) {
        try (FileOutputStream fos = new FileOutputStream(configPath)) {
            fos.write(config);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Save config error", e);
            return false;
        }
    }
    
    /**
     * Загрузка конфига
     */
    public byte[] loadConfig() {
        File file = new File(configPath);
        if (!file.exists()) return null;
        
        try (FileInputStream fis = new FileInputStream(configPath)) {
            return fis.readAllBytes();
        } catch (Exception e) {
            Log.e(TAG, "Load config error", e);
            return null;
        }
    }
    
    /**
     * Генерация нового конфига (требует Yggdrasil)
     */
    public byte[] generateConfig() {
        if (yggdrasil == null) {
            Log.e(TAG, "Yggdrasil not set");
            return null;
        }
        return (byte[]) callYggdrasilMethod(generateConfigJSONMethod);
    }
    
    // ========== Python API ==========
    
    /**
     * Запуск TUN (должен вызываться из Activity)
     */
    public boolean startTun() {
        if (yggdrasil == null) {
            Log.e(TAG, "Yggdrasil not set. Call setYggdrasil() first");
            return false;
        }
        
        // Проверяем разрешение VPN 
        if (VpnService.prepare(context) != null) {
            Log.e(TAG, "VPN permission not granted");
            return false;
        }
        
        Intent intent = new Intent(context, YggdroidVpnService.class);
        intent.setAction(YggdroidVpnService.ACTION_START);
        context.startService(intent);
        return true;
    }
    
    /**
     * Остановка TUN
     */
    public void stopTun() {
        Intent intent = new Intent(context, YggdroidVpnService.class);
        intent.setAction(YggdroidVpnService.ACTION_STOP);
        context.startService(intent);
    }
    
    /**
     * Добавление пира
     */
    public void addPeer(String peerUri) {
        Intent intent = new Intent(context, YggdroidVpnService.class);
        intent.setAction(YggdroidVpnService.ACTION_ADD_PEER);
        intent.putExtra(YggdroidVpnService.EXTRA_PEER_URI, peerUri);
        context.startService(intent);
    }
    
    /**
     * Удаление пира
     */
    public void removePeer(String peerUri) {
        Intent intent = new Intent(context, YggdroidVpnService.class);
        intent.setAction(YggdroidVpnService.ACTION_REMOVE_PEER);
        intent.putExtra(YggdroidVpnService.EXTRA_PEER_URI, peerUri);
        context.startService(intent);
    }
    
    /**
     * Проверка статуса
     */
    public boolean isRunning() {
        return isRunning.get();
    }
    
    /**
     * Получение IP адреса
     */
    public String getAddress() {
        if (yggdrasil == null) return "";
        return (String) callYggdrasilMethod(addressStringMethod);
    }
    
    /**
     * Получение публичного ключа
     */
    public String getPublicKey() {
        if (yggdrasil == null) return "";
        return (String) callYggdrasilMethod(publicKeyStringMethod);
    }
    
    /**
     * Получение подсети
     */
    public String getSubnet() {
        if (yggdrasil == null) return "";
        return (String) callYggdrasilMethod(subnetStringMethod);
    }
    
    /**
     * Получение списка пиров в JSON
     */
    public String getPeersJson() {
        if (yggdrasil == null) return "[]";
        return (String) callYggdrasilMethod(peersJSONMethod);
    }
    
    /**
     * Версия
     */
    public String getVersion() {
        return "1.0.0 (direct TUN)";
    }
}
EOF
