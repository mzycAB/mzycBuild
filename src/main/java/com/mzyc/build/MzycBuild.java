package com.mzyc.build;

import com.mzyc.build.block.LightBlock;
import com.mzyc.build.block.LightBlockEntity;
import com.mzyc.build.block.RedLightBlock;
import com.mzyc.build.block.RedLightBlockEntity;
import com.mzyc.build.command.LightCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.piston.PistonBehavior;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * mzycBuild —— 灯光方块。
 *
 * <p>灯光方块行为等价于「结构空位 + 灯光」：
 * <ul>
 *   <li>没有碰撞箱，玩家可以直接穿过去；</li>
 *   <li>不渲染到区块网格里，改由方块实体渲染器逐玩家绘制成「整方块黄色线框」
 *       （12 条棱、中间透明），因此只有手上拿着灯光方块的玩家看得见；</li>
 *   <li>同理，只有拿着它的玩家能选中并破坏它；</li>
 *   <li>不可被替换，所以它所在的位置放不下别的方块；</li>
 *   <li>入夜（世界时间 13000~23000）每块独立掷骰（比例 {@code /light percent X}，默认 30%），
 *       掷中后再等 0~{@code /light delay Y} 秒的随机延迟才亮（默认上限 60 秒），亮就整夜亮着，天亮自动熄灭；</li>
 *   <li>亮起时的亮度可调（{@code /light light X}，0~15，默认 12），和原版光源方块 {@code level} 属性同一套语义；</li>
 *   <li>开 {@code /flex on time Y [percent X]} 后，从「入夜第一次亮灯」起每 Y 秒洗牌一次：
 *       给了 {@code percent X} ⇒ 在还亮着的灯里每轮熄灭 X%（只灭不亮、越点越暗，剩得不够一盏时全灭）；
 *       没给 ⇒ 对全部黄灯按 {@code /light percent} 重新分配点亮 / 熄灭（有灭也有亮）；
 *       {@code /flex flex X} 给每块 ±X 秒偏差，{@code /flex grow X} 让每轮间隔递增 / 递减（可为负）。</li>
 * </ul>
 *
 * <p>另有<b>红色灯光方块</b>（{@code mzycbuild:red_light_block}）：可见性 / 穿行 / 占位行为与黄方块完全一致，
 * 线框是红色；但发光规律固定 —— 夜里按「亮起时间 / 熄灭时间」循环（{@code /hlight time on|off X}，默认各 2.00 秒），
 * 两端各带可调的明暗渐变（{@code /hlight slow on|off X}，默认各 0.10 秒），相位锁在世界刻数上，
 * 所以默认全世界所有红方块同步闪烁；用 {@code /hlight flex on X} 可改成「每块各自错开」。
 *
 * <p><b>总开关</b>：{@code /light on|off} 只管黄灯，{@code /light h on|off} 只管红灯，
 * {@code /light -f on|off} 是「全灭」——**无论什么情况**，{@code -f off} 当场熄灭全场黄灯和红灯，
 * 直到 {@code -f on}。所有写指令改完都会立即刷新全场已加载的灯光方块。
 */
public class MzycBuild implements ModInitializer {
    public static final String MOD_ID = "mzycbuild";
    public static final Logger LOGGER = LoggerFactory.getLogger("mzycBuild");

    public static final LightBlock LIGHT_BLOCK = Registry.register(
            Registries.BLOCK,
            id("light_block"),
            new LightBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.YELLOW)
                    .strength(0.2F, 1.0F)
                    .sounds(BlockSoundGroup.STONE)
                    .noCollision()
                    .nonOpaque()
                    .luminance(LightBlock::getLuminance)
                    .pistonBehavior(PistonBehavior.BLOCK)));

    public static final BlockItem LIGHT_BLOCK_ITEM = Registry.register(
            Registries.ITEM,
            id("light_block"),
            new BlockItem(LIGHT_BLOCK, new Item.Settings()));

    public static final BlockEntityType<LightBlockEntity> LIGHT_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            id("light_block"),
            BlockEntityType.Builder.create(LightBlockEntity::new, LIGHT_BLOCK).build(null));

    /** 红色灯光方块：行为同黄方块，但夜里固定闪烁（默认亮 2 秒 / 灭 2 秒，两端各 0.1 秒渐变）。 */
    public static final RedLightBlock RED_LIGHT_BLOCK = Registry.register(
            Registries.BLOCK,
            id("red_light_block"),
            new RedLightBlock(AbstractBlock.Settings.create()
                    .mapColor(MapColor.RED)
                    .strength(0.2F, 1.0F)
                    .sounds(BlockSoundGroup.STONE)
                    .noCollision()
                    .nonOpaque()
                    .luminance(RedLightBlock::getLuminance)
                    .pistonBehavior(PistonBehavior.BLOCK)));

    public static final BlockItem RED_LIGHT_BLOCK_ITEM = Registry.register(
            Registries.ITEM,
            id("red_light_block"),
            new BlockItem(RED_LIGHT_BLOCK, new Item.Settings()));

    public static final BlockEntityType<RedLightBlockEntity> RED_LIGHT_BLOCK_ENTITY = Registry.register(
            Registries.BLOCK_ENTITY_TYPE,
            id("red_light_block"),
            BlockEntityType.Builder.create(RedLightBlockEntity::new, RED_LIGHT_BLOCK).build(null));

    public static Identifier id(String path) {
        return new Identifier(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.BUILDING_BLOCKS)
                .register(entries -> {
                    entries.add(LIGHT_BLOCK_ITEM);
                    entries.add(RED_LIGHT_BLOCK_ITEM);
                });

        // /light [on|off] [h on|off] [-f on|off] percent|light|delay、/hlight flex|time|slow、/flex …（共用同一份世界设置）
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                LightCommand.register(dispatcher));

        // 跟踪「已加载的灯光方块实体」：指令改完设置要能当场刷新全场（含不 tick 的边界区块）
        LightIndex.register();

        // 服务端兜底：没拿对应灯光方块的玩家即使发了破坏包也拆不掉它
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (state.isOf(LIGHT_BLOCK)) {
                return LightBlock.isHoldingLightBlock(player);
            }
            if (state.isOf(RED_LIGHT_BLOCK)) {
                return RedLightBlock.isHoldingRedLightBlock(player);
            }
            return true;
        });

        LOGGER.info("[mzycBuild] light blocks registered (defaults: {}% lit / level {} / delay {}s"
                        + " || red on {}s / off {}s / slow-on {}s / slow-off {}s / flex {} X={}s"
                        + " || yellow-flex {} every {}s / percent {} / jitter {}s / grow {}s)"
                        + " commands /light [on|off] [h on|off] [-f on|off] percent|light|delay"
                        + " + /hlight flex|time|slow + /flex",
                LightConfig.DEFAULT_PERCENT, LightConfig.DEFAULT_LIGHT_LEVEL, LightConfig.DEFAULT_DELAY_SECONDS,
                LightConfig.formatCentis(LightConfig.DEFAULT_RED_TIME_ON_CENTIS),
                LightConfig.formatCentis(LightConfig.DEFAULT_RED_TIME_OFF_CENTIS),
                LightConfig.formatCentis(LightConfig.DEFAULT_RED_SLOW_ON_CENTIS),
                LightConfig.formatCentis(LightConfig.DEFAULT_RED_SLOW_OFF_CENTIS),
                LightConfig.DEFAULT_RED_FLEX_ON ? "on" : "off",
                LightConfig.formatCentis(LightConfig.DEFAULT_RED_FLEX_CENTIS),
                LightConfig.DEFAULT_YELLOW_FLEX_ON ? "on" : "off",
                LightConfig.formatCentis(LightConfig.DEFAULT_YELLOW_FLEX_TIME_CENTIS),
                LightConfig.YELLOW_FLEX_PERCENT_UNSET,
                LightConfig.formatCentis(LightConfig.DEFAULT_YELLOW_FLEX_JITTER_CENTIS),
                LightConfig.formatCentis(LightConfig.DEFAULT_YELLOW_FLEX_GROW_CENTIS));
    }
}
