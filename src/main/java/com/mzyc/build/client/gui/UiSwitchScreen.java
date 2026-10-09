package com.mzyc.build.client.gui;

import com.mzyc.build.LightConfig;
import net.minecraft.text.Text;

/**
 * 二级界面：总开关与杂项。
 *
 * <ul>
 *   <li>{@code /light on|off} —— 黄灯总开关</li>
 *   <li>{@code /light h on|off} —— 红灯总开关</li>
 *   <li>{@code /lightall on|off} —— 「全灭」总闸（{@code /light -f}、{@code /light all}、
 *       {@code /alllight} 都是它的别名）：关掉后黄红当场全灭，无视一切设置</li>
 *   <li>{@code /flexable on|off} —— 夜里黄灯脱离一切设定纯随机乱闪</li>
 *   <li>{@code /light define default} —— 一键还原出厂默认</li>
 * </ul>
 */
public class UiSwitchScreen extends UiScreen {
    public UiSwitchScreen() {
        super(Text.literal("总开关与杂项"));
    }

    @Override
    protected boolean isSubScreen() {
        return true;
    }

    @Override
    protected void buildContent() {
        LightConfig values = UiClient.snapshot();
        toggleRow(0, "黄灯（light on|off）", values.isYellowOn(), "light on", "light off");
        toggleRow(1, "红灯（light h on|off）", values.isRedOn(), "light h on", "light h off");
        toggleRow(2, "全灭（lightall on|off）", values.isForceOff(), "lightall on", "lightall off");
        toggleRow(3, "纯随机乱闪（flexable on|off）", values.isYellowFlexableOn(),
                "flexable on", "flexable off");
        actionRow(4, "恢复出厂默认（light define default）", "light define default");
    }
}
