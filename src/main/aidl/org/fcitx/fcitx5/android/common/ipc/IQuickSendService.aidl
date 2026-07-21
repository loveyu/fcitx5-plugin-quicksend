package org.fcitx.fcitx5.android.common.ipc;

import org.fcitx.fcitx5.android.common.ipc.IInputWindowStateListener;

// 与 fcitx5-android 主项目 lib/common 的 IQuickSendService.aidl 保持一致。
// 由主项目 QuickSendService 实现，插件绑定后用于发送按键/文本，
// 并订阅输入法软键盘窗口的可见性变化。
interface IQuickSendService {

    /** Commit text at the current cursor. cursor=-1 means end of text. */
    boolean commitText(String text, int cursor);

    /** Send a single down+up key pair with an explicit metaState mask. */
    boolean sendKeyDownUpKey(int keyCode, int metaState);

    /** Send a full modifier+key combination (modifiers down, key, modifiers up). */
    boolean sendKeyCombination(int keyCode, boolean alt, boolean ctrl, boolean shift, boolean meta);

    /** 订阅输入法软键盘窗口可见性变化。 */
    void registerInputWindowStateListener(IInputWindowStateListener listener);

    /** 注销输入法软键盘窗口可见性监听。 */
    void unregisterInputWindowStateListener(IInputWindowStateListener listener);
}
