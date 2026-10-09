package com.mzyc.build.client.gui;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * 一级界面：只放分类按钮，避免所有设置挤在一屏里。
 *
 * <p>同样的东西在聊天框里也都能直接打（{@code /light …}、{@code /hlight …}、{@code /flex …}），
 * 界面只是把那些指令变成按钮和输入框。
 *
 * <p>每次打开都新造一个（见 {@link UiScreen} 类注释里「界面对象不复用」的说明）。
 */
public class UiMainScreen extends UiScreen {
    public UiMainScreen() {
        super(Text.literal("mzycBuild 设置"));
    }

    @Override
    protected void buildContent() {
        int x = contentX();
        classify(0, "黄灯 / 通用（light …）", new UiLightScreen());
        classify(1, "红灯（hlight …）", new UiRedScreen());
        classify(2, "黄灯夜间重分配（flex …）", new UiFlexScreen());
        classify(3, "总开关与杂项", new UiSwitchScreen());
        // 出厂默认：一步到位，不用进子界面
        addDrawableChild(ButtonWidget.builder(Text.literal("恢复出厂默认（light define default）"),
                        b -> UiClient.send("light define default"))
                .dimensions(x, rowY(4), CONTENT_WIDTH, 20).build());
    }

    private void classify(int index, String label, UiScreen child) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> go(child))
                .dimensions(contentX(), rowY(index), CONTENT_WIDTH, 20).build());
    }
}
