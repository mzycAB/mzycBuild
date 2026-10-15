package com.mzyc.build.client;

import com.mzyc.build.MzycBuild;
import com.mzyc.build.client.gui.UiClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.minecraft.client.render.RenderLayer;

public class MzycBuildClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 逐玩家绘制灯光方块：拿着它才画得出来
        BlockEntityRendererRegistry.register(MzycBuild.LIGHT_BLOCK_ENTITY, LightBlockEntityRenderer::new);
        BlockEntityRendererRegistry.register(MzycBuild.RED_LIGHT_BLOCK_ENTITY, RedLightBlockEntityRenderer::new);

        // 方块贴图（textures/block/*.png）与物品栏图标是同一套「透明底 + 1px 线」的画法。
        // 本模组方块本体是 BlockRenderType.INVISIBLE（游戏内由上面的 BER 画线框，不读方块贴图），
        // 但**第三方建筑模组的放置预览**（轻松建造 / Effortless Building 之类）渲染的是方块模型
        // → 会读到 textures/block/*.png：不声明 cutout 的话透明像素会被画成黑底。
        // 注意 1.20.4 的方块模型 JSON **没有** render_type 字段（那是 1.21.2+ 才加进来的），
        // 所以渲染层只能在这里用代码声明。
        BlockRenderLayerMap.INSTANCE.putBlocks(RenderLayer.getCutout(),
                MzycBuild.LIGHT_BLOCK, MzycBuild.RED_LIGHT_BLOCK);

        // /mb help 的设置界面：收服务端快照、把 UI 上的按钮动作发回去
        UiClient.register();
    }
}
