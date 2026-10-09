package com.mzyc.build.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * 所有设置界面的公共底座：排版、行控件工厂、返回 / 刷新 / 关闭、以及「退出才汇总反馈」的收尾。
 *
 * <h2>界面分级</h2>
 * 一级（{@link UiMainScreen}）只放几个分类按钮，各自进二级：
 * <ul>
 *   <li>{@link UiLightScreen} —— 黄灯 / 通用（{@code /light …}）</li>
 *   <li>{@link UiRedScreen} —— 红灯（{@code /hlight …}）</li>
 *   <li>{@link UiFlexScreen} —— 黄灯夜间重分配（{@code /flex …}）</li>
 *   <li>{@link UiSwitchScreen} —— 总开关与杂项</li>
 * </ul>
 * 二级界面的「返回 / ESC」都回一级；一级界面的「关闭 / ESC」才真的退出整个 UI。
 *
 * <p><b>界面对象不复用</b>：MC 的 {@code Screen} 只在**第一次** {@code setScreen} 时调 {@code init()}，
 * 之后再 {@code setScreen} 同一个对象只会重排 Tab 导航、不会重建控件 —— 复用就会把上次那批
 * 带着旧数值的控件原样端上来。所以 {@link #mainScreen()} / 子界面构造一律**新造对象**。
 * 当前界面收到新快照时走 {@link #applySnapshot()}（{@code clearAndInit()}，那条路会真的重建控件）。
 *
 * <h2>排版</h2>
 * 中间一条 300px 的内容列，一行一件事：
 * <pre>
 *   [ 行名（标题）           ] [ 输入框 ] [ 设置 ]
 * </pre>
 * 开关类不用输入框，直接一个整行按钮，按钮文字里带着当前是「开」还是「关」。
 * 底部固定一行：{@code 刷新}（重新拉一份设置）+ 二级界面的 {@code 返回} + {@code 关闭}。
 *
 * <p><b>不放任何操作反馈小字</b>：界面上的文字只有两类 —— 界面标题 + 行名（都属标题性质），
 * 也不给任何点击动作加提示语；成功 / 失败只在退出 UI 时往聊天框回一条汇总（见 {@link UiClient#finishSession}）。
 */
public abstract class UiScreen extends Screen {
    /** 内容列宽度。 */
    protected static final int CONTENT_WIDTH = 300;
    /** 每行的高度（含行距）。 */
    protected static final int ROW_STEP = 22;
    /** 第一行的 y。 */
    protected static final int FIRST_ROW_Y = 36;

    private final List<RowLabel> labels = new ArrayList<>();

    protected UiScreen(Text title) {
        super(title);
    }

    /** 子类在这里摆自己的控件；用 {@link #rowY(int)} 排纵坐标。 */
    protected abstract void buildContent();

    /** 是不是二级界面（二级的「返回 / ESC」回一级界面）。 */
    protected boolean isSubScreen() {
        return false;
    }

    /**
     * 一级界面（主菜单）—— 每次都**新造**一个，理由见类注释（界面对象一旦用过就不能再初始化控件了）。
     */
    protected UiScreen mainScreen() {
        return new UiMainScreen();
    }

    @Override
    protected final void init() {
        this.labels.clear();
        buildContent();

        int x = contentX();
        int y = this.height - 26;
        addDrawableChild(ButtonWidget.builder(Text.literal("刷新"), b -> UiClient.send(""))
                .dimensions(x, y, 80, 20).build());
        if (isSubScreen()) {
            addDrawableChild(ButtonWidget.builder(Text.literal("返回"), b -> go(mainScreen()))
                    .dimensions(x + CONTENT_WIDTH - 168, y, 80, 20).build());
        }
        addDrawableChild(ButtonWidget.builder(Text.literal("关闭"), b -> closeUi())
                .dimensions(x + CONTENT_WIDTH - 80, y, 80, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
        for (RowLabel label : this.labels) {
            context.drawTextWithShadow(this.textRenderer, label.text(), label.x(), label.y(), 0xC0C0C0);
        }
        super.render(context, mouseX, mouseY, delta);
    }

    /** 界面开着时世界照常走（要能看着灯变），指令也能立刻生效。 */
    @Override
    public boolean shouldPause() {
        return false;
    }

    /** ESC：二级界面回一级，一级界面才真的退出整个 UI。 */
    @Override
    public void close() {
        if (isSubScreen()) {
            go(mainScreen());
        } else {
            closeUi();
        }
    }

    @Override
    public void removed() {
        if (!UiClient.navigating) {
            UiClient.finishSession(this.client);
        }
    }

    /** 服务端回传了新快照：把最新数值刷进输入框 / 按钮（{@code clearAndInit} 会真的重建控件）。 */
    void applySnapshot() {
        if (this.client != null) {
            this.clearAndInit();
        }
    }

    // ---------------------------------------------------------------- 排版工具

    /** 内容列左边界。 */
    protected int contentX() {
        return (this.width - CONTENT_WIDTH) / 2;
    }

    /** 第 {@code index} 行的 y（从 {@link #FIRST_ROW_Y} 起，每行 {@link #ROW_STEP}）。 */
    protected int rowY(int index) {
        return FIRST_ROW_Y + index * ROW_STEP;
    }

    /**
     * 一行「行名 + 输入框 + 设置」。
     *
     * @param index         第几行
     * @param label         行名（标题性质的文字，不是反馈）
     * @param value         输入框初值（当前设置值）
     * @param commandFormat 指令模板，{@code %s} 处填输入框里的内容，如 {@code "light percent %s"}
     */
    protected TextFieldWidget valueRow(int index, String label, String value, String commandFormat) {
        return valueRow(index, label, value, commandFormat, 12);
    }

    /** 同上，可指定输入框最大长度。 */
    protected TextFieldWidget valueRow(int index, String label, String value,
                                       String commandFormat, int maxLength) {
        int x = contentX();
        int y = rowY(index);
        TextFieldWidget field = new TextFieldWidget(this.textRenderer,
                x + CONTENT_WIDTH - 146, y, 76, 20, Text.literal(label));
        field.setMaxLength(maxLength);
        field.setText(value);
        addDrawableChild(field);
        addDrawableChild(ButtonWidget.builder(Text.literal("设置"),
                        b -> UiClient.send(commandFormat.formatted(field.getText().trim())))
                .dimensions(x + CONTENT_WIDTH - 66, y, 66, 20).build());
        addLabel(label, x, y + 6);
        return field;
    }

    /** 一个整行开关按钮：文字里带当前状态，点一下切到另一边。 */
    protected void toggleRow(int index, String label, boolean on, String onCommand, String offCommand) {
        int x = contentX();
        addDrawableChild(ButtonWidget.builder(Text.literal(label + "：" + (on ? "开" : "关")),
                        b -> UiClient.send(on ? offCommand : onCommand))
                .dimensions(x, rowY(index), CONTENT_WIDTH, 20).build());
    }

    /** 一个整行按钮（点下去就发一条指令）。 */
    protected void actionRow(int index, String label, String command) {
        int x = contentX();
        addDrawableChild(ButtonWidget.builder(Text.literal(label), b -> UiClient.send(command))
                .dimensions(x, rowY(index), CONTENT_WIDTH, 20).build());
    }

    /**
     * 一行两个按钮的「二选一」：当前那个置灰（= 现在生效的就是它），点另一个切过去。
     * 两个按钮的文字里都带着自己的指令名，所以不必再加一行小字。
     *
     * @param leftActive 左边那个是不是当前生效的
     */
    protected void choiceRow(int index,
                             String leftText, String leftCommand, boolean leftActive,
                             String rightText, String rightCommand) {
        int x = contentX();
        int y = rowY(index);
        int half = (CONTENT_WIDTH - 8) / 2;
        ButtonWidget left = ButtonWidget.builder(Text.literal(leftText), b -> UiClient.send(leftCommand))
                .dimensions(x, y, half, 20).build();
        ButtonWidget right = ButtonWidget.builder(Text.literal(rightText), b -> UiClient.send(rightCommand))
                .dimensions(x + CONTENT_WIDTH - half, y, half, 20).build();
        left.active = !leftActive;
        addDrawableChild(left);
        addDrawableChild(right);
    }

    /** 加一行行名（画在控件下面一层，所以先画它、后画控件）。 */
    protected void addLabel(String text, int x, int y) {
        this.labels.add(new RowLabel(text, x, y));
    }

    // ---------------------------------------------------------------- 跳转

    /** 跳到另一个界面（这次跳转不算「退出 UI」，所以不汇总反馈）。 */
    protected void go(Screen target) {
        UiClient.navigating = true;
        try {
            this.client.setScreen(target);
        } finally {
            UiClient.navigating = false;
        }
    }

    /** 关掉整个 UI（退出时由 {@link UiClient#finishSession} 回一条汇总）。 */
    protected void closeUi() {
        this.client.setScreen(null);
    }

    /** 行名。 */
    private record RowLabel(String text, int x, int y) {
    }
}
