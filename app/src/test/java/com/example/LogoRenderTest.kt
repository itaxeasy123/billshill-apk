package com.example

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the iTaxEasy mark so it can be checked against branding/itaxeasy-logo.png.
 *
 * The mark ships as the artwork itself (drawable-nodpi/ic_itaxeasy_logo.png) rather than as
 * a VectorDrawable. It was hand-converted first, because no SVG rasteriser was available on
 * the build machine — but folding the source's nested transforms by hand put the three
 * stacked bars a fraction too thick and closed the gaps between them, which is visible at
 * letterhead size. The artwork is 366x156, and the invoice prints the mark about 34pt tall,
 * i.e. ~142px at 300dpi, so the bitmap is at full resolution where it is used and the
 * conversion buys nothing.
 *
 * Output: app/build/outputs/roborazzi/itaxeasy_logo.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi")
class LogoRenderTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `itaxeasy vector logo renders`() {
        composeRule.setContent {
            Box(
                Modifier
                    .background(Color(0xFFBFBFBF))
                    .padding(12.dp)
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_itaxeasy_logo),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 281.dp, height = 120.dp)
                )
            }
        }
        composeRule.onRoot().captureRoboImage("build/outputs/roborazzi/itaxeasy_logo.png")
    }
}
