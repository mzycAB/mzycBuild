package com.mzyc.build.client;

import net.minecraft.client.render.VertexConsumer;
import org.joml.Matrix4f;

/**
 * 「整方块线框」的公共绘制代码，给黄 / 红两个灯光方块渲染器共用。
 *
 * <p>画的是一个铺满整格的立方体线框：8 个顶点，每个顶点连出 X / Y / Z 三条棱，
 * 一共 12 条棱，中间完全透明。等价于把原版选中黑框换成彩色、并且常驻。
 *
 * <p>顶点坐标统一**内缩 {@link #INSET} 格**：手持物品时原版会另外画一个选中黑框，
 * 两者落在同一深度上会闪烁（z-fighting）。内缩 0.002 格在屏幕上约一个像素，
 * 肉眼基本看不出来，却足以分开深度。
 */
final class Wireframe {
    /** 线框极小地内缩一点，避开和原版选中框打架。 */
    private static final float INSET = 0.002F;

    /** 立方体 8 个顶点，下标位含义：bit0=X、bit1=Y、bit2=Z。 */
    private static final float[][] CORNERS = new float[8][3];

    /** 12 条棱：X 方向 4 条、Y 方向 4 条、Z 方向 4 条。 */
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7}, // X 方向
            {0, 2}, {1, 3}, {4, 6}, {5, 7}, // Y 方向
            {0, 4}, {1, 5}, {2, 6}, {3, 7}, // Z 方向
    };

    static {
        float lo = INSET;
        float hi = 1.0F - INSET;
        for (int i = 0; i < 8; i++) {
            CORNERS[i][0] = (i & 1) == 0 ? lo : hi;
            CORNERS[i][1] = (i & 2) == 0 ? lo : hi;
            CORNERS[i][2] = (i & 4) == 0 ? lo : hi;
        }
    }

    private Wireframe() {
    }

    /** 画一整个立方体线框，颜色由调用方给（r/g/b 会按亮度系数缩放后再传进来）。 */
    static void drawCube(VertexConsumer buffer, Matrix4f matrix, float red, float green, float blue, float alpha) {
        for (int[] edge : EDGES) {
            drawLine(buffer, matrix, CORNERS[edge[0]], CORNERS[edge[1]], red, green, blue, alpha);
        }
    }

    /** 画一条线段：两次顶点（两端点），法线用线段方向，供着色器用。 */
    private static void drawLine(VertexConsumer buffer, Matrix4f matrix, float[] from, float[] to,
                                 float red, float green, float blue, float alpha) {
        float dx = to[0] - from[0];
        float dy = to[1] - from[1];
        float dz = to[2] - from[2];
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > 1.0E-5F) {
            dx /= length;
            dy /= length;
            dz /= length;
        }
        buffer.vertex(matrix, from[0], from[1], from[2])
                .color(red, green, blue, alpha)
                .normal(dx, dy, dz)
                .next();
        buffer.vertex(matrix, to[0], to[1], to[2])
                .color(red, green, blue, alpha)
                .normal(dx, dy, dz)
                .next();
    }
}
