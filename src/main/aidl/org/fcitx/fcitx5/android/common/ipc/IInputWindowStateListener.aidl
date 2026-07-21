package org.fcitx.fcitx5.android.common.ipc;

// 与 fcitx5-android 主项目 lib/common 的 IInputWindowStateListener.aidl 保持一致。
// 由主项目在输入法软键盘窗口显示/隐藏时回调，用于驱动插件悬浮按钮显隐。
oneway interface IInputWindowStateListener {

    void onInputWindowShown();

    void onInputWindowHidden();
}
