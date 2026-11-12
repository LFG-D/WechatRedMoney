package com.xros.wechatredmoney;

/** 微信界面相关常量，微信版本更新后集中在此适配。 */
public final class WeChatUi {

    public static final String PACKAGE = "com.tencent.mm";

    public static final String NOTIFY_KEYWORD = "[微信红包]";
    public static final String PACKET_TEXT = "微信红包";

    public static final String CLS_LAUNCHER = "LauncherUI";
    public static final String CLS_CHATTING = "ChattingUI";
    // 兼容 LuckyMoneyReceiveUI / LuckyMoneyNewReceiveUI / LuckyMoneyNotHookReceiveUI 等
    public static final String CLS_LUCKY = "LuckyMoney";
    public static final String CLS_RECEIVE = "ReceiveUI";
    public static final String CLS_DETAIL = "DetailUI";
    public static final String CLS_BEFORE_DETAIL = "BeforeDetailUI";

    public static final String[] OPEN_TEXTS = {"開", "开", "Open", "拆红包"};
    public static final String[] RECEIVED_TEXTS = {"已领取", "已被领完", "已过期", "Opened", "Expired"};
    public static final String[] EMPTY_TEXTS = {"手慢了", "红包派完了", "已超过24小时", "该红包已过期", "已被领完"};

    private WeChatUi() {
    }
}
