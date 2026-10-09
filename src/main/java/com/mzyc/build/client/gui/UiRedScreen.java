package com.mzyc.build.client.gui;

import com.mzyc.build.LightConfig;
import net.minecraft.text.Text;

/**
 * 二级界面：红灯 —— {@code /hlight …} 那一组（所有时间都支持 2 位小数）。
 *
 * <ul>
 *   <li>{@code /hlight time on|off X} —— 亮起 / 熄灭时间（秒）</li>
 *   <li>{@code /hlight slow on|off X} —— 两端明暗渐变时间（秒）</li>
 *   <li>{@code /hlight flex on|off [X]} —— 每块是否按坐标错开闪 + 错开时长（秒）</li>
 *   <li>{@code /light h on|off} —— 红灯总开关</li>
 * </ul>
 */
public class UiRedScreen extends UiScreen {
    public UiRedScreen() {
        super(Text.literal("红灯（hlight …）"));
    }

    @Override
    protected boolean isSubScreen() {
        return true;
    }

    @Override
    protected void buildContent() {
        LightConfig values = UiClient.snapshot();
        valueRow(0, "亮起时间 time on（秒）",
                LightConfig.formatCentis(values.getRedTimeOnCentis()), "hlight time on %s", 8);
        valueRow(1, "熄灭时间 time off（秒）",
                LightConfig.formatCentis(values.getRedTimeOffCentis()), "hlight time off %s", 8);
        valueRow(2, "灭→亮渐变 slow on（秒）",
                LightConfig.formatCentis(values.getRedSlowOnCentis()), "hlight slow on %s", 8);
        valueRow(3, "亮→灭渐变 slow off（秒）",
                LightConfig.formatCentis(values.getRedSlowOffCentis()), "hlight slow off %s", 8);
        // 改错开时长会顺手把「错开」打开（和 /hlight flex on X 的语义一致）
        valueRow(4, "错开时长 flex（秒）",
                LightConfig.formatCentis(values.getRedFlexCentis()), "hlight flex on %s", 8);
        toggleRow(5, "错开（hlight flex on|off）", values.isRedFlexOn(),
                "hlight flex on", "hlight flex off");
        toggleRow(6, "红灯总开关（light h on|off）", values.isRedOn(), "light h on", "light h off");
    }
}
