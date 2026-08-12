package com.example.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.db.AppDatabase
import com.example.utils.IndianFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class QuickActionsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            customBalanceText: String? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.widget_quick_actions)

            // Intent for New Sale
            val saleIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("ACTION", "NEW_SALE")
            }
            val salePendingIntent = PendingIntent.getActivity(
                context, 101, saleIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_new_sale, salePendingIntent)

            // Intent for New Purchase
            val purchaseIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("ACTION", "NEW_PURCHASE")
            }
            val purchasePendingIntent = PendingIntent.getActivity(
                context, 102, purchaseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_new_purchase, purchasePendingIntent)

            // Intent for Check Balance
            val balanceIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("ACTION", "CHECK_BALANCE")
            }
            val balancePendingIntent = PendingIntent.getActivity(
                context, 103, balanceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_btn_check_balance, balancePendingIntent)

            if (customBalanceText != null) {
                views.setTextViewText(R.id.widget_balance_text, customBalanceText)
                appWidgetManager.updateAppWidget(appWidgetId, views)
            } else {
                // Fetch balance from Room asynchronously
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val db = AppDatabase.getDatabase(context)
                        val total = db.accountingDao().getCashAndBankBalanceSync()
                        val text = "Cash & Bank: ${IndianFormatter.formatRupee(total)}"
                        views.setTextViewText(R.id.widget_balance_text, text)
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    } catch (e: Exception) {
                        appWidgetManager.updateAppWidget(appWidgetId, views)
                    }
                }
            }
        }

        fun updateAllWidgets(context: Context, balanceText: String) {
            val appWidgetManager = AppWidgetManager.getInstance(context)
            val componentName = ComponentName(context, QuickActionsWidgetProvider::class.java)
            val ids = appWidgetManager.getAppWidgetIds(componentName)
            for (id in ids) {
                updateAppWidget(context, appWidgetManager, id, balanceText)
            }
        }
    }
}
