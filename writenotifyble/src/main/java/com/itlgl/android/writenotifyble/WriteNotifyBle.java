package com.itlgl.android.writenotifyble;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.itlgl.java.util.ByteUtil;

import java.io.ByteArrayOutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

public class WriteNotifyBle extends BluetoothGattCallback {
    private static final String TAG = "WriteNotifyBle";

    /**
     * 默认连接超时时间
     */
    public static final long CONNECT_TIMEOUT = 10000;
    /**
     * 默认打开notify/indicate通道的超时时间
     */
    public static final long ENABLE_CHARA_TIMEOUT = 3000;// ms

    /**
     * 成功
     */
    public static final int SUCCESS = 0;
    /**
     * 普通失败
     */
    public static final int FAILURE_GENERAL = 1;
    /**
     * 超时
     */
    public static final int FAILURE_TIMEOUT = 2;

    private Context context;
    private BluetoothDevice bluetoothDevice;
    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothGatt bluetoothGatt;

    private long connectTimeout = CONNECT_TIMEOUT;
    private ConnectState connectState = ConnectState.None;
    private ConnectStateListener connectStateListener;

    // setting params
    private UUID writeServiceUUID, writeCharaUUID, receiveServiceUUID, receiveCharaUUID;
    private int writeType;
    private boolean isReceiveNotify;
    private ReceiveRule receiveRule;
    private boolean printServiceTree = false;
    private boolean printLog = true;

    private BluetoothGattService writeService, receiveService;
    private BluetoothGattCharacteristic writeChara, receiveChara;
    private ByteArrayOutputStream receiveStream = new ByteArrayOutputStream();

    private final BlockingQueue<Boolean> connectResultQueue = new ArrayBlockingQueue<>(5);
    private final BlockingQueue<Boolean> disconnectResultQueue = new ArrayBlockingQueue<>(5);
    private final BlockingQueue<Boolean> enableCharaQueue = new ArrayBlockingQueue<>(5);
    private final BlockingQueue<byte[]> receiveDataQueue = new ArrayBlockingQueue<>(5);
    private final BlockingQueue<Boolean> writeCharaResponseQueue = new ArrayBlockingQueue<>(5);
    private final BlockingQueue<byte[]> readBatteryResponseQueue = new ArrayBlockingQueue<>(5);

    private final Object gattCallbackObj = new Object();

    public interface ReceiveRule {
        boolean isValidResponse(byte[] response);
    }

    public static class Builder {
        private WriteNotifyBle instance;

        public Builder(Context context) {
            this.instance = new WriteNotifyBle(context);
        }

        /**
         *
         * @param writeServiceUUID service uuid
         * @param writeCharaUUID 写通道的uuid
         * @param writeType BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
         *                  BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
         * @return self builder
         */
        public Builder setWriteCharaUUID(UUID writeServiceUUID, UUID writeCharaUUID, int writeType) {
            instance.writeServiceUUID = writeServiceUUID;
            instance.writeCharaUUID = writeCharaUUID;
            instance.writeType = writeType;
            return this;
        }

        /**
         *
         * @param receiveServiceUUID service uuid
         * @param receiveCharaUUID 接收通道uuid，notify或indicate
         * @param isReceiveNotify true-notify, false-indicate
         * @return self builder
         */
        public Builder setReceiveCharaUUID(UUID receiveServiceUUID, UUID receiveCharaUUID, boolean isReceiveNotify) {
            instance.receiveServiceUUID = receiveServiceUUID;
            instance.receiveCharaUUID = receiveCharaUUID;
            instance.isReceiveNotify = isReceiveNotify;
            return this;
        }

        public Builder setReceiveRule(ReceiveRule receiveRule) {
            instance.receiveRule = receiveRule;
            return this;
        }

        public Builder setPrintServiceTree(boolean print) {
            instance.setPrintServiceTree(print);
            return this;
        }

        public Builder setPrintLog(boolean print) {
            instance.setPrintLog(print);
            return this;
        }

        public Builder setConnectTimeout(long timeout) {
            instance.setConnectTimeout(timeout);
            return this;
        }

        public Builder setConnectStateListener(ConnectStateListener listener) {
            instance.setConnectStateListener(listener);
            return this;
        }

        public WriteNotifyBle build() {
            if(instance.writeCharaUUID == null) {
                throw new IllegalArgumentException("writeCharaUUID is null!");
            }
            if(instance.receiveCharaUUID == null) {
                throw new IllegalArgumentException("receiveCharaUUID is null!");
            }
            if(instance.receiveRule == null) {
                throw new IllegalArgumentException("receiveRule is null!");
            }

            return instance;
        }
    }

    private WriteNotifyBle(Context context) {
        this.context = context.getApplicationContext();
        bluetoothManager = (BluetoothManager) context
                .getSystemService(Context.BLUETOOTH_SERVICE);
        if (bluetoothManager == null) {
            loge("[ble initialize error]-Unable to initialize BluetoothManager.");
            return;
        }
        bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null) {
            loge("[ble initialize error]-Unable to obtain a BluetoothAdapter.");
            return;
        }
    }

    public void setConnectTimeout(long timeout) {
        if(timeout < 1000) {
            timeout = 1000;
        }
        connectTimeout = timeout;
    }

    /**
     * 设备是否连接
     * @return 已连接返true，其他返false
     */
    public boolean isConnected() {
        return connectState == ConnectState.Connected;
    }

    /**
     * 获取连接状态
     * @return 返回连接状态
     */
    public ConnectState getConnectState() {
        return connectState;
    }

    /**
     * 设置连接状态监听，可以不设置，可设置为空移除监听
     * @param connectStateListener 监听器，可为空
     */
    public void setConnectStateListener(ConnectStateListener connectStateListener) {
        this.connectStateListener = connectStateListener;
    }

    /**
     * 连接设备，搜索service，但是不进行开通道的操作
     * @param device 设备
     * @return 成功与否
     */
    public synchronized boolean connectDevice(BluetoothDevice device) {
        connectResultQueue.clear();
        // 连接的时候设置不自动连接，如果断开了，就直接断开
        bluetoothGatt = device.connectGatt(context, false, this);
        Boolean connectResult = null;
        try {
            connectResult = connectResultQueue.poll(connectTimeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(connectResult == null) {
            logi("连接超时");
            return false;
        } else if(connectResult) {
            logi("连接成功");
        } else {
            logi("连接失败");
            return false;
        }

        return true;
    }

    /**
     * 连接操作，包括连接设备、搜索service、开notify/indicate通道
     * @param device 设备
     * @return 成功与否
     */
    public synchronized boolean connect(BluetoothDevice device) {
        connectDevice(device);

        // init service
        writeService = getService(writeServiceUUID);
        writeChara = getCharacteristics(writeService, writeCharaUUID);
        receiveService = getService(receiveServiceUUID);
        receiveChara = getCharacteristics(receiveService, receiveCharaUUID);
        if(writeService == null || writeChara == null || receiveService == null || receiveChara == null) {
            logi("寻找service失败");
            disconnect();// 断开
            return false;
        }

        // open notify/indicate\
        boolean enableNotifyResult;
        if(isReceiveNotify) {
            enableNotifyResult = setCharacteristicNotification(receiveChara, true);
        } else {
            enableNotifyResult = setCharacteristicIndication(receiveChara, true);
        }
        if(!enableNotifyResult) {
            logi("打开indicate/notify通道失败");
            disconnect();// 断开
            return false;
        }

        // 完成
        this.bluetoothDevice = device;
        return true;
    }

    /**
     * 断开ble连接
     * @return 成功与否
     */
    public synchronized int disconnect() {
        if (bluetoothGatt != null && connectState == ConnectState.Connected) {
            disconnectResultQueue.clear();

            bluetoothGatt.disconnect();

            Boolean disconnectResult = null;
            try {
                disconnectResult = disconnectResultQueue.poll(connectTimeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            if(disconnectResult == null) {
                return FAILURE_TIMEOUT;
            } else if(disconnectResult) {
                return SUCCESS;
            } else {
                return FAILURE_GENERAL;
            }
        } else {
            return SUCCESS;
        }
    }

    /**
     * 向设备传输数据，方法内部已经进行了分包操作，20字节一包
     * @param data 数据
     * @param timeout 超时时间
     * @return 返回数据
     * @throws Exception 出现错误时抛出异常
     */
    public synchronized byte[] transmitData(byte[] data, long timeout) throws Exception {
        List<byte[]> frameList = new ArrayList<>();
        if(data.length > 20) {
            int len = data.length / 20 + (data.length % 20 != 0 ? 1 : 0);
            for (int i = 0; i < len; i++) {
                int from = i * 20;
                int to = from + 20;
                if(to >= data.length) {
                    to = data.length;
                }
                byte[] frame = Arrays.copyOfRange(data, from, to);
                frameList.add(frame);
            }
        } else {
            frameList.add(data);
        }

        receiveDataQueue.clear();
        for (int i = 0; i < frameList.size(); i++) {
            if(isReceiveNotify) {
                writeCharaResponseQueue.clear();
            }
            boolean writeCharacteristic = writeCharacteristic(writeChara, frameList.get(i));
            if(!writeCharacteristic) {
                loge("发送指令失败");
                throw new Exception("发送指令失败");
            }
            if(isReceiveNotify) {
                Boolean writeCharaResult = null;
                try {
                    writeCharaResult = writeCharaResponseQueue.poll(3000, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                if (writeCharaResult == null || !writeCharaResult) {
                    loge("发送指令失败");
                    throw new Exception("发送指令失败");
                }
            }
        }

        byte[] receiveData = null;
        try {
            receiveData = receiveDataQueue.poll(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(receiveData != null && receiveData.length != 0) {
            return receiveData;
        } else {
            throw new Exception("接收指令错误");
        }
    }

    public synchronized int readBleElectricity() throws Exception {
        if(!isConnected()) {
            throw new Exception("蓝牙未连接");
        }
        BluetoothGattCharacteristic batteryChara = getCharacteristics(getService(UUIDDatabase.UUID_BATTERY_SERVICE), UUIDDatabase.UUID_BATTERY_LEVEL);
        if(batteryChara == null) {
            throw new Exception("设备不支持读电量");
        }
        readBatteryResponseQueue.clear();
        bluetoothGatt.readCharacteristic(batteryChara);
        byte[] bytes = null;
        try {
            bytes = readBatteryResponseQueue.poll(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(bytes != null && bytes.length > 0) {
            return bytes[0] & 0xff;
        } else {
            throw new Exception("读取电量失败");
        }
    }

    /*
     * Callback indicating when GATT client has connected/disconnected to/from a
     * remote GATT server. 蓝牙连接成功或者失败的回调
     */
    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
        super.onConnectionStateChange(gatt, status, newState);
        BluetoothDevice device = gatt.getDevice();
        logi(MessageFormat.format("[onConnectionStateChange] device={0},status={1},newState={2}",
                device.getAddress(), status, newState));

        switch (newState) {
            case BluetoothProfile.STATE_CONNECTING:
                logi("正在连接蓝牙");
                connectState = ConnectState.Connecting;
                break;
            case BluetoothProfile.STATE_CONNECTED:
                logi("蓝牙已连接");
                connectState = ConnectState.Connected;
                boolean result = gatt.discoverServices();
                logi("discoverServices result=" + result);
                // 如果刷服务失败了，那么直接返回失败吧
                if (!result) {
                    bluetoothGatt.disconnect();
                }
                break;
            case BluetoothProfile.STATE_DISCONNECTING:
                logi("蓝牙连接正在断开");
                break;
            case BluetoothProfile.STATE_DISCONNECTED:
                logi("蓝牙连接已经断开");
                connectState = ConnectState.Disconnected;

                // 当连接断开以后，将这个对象关闭
                bluetoothGatt.close();
                // // 然后将这个对象置空
                // mBluetoothGatt = null;

                // 如果在连接过程中，比如正在刷服务的时候，蓝牙断开了，那么通知一下
                connectResultQueue.offer(false);
                disconnectResultQueue.offer(true);

                // 断开连接后，停止阻塞
                enableCharaQueue.offer(false);
                receiveDataQueue.offer(new byte[0]);// offer(null)会报异常
                writeCharaResponseQueue.offer(false);
                break;
        }

        // 回调状态变化
        if(connectStateListener != null) {
            connectStateListener.onConnectStateChange(connectState);
        }
    }

    /*
     * service发现成功或者失败的回调 现在认为只要回调的到这个方法，service发现就成功了
     */
    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        super.onServicesDiscovered(gatt, status);

        BluetoothDevice device = gatt.getDevice();
        logi(MessageFormat.format("[onServicesDiscovered] device={0},status={1}", device.getAddress(),
                status));
        if(status == BluetoothGatt.GATT_SUCCESS) {
            if(printServiceTree) {
                String formatServiceStr = formatBleGattServices(gatt);
                logi(formatServiceStr);
            }
            connectResultQueue.offer(true);
        } else {
            connectResultQueue.offer(false);
        }
    }

    @Override
    public void onPhyUpdate(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        super.onPhyUpdate(gatt, txPhy, rxPhy, status);
        logi("[onPhyUpdate] ");
    }

    @Override
    public void onPhyRead(BluetoothGatt gatt, int txPhy, int rxPhy, int status) {
        super.onPhyRead(gatt, txPhy, rxPhy, status);
        logi("[onPhyRead] ");
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);

        synchronized (gattCallbackObj) {
            BluetoothDevice device = gatt.getDevice();
            String serviceUUID = characteristic.getService().getUuid().toString();
            String charaUUID = characteristic.getUuid().toString();
            byte[] value = characteristic.getValue();
            int charaInstanceId = characteristic.getInstanceId();
            int serviceInstanceId = characteristic.getService().getInstanceId();
            logi(MessageFormat.format("[onCharacteristicRead] device={0},charaUUID={1},charaInstanceId={2},serviceInstanceId={3},status={4},value={5}",
                    device.getAddress(), charaUUID, charaInstanceId, serviceInstanceId, status, ByteUtil.toHex(value)));

            if(UUIDDatabase.UUID_BATTERY_LEVEL.equals(characteristic.getUuid())) {
                readBatteryResponseQueue.offer(value == null ? new byte[0] : value);
            }
        }
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicWrite(gatt, characteristic, status);

        synchronized (gattCallbackObj) {
            BluetoothDevice device = gatt.getDevice();
            String serviceUUID = characteristic.getService().getUuid().toString();
            String uuid = characteristic.getUuid().toString();
            int charaInstanceId = characteristic.getInstanceId();
            int serviceInstanceId = characteristic.getService().getInstanceId();
            logi(MessageFormat.format("[onCharacteristicWrite] device={0},charaUUID={1},charaInstanceId={2},serviceInstanceId={3},status={4}",
                    device.getAddress(), uuid, charaInstanceId, serviceInstanceId, status));

            if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                if (characteristic != null && characteristic.getUuid().equals(writeCharaUUID)) {
                    writeCharaResponseQueue.offer(status == BluetoothGatt.GATT_SUCCESS);
                }
            }
        }
    }

    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);

        synchronized (gattCallbackObj) {
            BluetoothDevice device = gatt.getDevice();
            String serviceUUID = characteristic.getService().getUuid().toString();
            String uuid = characteristic.getUuid().toString();
            byte[] value = characteristic.getValue();
            int charaInstanceId = characteristic.getInstanceId();
            int serviceInstanceId = characteristic.getService().getInstanceId();
            logi(MessageFormat.format("[onCharacteristicChanged] device={0},charaUUID={1},charaInstanceId={2},serviceInstanceId={3},value={4}",
                    device.getAddress(), uuid, charaInstanceId, serviceInstanceId, ByteUtil.toHex(value)));

            if (value != null && characteristic != null && characteristic.getUuid().equals(receiveCharaUUID)) {
                receiveStream.write(value, 0, value.length);

                byte[] response = receiveStream.toByteArray();
                if (receiveRule.isValidResponse(response)) {
                    receiveStream.reset();
                    receiveDataQueue.offer(response);
                }
            }
        }
    }

    @Override
    public void onDescriptorRead(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorRead(gatt, descriptor, status);

        synchronized (gattCallbackObj) {
            BluetoothDevice device = gatt.getDevice();
            String serviceUUID = descriptor.getCharacteristic().getService().getUuid().toString();
            String charaUUID = descriptor.getCharacteristic().getUuid().toString();
            String descUUID = descriptor.getUuid().toString();
            int serviceInstanceId = descriptor.getCharacteristic().getService().getInstanceId();
            int charaInstanceId = descriptor.getCharacteristic().getInstanceId();
            byte[] value = descriptor.getValue();
            logi(MessageFormat.format("[onDescriptorRead] device={0},descUUID={1},charaUUID={2},value={3}",
                    device.getAddress(), descUUID, charaUUID, ByteUtil.toHex(value)));
        }
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
        super.onDescriptorWrite(gatt, descriptor, status);

        synchronized (gattCallbackObj) {
            BluetoothDevice device = gatt.getDevice();
            String serviceUUID = descriptor.getCharacteristic().getService().getUuid().toString();
            String charaUUID = descriptor.getCharacteristic().getUuid().toString();
            String descUUID = descriptor.getUuid().toString();
            int serviceInstanceId = descriptor.getCharacteristic().getService().getInstanceId();
            int charaInstanceId = descriptor.getCharacteristic().getInstanceId();
            byte[] value = descriptor.getValue();
            logi(MessageFormat.format("[onDescriptorRead] device={0},descUUID={1},charaUUID={2}",
                    device.getAddress(), descUUID, charaUUID));

            if (descriptor != null && descriptor.getUuid().equals(UUIDDatabase.UUID_CLIENT_CHARACTERISTIC_CONFIG)) {
                enableCharaQueue.offer(true);
            }
        }
    }

    @Override
    public void onReliableWriteCompleted(BluetoothGatt gatt, int status) {
        super.onReliableWriteCompleted(gatt, status);
    }

    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        super.onReadRemoteRssi(gatt, rssi, status);
    }

    @Override
    public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
        super.onMtuChanged(gatt, mtu, status);
    }

    public BluetoothGattService getService(UUID serviceUUID) {
        if(serviceUUID == null) {
            loge("uuid为空");
            return null;
        }
        if(!isConnected()) {
            loge("蓝牙未连接");
            return null;
        }
        return bluetoothGatt.getService(serviceUUID);
    }

    public BluetoothGattService getService(String uuid) {
        if(TextUtils.isEmpty(uuid)) {
            loge("uuid为空");
            return null;
        }
        if(!isConnected()) {
            loge("蓝牙未连接");
            return null;
        }
        List<BluetoothGattService> servicesList = bluetoothGatt.getServices();
        for(BluetoothGattService service : servicesList) {
            if(uuid.equalsIgnoreCase(service.getUuid().toString())) {
                return service;
            }
        }
        logi("没有找到对应uuid的service");
        return null;
    }

    public BluetoothGattCharacteristic getCharacteristics(BluetoothGattService service, UUID characteristicsUUID) {
        if(service == null) {
            loge("service为空");
            return null;
        }
        if(characteristicsUUID == null) {
            loge("uuid为空");
            return null;
        }
        if(!isConnected()) {
            loge("蓝牙未连接");
            return null;
        }
        return service.getCharacteristic(characteristicsUUID);
    }

    public BluetoothGattDescriptor getDescriptor(BluetoothGattCharacteristic characteristic, UUID descriptorUUID) {
        if(characteristic == null) {
            loge("characteristic为空");
            return null;
        }
        if(descriptorUUID == null) {
            loge("uuid为空");
            return null;
        }
        return characteristic.getDescriptor(descriptorUUID);
    }

    public synchronized boolean setCharacteristicNotification(
            BluetoothGattCharacteristic characteristic, boolean enabled) {
        if(!isConnected()) {
            loge("蓝牙连接已断开");
            return false;
        }
        BluetoothGattDescriptor descriptor = getDescriptor(characteristic, UUIDDatabase.UUID_CLIENT_CHARACTERISTIC_CONFIG);
        if(descriptor == null) {
            loge("descriptor为空");
            return false;
        }
        final byte[] descrSetValue = enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        enableCharaQueue.clear();
        boolean setCharaNotifyResult = false;
        // 如果通道开启失败了重试5次
        int retyrCount = 0;
        do {
            setCharaNotifyResult = bluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        } while(!setCharaNotifyResult && retyrCount < 5);
        if(!setCharaNotifyResult) {
            logi("开启notify通道失败,setCharacteristicNotification return false");
            return false;
        }

        descriptor.setValue(descrSetValue);
        boolean writeDescriporResult = false;
        // 如果通道写入失败了重试5次
        retyrCount = 0;
        do {
            writeDescriporResult = bluetoothGatt.writeDescriptor(descriptor);
            retyrCount++;
        } while(!writeDescriporResult && retyrCount < 5);
        if(!writeDescriporResult) {
            return false;
        }
        Boolean enableCharaResult = null;
        try {
            enableCharaResult = enableCharaQueue.poll(ENABLE_CHARA_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(enableCharaResult == null) {
            logi("开启notify通道%s失败,失败原因：超时", characteristic.getUuid().toString());
            return false;
        } else if(enableCharaResult) {
            logi("开启notify通道%s成功", characteristic.getUuid().toString());
            return true;
        } else {
            logi("开启notify通道%s失败,失败原因：通道开启失败", characteristic.getUuid().toString());
            return false;
        }
    }

    public synchronized boolean setCharacteristicIndication(
            BluetoothGattCharacteristic characteristic, boolean enabled) {
        if(!isConnected()) {
            loge("蓝牙连接已断开");
            return false;
        }
        BluetoothGattDescriptor descriptor = getDescriptor(characteristic, UUIDDatabase.UUID_CLIENT_CHARACTERISTIC_CONFIG);
        if(descriptor == null) {
            loge("descriptor为空");
            return false;
        }
        final byte[] descrSetValue = enabled ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
        enableCharaQueue.clear();
        boolean setCharaNotifyResult = false;
        // 如果通道开启失败了重试5次
        int retyrCount = 0;
        do {
            setCharaNotifyResult = bluetoothGatt.setCharacteristicNotification(characteristic, enabled);
        } while(!setCharaNotifyResult && retyrCount < 5);
        if(!setCharaNotifyResult) {
            logi("开启indicate通道失败,setCharacteristicNotification return false");
            return false;
        }

        descriptor.setValue(descrSetValue);
        boolean writeDescriporResult = bluetoothGatt.writeDescriptor(descriptor);
        // 如果通道写入失败了重试5次
        retyrCount = 0;
        do {
            writeDescriporResult = bluetoothGatt.writeDescriptor(descriptor);
            retyrCount++;
        } while(!writeDescriporResult && retyrCount < 5);
        if(!writeDescriporResult) {
            return false;
        }
        Boolean enableCharaResult = null;
        try {
            enableCharaResult = enableCharaQueue.poll(ENABLE_CHARA_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if(enableCharaResult == null) {
            logi("开启indicate通道%s失败,失败原因：超时", characteristic.getUuid().toString());
            return false;
        } else if(enableCharaResult) {
            logi("开启indicate通道%s成功", characteristic.getUuid().toString());
            return true;
        } else {
            logi("开启indicate通道%s失败,失败原因：通道开启失败", characteristic.getUuid().toString());
            return false;
        }
    }

    public boolean writeCharacteristic(BluetoothGattCharacteristic bgc, byte[] value) {
        if(!isConnected()) {
            loge("蓝牙未连接");
            return false;
        }
        bgc.setWriteType(writeType);
        boolean setValue = bgc.setValue(value);
        if(!setValue) {
            return false;
        }
        boolean writeCharacteristic = bluetoothGatt.writeCharacteristic(bgc);
        logi("写入一包数据:" + ByteUtil.toHex(value));
        return writeCharacteristic;
    }

    /**
     * 将ble service的list格式化
     * @param gatt gatt
     * @return string
     */
    public static final String formatBleGattServices(BluetoothGatt gatt) {
        StringBuilder sb = new StringBuilder();
        sb.append("================start================");
        sb.append("\ndevice mac=").append(gatt.getDevice().getAddress());
        List<BluetoothGattService> services = gatt.getServices();
        for (BluetoothGattService service : services) {
            String type = service.getType() == BluetoothGattService.SERVICE_TYPE_PRIMARY ? "primaryServcie"
                    : "SecondaryService";
            sb.append("\n[service]:"
                    + "\n    UUID=" + service.getUuid().toString()
                    + "\n    InstanceId=" + service.getInstanceId()
                    + "\n    TYPE=" + type);


            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            for(BluetoothGattCharacteristic charact : characteristics) {
                int permission = charact.getPermissions();
                StringBuilder strPermission = new StringBuilder();
                if((permission & BluetoothGattCharacteristic.PERMISSION_READ) != 0) {
                    strPermission.append("R|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED) != 0) {
                    strPermission.append("RE|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM) != 0) {
                    strPermission.append("REM|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_WRITE) != 0) {
                    strPermission.append("W|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED) != 0) {
                    strPermission.append("WE|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM) != 0) {
                    strPermission.append("WEM|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED) != 0) {
                    strPermission.append("WS|");
                }
                if((permission & BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM) != 0) {
                    strPermission.append("WSM|");
                }

                int property = charact.getProperties();
                StringBuilder strProperty = new StringBuilder();
                if((property & BluetoothGattCharacteristic.PROPERTY_BROADCAST) != 0) {
                    strProperty.append("BRO|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    strProperty.append("R|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
                    strProperty.append("WNR|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
                    strProperty.append("W|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                    strProperty.append("N|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                    strProperty.append("I|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE) != 0) {
                    strProperty.append("SW|");
                }
                if((property & BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS) != 0) {
                    strProperty.append("EP|");
                }

                sb.append("\n    [Characteristic]:"
                        + "\n        UUID=" + charact.getUuid()
                        + "\n        InstanceId=" + charact.getInstanceId()
                        + "\n        Permission=" + strPermission.toString()
                        + "\n        Property=" + strProperty.toString());

                List<BluetoothGattDescriptor> descriptors = charact.getDescriptors();
                for (BluetoothGattDescriptor descriptor : descriptors) {
                    permission = charact.getPermissions();
                    strPermission = new StringBuilder();
                    if((permission & BluetoothGattDescriptor.PERMISSION_READ) != 0) {
                        strPermission.append("R|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED) != 0) {
                        strPermission.append("RE|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM) != 0) {
                        strPermission.append("REM|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_WRITE) != 0) {
                        strPermission.append("W|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED) != 0) {
                        strPermission.append("WE|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM) != 0) {
                        strPermission.append("WEM|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED) != 0) {
                        strPermission.append("WS|");
                    }
                    if((permission & BluetoothGattDescriptor.PERMISSION_WRITE_SIGNED_MITM) != 0) {
                        strPermission.append("WSM|");
                    }

                    sb.append("\n        [Descriptor]:"
                            + "\n            UUID=" + descriptor.getUuid()
                            + "\n            Permission=" + strPermission.toString());
                }
            }
        }
        sb.append("\n================end================");
        return sb.toString();
    }

    // getters
    public Context getContext() {
        return context;
    }

    public BluetoothDevice getBluetoothDevice() {
        return bluetoothDevice;
    }

    public BluetoothManager getBluetoothManager() {
        return bluetoothManager;
    }

    public BluetoothAdapter getBluetoothAdapter() {
        return bluetoothAdapter;
    }

    public BluetoothGatt getBluetoothGatt() {
        return bluetoothGatt;
    }

    public UUID getWriteCharaUUID() {
        return writeCharaUUID;
    }

    public UUID getReceiveCharaUUID() {
        return receiveCharaUUID;
    }

    public int getWriteType() {
        return writeType;
    }

    public boolean isReceiveNotify() {
        return isReceiveNotify;
    }

    public ReceiveRule getReceiveRule() {
        return receiveRule;
    }

    public boolean isPrintLog() {
        return printLog;
    }

    public void setPrintServiceTree(boolean printServiceTree) {
        this.printServiceTree = printServiceTree;
    }

    public void setPrintLog(boolean printLog) {
        this.printLog = printLog;
    }

    private void logi(String msg, Object... args) {
        if(!printLog) {
            return;
        }
        if(msg == null) {
            msg = "null";
        }
        if(args != null && args.length != 0) {
            try {
                msg = String.format(msg, args);
            } catch (Exception e) {
                Log.e(TAG, "format log msg error:" + e.getMessage());
            }
        }
        Log.i(TAG, msg);
    }

    private void loge(String msg, Object... args) {
        if(!printLog) {
            return;
        }
        if(msg == null) {
            msg = "null";
        }
        if(args != null && args.length != 0) {
            try {
                msg = String.format(msg, args);
            } catch (Exception e) {
                Log.e(TAG, "format log msg error:" + e.getMessage());
            }
        }
        Log.e(TAG, msg);
    }
}
