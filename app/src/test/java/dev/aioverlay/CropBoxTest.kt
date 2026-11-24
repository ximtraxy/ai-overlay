package dev.aioverlay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CropBoxTest {
    @Test fun padsInside() = assertArrayEquals(intArrayOf(90, 90, 210, 210), cropBox(100f, 100f, 200f, 200f, 1000, 1000, 10))
    @Test fun clampsToImage() = assertArrayEquals(intArrayOf(0, 0, 30, 30), cropBox(-50f, -50f, 20f, 20f, 100, 100, 10))
    @Test fun outsideImageIsNull() = assertNull(cropBox(500f, 500f, 600f, 600f, 100, 100, 10))
}
