package com.itlgl.android.writenotifyble;

/**
 * 连接状态监听接口
 */
public interface ConnectStateListener {
    /**
     * 当连接状态变化时，回调此方法
     * @param connectState 新的连接状态
     */
    void onConnectStateChange(ConnectState connectState);
}
