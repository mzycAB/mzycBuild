package com.mzyc.build.client.gui;

import com.mzyc.build.LightConfig;
import net.minecraft.text.Text;

/**
 * 二级界面：黄灯夜间随机重分配 —— {@code /flex …} 那一组。
 *
 * <p>两个正交的开关，界面上分开摆：
 * <ul>
 *   <li><b>功能</b>（{@code /flex all on|off}）—— 整夜只掷一次骰，还是每轮持续变化；</li>
 *   <li><b>模式</b>（{@code /flex off} = 熄灭模式 / {@code /flex on} = 点亮模式）——
 *       当前生效的那个按钮置灰，点另一个切过去。</li>
 * </ul>
 * 间隔 / 比例这两个输入框会**带上当前模式与当前间隔**再发指令（{@code flex off time Y percent X} 之类），
 * 免得「只想改个间隔，结果模式也被换掉」。
 */
public class UiFlexScreen extends UiScreen {
    public UiFlexScreen() {
        super(Text.literal("黄灯夜间重分配（flex …）"));
    }

    @Override
    protected boolean isSubScreen() {
        return true;
    }

    @Override
    protected void buildContent() {
        LightConfig values = UiClient.snapshot();
        boolean lightMode = values.getYellowFlexDir() == LightConfig.FLEX_DIR_LIGHT;
        String dir = lightMode ? "on" : "off";
        String timeText = LightConfig.formatCentis(values.getYellowFlexTimeCentis());

        toggleRow(0, "功能（flex all on|off）", values.isYellowFlexOn(), "flex all on", "flex all off");
        choiceRow(1, "熄灭模式（flex off）", "flex off", !lightMode,
                "点亮模式（flex on）", "flex on");
        valueRow(2, "间隔 time（秒）", timeText, "flex " + dir + " time %s", 8);
        valueRow(3, "比例 percent（%）", Integer.toString(values.getYellowFlexPercent()),
                "flex " + dir + " time " + timeText + " percent %s", 4);
        valueRow(4, "偏差 flex（秒）",
                LightConfig.formatCentis(values.getYellowFlexJitterCentis()), "flex flex %s", 8);
        valueRow(5, "递增 grow（秒，可负）",
                LightConfig.formatCentis(values.getYellowFlexGrowCentis()), "flex grow %s", 8);
    }
}
