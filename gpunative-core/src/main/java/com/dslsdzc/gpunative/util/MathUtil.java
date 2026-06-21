package com.dslsdzc.gpunative.util;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Math utilities for matrix and vector operations.
 * All matrix operations use column-major order (OpenGL convention).
 */
public final class MathUtil {
    private MathUtil() {}

    /**
     * Create a perspective projection matrix.
     */
    public static float[] perspective(float fovY, float aspect, float near, float far) {
        float f = (float) (1.0 / Math.tan(fovY * 0.5));
        float rangeInv = 1.0f / (near - far);

        return new float[]{
            f / aspect, 0, 0, 0,
            0, f, 0, 0,
            0, 0, (far + near) * rangeInv, -1,
            0, 0, 2 * far * near * rangeInv, 0
        };
    }

    /**
     * Create an orthographic projection matrix.
     */
    public static float[] orthographic(float left, float right, float bottom, float top, float near, float far) {
        float rangeX = 1.0f / (right - left);
        float rangeY = 1.0f / (top - bottom);
        float rangeZ = 1.0f / (far - near);

        return new float[]{
            2 * rangeX, 0, 0, 0,
            0, 2 * rangeY, 0, 0,
            0, 0, -2 * rangeZ, 0,
            -(right + left) * rangeX, -(top + bottom) * rangeY, -(far + near) * rangeZ, 1
        };
    }

    /**
     * Create a look-at view matrix.
     */
    public static float[] lookAt(float eyeX, float eyeY, float eyeZ,
                                  float centerX, float centerY, float centerZ,
                                  float upX, float upY, float upZ) {
        float fX = centerX - eyeX, fY = centerY - eyeY, fZ = centerZ - eyeZ;
        float fLen = (float) Math.sqrt(fX * fX + fY * fY + fZ * fZ);
        fX /= fLen; fY /= fLen; fZ /= fLen;

        float sX = fY * upZ - fZ * upY;
        float sY = fZ * upX - fX * upZ;
        float sZ = fX * upY - fY * upX;
        float sLen = (float) Math.sqrt(sX * sX + sY * sY + sZ * sZ);
        sX /= sLen; sY /= sLen; sZ /= sLen;

        float uX = sY * fZ - sZ * fY;
        float uY = sZ * fX - sX * fZ;
        float uZ = sX * fY - sY * fX;

        return new float[]{
            sX, uX, -fX, 0,
            sY, uY, -fY, 0,
            sZ, uZ, -fZ, 0,
            -sX * eyeX - sY * eyeY - sZ * eyeZ,
            -uX * eyeX - uY * eyeY - uZ * eyeZ,
            fX * eyeX + fY * eyeY + fZ * eyeZ, 1
        };
    }

    /**
     * Multiply two 4x4 matrices (column-major).
     */
    public static float[] mat4Mul(float[] a, float[] b) {
        float[] result = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + row] * b[col * 4 + k];
                }
                result[col * 4 + row] = sum;
            }
        }
        return result;
    }

    /**
     * Invert a 4x4 matrix (column-major). Returns null if singular.
     */
    public static float[] mat4Invert(float[] m) {
        float a00 = m[0], a01 = m[1], a02 = m[2], a03 = m[3];
        float a10 = m[4], a11 = m[5], a12 = m[6], a13 = m[7];
        float a20 = m[8], a21 = m[9], a22 = m[10], a23 = m[11];
        float a30 = m[12], a31 = m[13], a32 = m[14], a33 = m[15];

        float b00 = a00 * a11 - a01 * a10;
        float b01 = a00 * a12 - a02 * a10;
        float b02 = a00 * a13 - a03 * a10;
        float b03 = a01 * a12 - a02 * a11;
        float b04 = a01 * a13 - a03 * a11;
        float b05 = a02 * a13 - a03 * a12;
        float b06 = a20 * a31 - a21 * a30;
        float b07 = a20 * a32 - a22 * a30;
        float b08 = a20 * a33 - a23 * a30;
        float b09 = a21 * a32 - a22 * a31;
        float b10 = a21 * a33 - a23 * a31;
        float b11 = a22 * a33 - a23 * a32;

        float det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06;
        if (Math.abs(det) < 1e-15f) return null;

        float invDet = 1.0f / det;
        return new float[]{
            (a11 * b11 - a12 * b10 + a13 * b09) * invDet,
            (-a01 * b11 + a02 * b10 - a03 * b09) * invDet,
            (a31 * b05 - a32 * b04 + a33 * b03) * invDet,
            (-a21 * b05 + a22 * b04 - a23 * b03) * invDet,

            (-a10 * b11 + a12 * b08 - a13 * b07) * invDet,
            (a00 * b11 - a02 * b08 + a03 * b07) * invDet,
            (-a30 * b05 + a32 * b02 - a33 * b01) * invDet,
            (a20 * b05 - a22 * b02 + a23 * b01) * invDet,

            (a10 * b10 - a11 * b08 + a13 * b06) * invDet,
            (-a00 * b10 + a01 * b08 - a03 * b06) * invDet,
            (a30 * b04 - a31 * b02 + a33 * b00) * invDet,
            (-a20 * b04 + a21 * b02 - a23 * b00) * invDet,

            (-a10 * b09 + a11 * b07 - a12 * b06) * invDet,
            (a00 * b09 - a01 * b07 + a02 * b06) * invDet,
            (-a30 * b03 + a31 * b01 - a32 * b00) * invDet,
            (a20 * b03 - a21 * b01 + a22 * b00) * invDet
        };
    }

    /**
     * Create an identity matrix.
     */
    public static float[] mat4Identity() {
        return new float[]{
            1, 0, 0, 0,
            0, 1, 0, 0,
            0, 0, 1, 0,
            0, 0, 0, 1
        };
    }

    /**
     * Creates a frustum from a combined view-projection matrix.
     * Planes are in the form: dot(normal, point) + distance = 0.
     * Returns 6 planes: [left, right, bottom, top, near, far]
     * Each plane is 4 floats: (nx, ny, nz, d)
     */
    public static float[] extractFrustumPlanes(float[] viewProj) {
        float[] planes = new float[24];

        // Left plane (column 3 + column 0)
        planes[0] = viewProj[3] + viewProj[0];
        planes[1] = viewProj[7] + viewProj[4];
        planes[2] = viewProj[11] + viewProj[8];
        planes[3] = viewProj[15] + viewProj[12];

        // Right plane (column 3 - column 0)
        planes[4] = viewProj[3] - viewProj[0];
        planes[5] = viewProj[7] - viewProj[4];
        planes[6] = viewProj[11] - viewProj[8];
        planes[7] = viewProj[15] - viewProj[12];

        // Bottom plane (column 3 + column 1)
        planes[8] = viewProj[3] + viewProj[1];
        planes[9] = viewProj[7] + viewProj[5];
        planes[10] = viewProj[11] + viewProj[9];
        planes[11] = viewProj[15] + viewProj[13];

        // Top plane (column 3 - column 1)
        planes[12] = viewProj[3] - viewProj[1];
        planes[13] = viewProj[7] - viewProj[5];
        planes[14] = viewProj[11] - viewProj[9];
        planes[15] = viewProj[15] - viewProj[13];

        // Near plane (column 3 + column 2)
        planes[16] = viewProj[3] + viewProj[2];
        planes[17] = viewProj[7] + viewProj[6];
        planes[18] = viewProj[11] + viewProj[10];
        planes[19] = viewProj[15] + viewProj[14];

        // Far plane (column 3 - column 2)
        planes[20] = viewProj[3] - viewProj[2];
        planes[21] = viewProj[7] - viewProj[6];
        planes[22] = viewProj[11] - viewProj[10];
        planes[23] = viewProj[15] - viewProj[14];

        // Normalize all planes
        for (int i = 0; i < 6; i++) {
            float lenSq = planes[i * 4] * planes[i * 4]
                + planes[i * 4 + 1] * planes[i * 4 + 1]
                + planes[i * 4 + 2] * planes[i * 4 + 2];
            if (lenSq < 1e-15f) continue;
            float invLen = 1.0f / (float) Math.sqrt(lenSq);
            planes[i * 4] *= invLen;
            planes[i * 4 + 1] *= invLen;
            planes[i * 4 + 2] *= invLen;
            planes[i * 4 + 3] *= invLen;
        }

        return planes;
    }

    /**
     * Check if an AABB is inside a frustum defined by 6 planes.
     */
    public static boolean frustumIntersectsAABB(float[] frustumPlanes, float minX, float minY, float minZ,
                                                  float maxX, float maxY, float maxZ) {
        for (int i = 0; i < 6; i++) {
            float nx = frustumPlanes[i * 4];
            float ny = frustumPlanes[i * 4 + 1];
            float nz = frustumPlanes[i * 4 + 2];
            float d = frustumPlanes[i * 4 + 3];

            float px = nx >= 0 ? maxX : minX;
            float py = ny >= 0 ? maxY : minY;
            float pz = nz >= 0 ? maxZ : minZ;

            if (nx * px + ny * py + nz * pz + d < 0) {
                return false;
            }
        }
        return true;
    }
}
