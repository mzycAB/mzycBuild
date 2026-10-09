package com.mzyc.build.client.gui;

import com.mzyc.build.LightConfig;
import net.minecraft.text.Text;

/**
 * 二级界面：黄灯 / 通用 —— {@code /light …} 那一组。
 *
 * <ul>
 *   <li>{@code /light percent X} —— 夜晚点亮比例（%）</li>
 *   <li>{@code /light light X} —— 灯光亮度（0~15）</li>
 *   <li>{@code /light nightdelay Y} —— 天黑随机点亮秒数（旧名 {@code /light delay}）</li>
 *   <li>{@code /light daydelay Y} —— 天亮随机熄灭秒数</li>
 *   <li>{@code /light on|off} —— 黄灯总开关</li>
 * </ul>
 */
public class UiLightScreen extends UiScreen {
    public UiLightScreen() {
        super(Text.literal("黄灯 / 通用（light …）"));
    }

    @Override
    protected boolean isSubScreen() {
        return true;
    }

    @Override
    protected void buildContent() {
        LightConfig values = UiClient.snapshot();
        valueRow(0, "夜晚点亮比例 percent（%）",
                Integer.toString(values.getPercent()), "light percent %s", 4);
        valueRow(1, "灯光亮度 light（0~15）",
                Integer.toString(values.getLightLevel()), "light light %s", 3);
        valueRow(2, "天黑随机点亮秒数 nightdelay",
                Integer.toString(values.getDelaySeconds()), "light nightdelay %s", 4);
        valueRow(3, "天亮随机熄灭秒数 daydelay",
                Integer.toString(values.getDayDelaySeconds()), "light daydelay %s", 4);
        toggleRow(4, "黄灯总开关（light on|off）", values.isYellowOn(), "light on", "light off");
    }
}
