package com.powerstick.beaglepro.services;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Binder;
import android.os.IBinder;
import android.support.v7.app.NotificationCompat;
import android.text.TextUtils;

import com.afap.utils.ByteUtil;
import com.powerstick.beaglepro.App;
import com.powerstick.beaglepro.R;
import com.powerstick.beaglepro.event.BatteryEvent;
import com.powerstick.beaglepro.event.BindEvent;
import com.powerstick.beaglepro.event.FirmwareEvent;
import com.powerstick.beaglepro.event.StatusEvent;
import com.powerstick.beaglepro.greendao.Beagle;
import com.powerstick.beaglepro.greendao.BeagleDao;
import com.powerstick.beaglepro.receiver.GattUpdateReceiver;
import com.powerstick.beaglepro.util.BluetoothUtils;
import com.powerstick.beaglepro.util.LogUtil;
import com.powerstick.beaglepro.util.SPUtils;
import com.powerstick.beaglepro.util.Utils;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import de.greenrobot.dao.query.QueryBuilder;
import rx.Observable;
import rx.Subscriber;
import rx.schedulers.Schedulers;


public class BluetoothLeService extends Service {
    private final static String TAG = "BluetoothLeService";

    private SPUtils mSpu = new SPUtils();


    private final IBinder mBinder = new LocalBinder();
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    // 记录MAC对应的BluetoothGatt，可通过MAC来获取各个设备的BluetoothGatt
    private Map<String, BluetoothGatt> mBluetoothGatts = new HashMap<>();
    private Map<String, Boolean> mBellTasks = new HashMap<>();

    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

        @Override
        public void onCharacteristicChanged(final BluetoothGatt gatt, final BluetoothGattCharacteristic
                characteristic) {
            dealWithData(gatt, characteristic);
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dealWithData(gatt, characteristic);
            } else {
                LogUtil.e(TAG, "读取通道信息失败：" + characteristic.getUuid());
            }
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                LogUtil.i(TAG, "BluetoothGatt连接上,MAC:" + gatt.getDevice().getAddress());

                gatt.discoverServices();
                mBluetoothGatts.put(gatt.getDevice().getAddress(), gatt);

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                dealDeviceDisconnected(gatt);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            super.onCharacteristicWrite(gatt, characteristic, status);

            String uuidStr = characteristic.getUuid().toString();

            LogUtil.d(TAG, "onCharacteristicWrite-->\nmac:" + gatt.getDevice().getAddress() + "\nuuid:" + uuidStr +
                    "\n发送的值:" + ByteUtil.byteArrayToHexString(characteristic.getValue()) + "\nstatus:" + status);

            if (TextUtils.equals(uuidStr, BluetoothUtils.UUID_S_EXTRA_C.toString())) { // 设备信息设置

                StatusEvent e = new StatusEvent();
                String action = "";

                if (characteristic.getValue() == BluetoothUtils.VALUE_MODE_THETHER) {
                    action = StatusEvent.ACTION_MODE_THETHER;
                } else if (characteristic.getValue() == BluetoothUtils.VALUE_MODE_FIND) {
                    action = StatusEvent.ACTION_MODE_FIND;
                } else if (characteristic.getValue() == BluetoothUtils.VALUE_FIND_LIGHT_ON) {
                    action = StatusEvent.ACTION_FIND_LIGHT_ON;
                } else if (characteristic.getValue() == BluetoothUtils.VALUE_FIND_LIGHT_OFF) {
                    action = StatusEvent.ACTION_FIND_LIGHT_OFF;
                } else if (characteristic.getValue() == BluetoothUtils.VALUE_TETHER_BEEP_ON) {
                    action = StatusEvent.ACTION_TETHER_BEEP_ON;
                } else if (characteristic.getValue() == BluetoothUtils.VALUE_TETHER_BEEP_OFF) {
                    action = StatusEvent.ACTION_TETHER_BEEP_OFF;
                }


                e.setAction(action);
                e.setMac(gatt.getDevice().getAddress());
                EventBus.getDefault().post(e);
            } else if (TextUtils.equals(uuidStr, BluetoothUtils.UUID_S_IMMEDIATE_C_ALERT.toString())) { // 立即警报设置

                StatusEvent e = new StatusEvent();
                e.setAction(StatusEvent.ACTION_IMMEDIATE);
                e.setMac(gatt.getDevice().getAddress());
                EventBus.getDefault().post(e);

            } else if (TextUtils.equals(uuidStr, BluetoothUtils.UUID_S_EXTRA_C_LOGIN.toString())) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    String mac = gatt.getDevice().getAddress();
                    if (App.getInstance().getDaoSession().getBeagleDao().load(mac) == null) {
                        LogUtil.w(TAG, "认证通过了一个未绑定设备，添加入库：" + mac);
                        Beagle beagle = new Beagle();
                        beagle.setMac(mac);
                        beagle.setSilentPeriod(false);
                        beagle.setSilentPlace(false);
                        beagle.setLocation(true);
                        beagle.setPhoneAlarm(true);
                        beagle.setBeagleAlarm(true);
                        beagle.setTetherBeep(true);
                        beagle.setFindLight(true);
                        beagle.setMode(0);
                        beagle.setUpdateStatus(Beagle.UPDATE_NONE);
                        beagle.setLostStatus(0);
                        String name = gatt.getDevice().getName();
                        beagle.setAlias(TextUtils.isEmpty(name) ? getString(R.string.app_name) : name);
                        App.getInstance().getDaoSession().getBeagleDao().insert(beagle);
                        // 同时记录该设备认证时的ID
                        SPUtils spu = new SPUtils();
                        spu.getSP().edit().putString(mac, spu.getCustomDeviceId()).commit();

                        // 通知绑定页面和主页
                        BindEvent e = new BindEvent();
                        e.setAction(BindEvent.ACTION_BIND_SUCCESS);
                        e.setMac(mac);
                        EventBus.getDefault().post(e);
                    } else {
                        LogUtil.w(TAG, "认证通过,该设备已存在：" + mac);
                    }

                    StatusEvent e1 = new StatusEvent();
                    e1.setAction(StatusEvent.ACTION_SERVICECONNECTED);
                    e1.setMac(mac);
                    EventBus.getDefault().post(e1);

                } else {
                    BindEvent e = new BindEvent();
                    e.setAction(BindEvent.ACTION_BIND_EXIST);
                    EventBus.getDefault().post(e);
                }

            } else if (TextUtils.equals(uuidStr, BluetoothUtils.UUID_S_EXTRA_C_UNBIND.toString())) { // 解绑命令
                QueryBuilder qb = App.getInstance().getDaoSession().getBeagleDao().queryBuilder();
                qb.where(BeagleDao.Properties.Mac.eq(gatt.getDevice().getAddress()));
                List<Beagle> mBeagles = qb.list();

                Beagle beagle;
                if (mBeagles.size() > 0) {
                    beagle = mBeagles.get(0);
                } else {
                    return;
                }

                if (beagle.getUpdateStatus() == Beagle.UPDATE_PROCESSING) {
                    LogUtil.w(TAG, "解绑--升级…………");
                    mBluetoothGatts.remove(gatt.getDevice().getAddress());
                } else if (TextUtils.isEmpty(mSpu.getSP().getString(beagle.getMac(), ""))) {
                    LogUtil.w(TAG, "解绑--->兼容老版本：解绑成功，记录与该设备绑定的唯一值ID");
                    String bindId = mSpu.getCustomDeviceId();
                    mSpu.getSP().edit().putString(beagle.getMac(), bindId).commit();

                    connect(beagle.getMac());
                } else {
                    LogUtil.w(TAG, "解绑！！！");
                    App.getInstance().getDaoSession().getBeagleDao().deleteByKey(beagle.getMac());
                    StatusEvent e = new StatusEvent();
                    e.setAction(StatusEvent.ACTION_UNBIND);
                    e.setMac(gatt.getDevice().getAddress());
                    EventBus.getDefault().post(e);
                }
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            LogUtil.v(TAG, "onServicesDiscovered received: " + status);
            cancelBell();
            if (status == BluetoothGatt.GATT_SUCCESS) {
                dealServicesDiscoverd(gatt);
            }
        }

        @Override
        public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                StatusEvent e = new StatusEvent();
                e.setAction(StatusEvent.ACTION_RSSI);
                e.setMac(gatt.getDevice().getAddress());
                e.setRssi(rssi);
                EventBus.getDefault().post(e);
            }
        }
    };

    public boolean initialize() {
        // API >= 18
        if (mBluetoothManager == null) {
            mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager == null) {
                LogUtil.e(TAG, "初始化BluetoothManager失败");
                return false;
            }
        }

        mBluetoothAdapter = mBluetoothManager.getAdapter();
        if (mBluetoothAdapter == null) {
            LogUtil.e(TAG, "获取BluetoothAdapter失败");
            return false;
        }

        return true;
    }


    public BluetoothGatt getGatt(String mac) {
        return mBluetoothGatts.get(mac);
    }

    public void close() {
        for (BluetoothGatt gatt : mBluetoothGatts.values()) {
            if (gatt != null) {
                gatt.close();
            }
        }
        mBluetoothGatts.clear();
    }


    public void clear() {
        if (mBluetoothAdapter != null) {
            mBluetoothGatts.clear();
        }
    }

    /**
     * 连接蓝牙设备
     *
     * @param mac mac
     */
    public boolean connect(String mac) {
        if (mBluetoothAdapter == null || mac == null) {
            return false;
        }

//        List<BluetoothDevice> connectDevices = getConnectedDevices();
//
//        for (BluetoothDevice bd : connectDevices) {
//            if (TextUtils.equals(bd.getAddress(), mac)) {
//                LogUtil.d(TAG, "该设备已经连接啦：" + mac);
//                return true;
//            }
//        }

        if (mBluetoothGatts.get(mac) != null) {
            LogUtil.i(TAG, "该设备已经连接啦：" + mac);
            return true;
        }

        final BluetoothDevice device = mBluetoothManager.getAdapter().getRemoteDevice(mac);
        if (device == null) {
            LogUtil.w(TAG, "Device未找到，无法链接");
            return false;
        }


        device.connectGatt(this, false, mGattCallback);
        LogUtil.d(TAG, "创建一个新连接，MAC=" + mac);
        return true;
    }

    /**
     * 断开指定设备
     */
    public void disconnect(String mac) {
        if (mBluetoothAdapter == null || mBluetoothGatts.get(mac) == null) {
            return;
        }
        BluetoothGatt gatt = mBluetoothGatts.get(mac);
        gatt.disconnect();
        gatt.close();
        mBluetoothGatts.remove(mac);
    }

    /**
     * Retrieves a list of supported GATT services on the connected device. This should be
     * invoked only after {@code BluetoothGatt#discoverServices()} completes successfully.
     *
     * @return A {@code List} of supported services.
     */
    public List<BluetoothGattService> getSupportedGattServices(String mac) {
        if (mBluetoothGatts.get(mac) == null) {
            return null;
        }
        return mBluetoothGatts.get(mac).getServices();
    }

    /**
     * 读取目标通道数据
     *
     * @param mac            目标设备MAC
     * @param characteristic 目标通道
     */
    public void readCharacteristic(String mac, BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatts.get(mac) == null) {
            return;
        }
        mBluetoothGatts.get(mac).readCharacteristic(characteristic);
    }

    /**
     * 向目标通道写入数据
     *
     * @param mac            目标设备MAC
     * @param characteristic 目标通道
     */
    public boolean writeCharacteristic(String mac, BluetoothGattCharacteristic characteristic) {
        if (mBluetoothAdapter == null || mBluetoothGatts.get(mac) == null) {
            return false;
        }

        return mBluetoothGatts.get(mac).writeCharacteristic(characteristic);
    }

    /**
     * 设置监听通道通知
     *
     * @param mac            目标设备MAC
     * @param characteristic 目标通道
     * @param enabled        是否接收通知
     */
    public void setCharacteristicNotification(String mac, BluetoothGattCharacteristic characteristic, boolean enabled) {
        if (mBluetoothAdapter == null || mBluetoothGatts.get(mac) == null) {
            LogUtil.w(TAG, "BluetoothAdapter未初始化");
            return;
        }
        mBluetoothGatts.get(mac).setCharacteristicNotification(characteristic, enabled);
    }

    public List<BluetoothDevice> getConnectedDevices() {
        if (mBluetoothManager == null) {
            return new ArrayList<>();
        }

        return mBluetoothManager.getConnectedDevices(BluetoothProfile.GATT_SERVER);
    }

    public BluetoothGattService getService(String mac, UUID uuid) {
        if (mBluetoothGatts.get(mac) == null) {
            LogUtil.w(TAG, "BluetoothGatt未初始化");
            return null;
        }
        return mBluetoothGatts.get(mac).getService(uuid);
    }

    @Override
    public IBinder onBind(final Intent intent) {
        return mBinder;
    }

    @Override
    public boolean onUnbind(final Intent intent) {
        close();
        return super.onUnbind(intent);
    }

    public class LocalBinder extends Binder {
        public BluetoothLeService getService() {
            return BluetoothLeService.this;
        }
    }


    /**
     * 处理Service被发现
     */
    private void dealServicesDiscoverd(BluetoothGatt gatt) {
        String mac = gatt.getDevice().getAddress();

        displayGattServices(getSupportedGattServices(mac), mac);
    }

    /**
     * 处理设备断开的情况
     */
    private void dealDeviceDisconnected(BluetoothGatt gatt) {
        final Context context = getApplicationContext();
        final String mac = gatt.getDevice().getAddress();

        LogUtil.w(TAG, "断开连接:" + mac);

        if (mBluetoothGatts.get(mac) != null) {
            // 立即尝试一次重连
            LogUtil.w(TAG, "立即尝试重连:" + mac);
            mBluetoothGatts.get(mac).disconnect();
            mBluetoothGatts.get(mac).close();
            mBluetoothGatts.remove(mac);
            // 立即检测，尝试重连
            App.getInstance().checkBeagleSatus();
        } else {
            // 已经从连接列表去除了就不需要再次报警了
            LogUtil.w(TAG, "已经从连接列表去除了就不需要再次报警了");
            return;
        }


        QueryBuilder qb = App.getInstance().getDaoSession().getBeagleDao().queryBuilder();
        qb.where(BeagleDao.Properties.Mac.eq(mac));
        List<Beagle> mBeagles = qb.list();

        final Beagle beagle;
        if (mBeagles.size() > 0) {
            beagle = mBeagles.get(0);
        } else {
            return;
        }

        if (beagle.getPhoneAlarm() && Utils.isNeedNotify(context, beagle) && beagle.getMode() == 0) {
            mBellTasks.put(mac, true);
            // X秒内重连上就不报警了
            Observable
                    .timer(4, TimeUnit.SECONDS)
                    .subscribeOn(Schedulers.io())
                    .observeOn(Schedulers.io())
                    .subscribe(new Subscriber() {
                        @Override
                        public void onCompleted() {
                            LogUtil.d(TAG, "延迟报警onCompleted");
                        }

                        @Override
                        public void onError(Throwable e) {
                            LogUtil.d(TAG, "延迟报警onError" + e.getMessage());
                        }

                        @Override
                        public void onNext(Object o) {
                            LogUtil.d(TAG, "延迟报警onNext");
                            if (mBellTasks.get(mac) != null && mBellTasks.get(mac)) {
                                LogUtil.d(TAG, "延迟报警onNext，没有重连上，报警");
                                bellToRemind();

                                Intent i = new Intent(GattUpdateReceiver.ACTION_CANCEL);
                                PendingIntent pIntent = PendingIntent.getBroadcast(context, 0, i, PendingIntent
                                        .FLAG_UPDATE_CURRENT);
                                Uri ringUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
                                Notification notify = new NotificationCompat
                                        .Builder(context)
                                        .setTicker(context.getString(R.string.app_name))
                                        .setContentTitle(context.getString(R.string.app_name))
                                        .setContentText("Device " + beagle.getAlias() + " Disconnected")
                                        .setSmallIcon(R.drawable.ic_notification)
                                        .setAutoCancel(false)
                                        .setOngoing(true)
                                        .setContentIntent(pIntent)
                                        .setSound(ringUri)
                                        .build();
                                App.getInstance().getmNotificationManager().notify(GattUpdateReceiver.NOTIFY_ID,
                                        notify);
                            } else {
                                LogUtil.d(TAG, "延迟报警onNext，但是重连上了，不报警");
                            }
                        }

                    });
        }

        // 通知主界面
        StatusEvent e = new StatusEvent();
        e.setAction(StatusEvent.ACTION_GATT_DISCONNECTED);
        e.setMac(mac);
        EventBus.getDefault().post(e);
    }


    private static MediaPlayer mMediaPlayer;

    /**
     * 处理接收到的数据
     */
    private void dealWithData(final BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
        LogUtil.d(TAG, "进入方法:dealWithData()");

        final UUID uuid = characteristic.getUuid();
        final String mac = gatt.getDevice().getAddress();
        final byte[] dataArr = characteristic.getValue();
        String dataString = new String(dataArr);

        LogUtil.i(TAG, String.format("发出指令的设备： %S，指令为： %S", mac, ByteUtil.byteArrayToHexString(dataArr)));

        if (BluetoothUtils.UUID_S_KEY_C_PRESS.equals(uuid)) {
            int v = ByteUtil.getIntFromByte(dataArr[0]);

            if (v == 1) { // 短按
                // 通知主界面
                StatusEvent e = new StatusEvent();
                e.setAction(StatusEvent.ACTION_PRESS_SHORT);
                e.setMac(mac);
                EventBus.getDefault().post(e);

            } else if (v == 2) { // 长按
                if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
                    mMediaPlayer.stop();
                } else {
                    bellToRemind();
                }
            }

        } else if (BluetoothUtils.UUID_S_BATTERY_C_LEVEL.equals(uuid)) {

            // 通知设备信息界面
            BatteryEvent e = new BatteryEvent();
            e.setMac(mac);
            e.setLevel(ByteUtil.getIntFromByte(dataArr[0]));
            EventBus.getDefault().post(e);

        } else if (BluetoothUtils.UUID_S_DEVICEINFO_C_FIRMWARE.equals(uuid)) {

            // 通知设备信息界面
            FirmwareEvent e = new FirmwareEvent();
            e.setMac(mac);
            e.setVersion(dataString);
            EventBus.getDefault().post(e);

        }
    }


    void bellToRemind() {
        AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_PLAY_SOUND);

        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
        }

        mMediaPlayer = MediaPlayer.create(getApplicationContext(), R.raw.helium);
        mMediaPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
        mMediaPlayer.setLooping(false); //循环播放
        mMediaPlayer.start();
    }

    public void cancelBell() {
        if (mMediaPlayer != null && mMediaPlayer.isPlaying()) {
            mMediaPlayer.stop();
        }
    }


    private void displayGattServices(List<BluetoothGattService> gattServices, String mac) {
        Beagle beagle = App.getInstance().getDaoSession().getBeagleDao().load(mac);
        if (beagle != null && beagle.getUpdateStatus() == Beagle.UPDATE_PROCESSING) {
            LogUtil.e(TAG, "正在升级，直接返回，不做任何事情");
            return;
        }

        if (gattServices == null) {
            return;
        }

        mBellTasks.put(mac, false);
        cancelBell();

        for (final BluetoothGattService gattService : gattServices) {
            final List<BluetoothGattCharacteristic> gattCharacteristics = gattService.getCharacteristics();

            String uuid0 = gattService.getUuid().toString();

            LogUtil.d(TAG, "服务uuid0=" + uuid0);
            for (final BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                UUID uuid = gattCharacteristic.getUuid();

                LogUtil.i(TAG, "服务uuid=" + uuid);
                // 监听按键指令以及认证结果信息
                if (BluetoothUtils.UUID_S_KEY_C_PRESS.equals(uuid) ||
                        BluetoothUtils.UUID_S_EXTRA_C_LOGIN.equals(uuid)) {
                    LogUtil.i(TAG, "启动监听通知服务uuid=" + uuid);
                    int charaProp = gattCharacteristic.getProperties();
                    if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                        setCharacteristicNotification(mac, gattCharacteristic, true);
                    }
                }
            }
        }

        // 建立连接后，搜寻到设备服务，且该设备并未找到绑定记录，则写入认证信息进行绑定
        String beagleBindId;
        if (beagle != null) {
            beagleBindId = mSpu.getSP().getString(beagle.getMac(), "");
        } else {
            beagleBindId = mSpu.getCustomDeviceId();
        }

        // 如果该设备没有绑定的ID,则是老版本升级过来的，先解绑
        if (TextUtils.isEmpty(beagleBindId)) {
            LogUtil.i(TAG, "老版本APP，兼容升级处理，先解绑再绑定");

            byte[] unbindValue = BluetoothUtils.VALUE_UNBIND;
            BluetoothUtils.sendValueToBle(mac, BluetoothUtils.UUID_S_EXTRA, BluetoothUtils.UUID_S_EXTRA_C_UNBIND,
                    unbindValue);
            return;
        }


        byte[] loginValue = new byte[20];
        System.arraycopy("AUT:".getBytes(), 0, loginValue, 0, 4);
        byte[] l = {(byte) 0x0f};
        System.arraycopy(l, 0, loginValue, 4, 1);
        System.arraycopy(beagleBindId.getBytes(), 0, loginValue, 5, beagleBindId.length());


        boolean loginFlag = BluetoothUtils.sendValueToBle(mac, BluetoothUtils.UUID_S_EXTRA, BluetoothUtils
                .UUID_S_EXTRA_C_LOGIN, loginValue);
        LogUtil.i(TAG, "写入认证信息=" + loginFlag);
        if (!loginFlag) {
            BindEvent e = new BindEvent();
            e.setAction(BindEvent.ACTION_BIND_FAIL);
            e.setMac(mac);
            EventBus.getDefault().post(e);
        }
    }

}