package com.itlgl.android.writenotifyble;

public enum ConnectState {
	/**
	 * 无状态
	 */
	None, 
	/**
	 * 正在连接中
	 */
	Connecting, 
	/**
	 * 已经连接
	 */
	Connected, 
	/**
	 * 连接失败
	 */
	ConnectFailed, 
	/**
	 * 连接断开
	 */
	Disconnected;
}
