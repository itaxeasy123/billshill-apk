package com.example.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.LavenderContainer
import com.example.ui.theme.RoyalPurplePrimary

sealed class NavItem(val route: String, val title: String, val icon: ImageVector) {
    object Dashboard : NavItem("dashboard", "Dashboard", Icons.Default.Dashboard)
    object Vouchers : NavItem("vouchers", "Vouchers", Icons.Default.ReceiptLong)
    object Scan : NavItem("scan", "Scan", Icons.Default.QrCodeScanner)
    object Reports : NavItem("reports", "Reports", Icons.Default.Assessment)
    object Settings : NavItem("settings", "Settings", Icons.Default.Settings)
}

@Composable
fun AccountingBottomNavBar(
    selectedRoute: String,
    onTabSelected: (String) -> Unit
) {
    // 1. Memory Optimization: Cache the list to prevent allocation on every recomposition
    val items = remember {
        listOf(
            NavItem.Dashboard,
            NavItem.Vouchers,
            NavItem.Scan,
            NavItem.Reports,
            NavItem.Settings
        )
    }

    // 2. Responsive Breakpoint Calculations
    val configuration = LocalConfiguration.current
    val screenWidthDp = configuration.screenWidthDp

    val isTablet = screenWidthDp >= 600
    val isCompactScreen = screenWidthDp < 360

    // Dynamic dimensions based on form factor
    val navBarHeight = if (isTablet) 80.dp else 68.dp
    val iconSize = if (isTablet) 28.dp else 22.dp
    val scanIconPadding = if (isTablet) 8.dp else 5.dp
    val fontSize = if (isTablet) 13.sp else if (isCompactScreen) 10.sp else 11.sp

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .testTag("accounting_bottom_nav_bar")
    ) {
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                modifier = Modifier
                    .widthIn(max = 840.dp) // Centers and constrains navigation width on tablets
                    .height(navBarHeight)
            ) {
                items.forEach { item ->
                    val isSelected = selectedRoute == item.route
                    val isCenterScan = item == NavItem.Scan

                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onTabSelected(item.route) },
                        alwaysShowLabel = true, // Force labeled mode to accommodate localized terminology
                        icon = {
                            if (isCenterScan) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = RoyalPurplePrimary,
                                    contentColor = Color.White,
                                    shadowElevation = 4.dp
                                ) {
                                    Icon(
                                        imageVector = item.icon,
                                        contentDescription = item.title,
                                        modifier = Modifier
                                            .padding(scanIconPadding)
                                            .size(iconSize)
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = item.icon,
                                    contentDescription = item.title,
                                    modifier = Modifier.size(iconSize)
                                )
                            }
                        },
                        label = {
                            Text(
                                text = item.title,
                                fontSize = fontSize,
                                fontWeight = if (isSelected || isCenterScan) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                softWrap = false,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = RoyalPurplePrimary,
                            selectedTextColor = RoyalPurplePrimary,
                            indicatorColor = LavenderContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    }
}