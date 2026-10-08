package com.mzyc.build.client;

import com.mzyc.build.MzycBuild;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

public class MzycBuildClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // 逐玩家绘制灯光方块：拿着它才画得出来
        BlockEntityRendererRegistry.register(MzycBuild.LIGHT_BLOCK_ENTITY, LightBlockEntityRenderer::new);
        BlockEntityRendererRegistry.register(MzycBuild.RED_LIGHT_BLOCK_ENTITY, RedLightBlockEntityRenderer::new);
    }
}
