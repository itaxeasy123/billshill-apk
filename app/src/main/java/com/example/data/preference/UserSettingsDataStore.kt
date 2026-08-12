package com.example.data.preference

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.model.BusinessType
import com.example.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserSettingsDataStore(private val context: Context) {

    companion object {
        val KEY_ENABLE_INVENTORY = booleanPreferencesKey("enable_inventory")
        val KEY_BUSINESS_TYPE = stringPreferencesKey("business_type")
        val KEY_BUSINESS_NAME = stringPreferencesKey("business_name")
        val KEY_THEME_MODE = stringPreferencesKey("theme_mode")
        val KEY_DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val KEY_AUTO_CLOUD_BACKUP = booleanPreferencesKey("auto_cloud_backup")
        val KEY_BACKUP_FREQUENCY = stringPreferencesKey("backup_frequency")
        val KEY_LAST_BACKUP_TIME = stringPreferencesKey("last_backup_time")
        val KEY_AUTO_RECONCILIATION = booleanPreferencesKey("auto_reconciliation")
        val KEY_LAST_RECONCILIATION_TIME = stringPreferencesKey("last_reconciliation_time")
        val KEY_RECONCILIATION_DISCREPANCY_COUNT = intPreferencesKey("reconciliation_discrepancy_count")
    }

    val autoReconciliationFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_RECONCILIATION] ?: true
    }

    val lastReconciliationTimeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_RECONCILIATION_TIME] ?: "Never"
    }

    suspend fun saveAutoReconciliationSettings(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_RECONCILIATION] = enabled
        }
    }

    suspend fun updateLastReconciliationTime(timestampStr: String, discrepancyCount: Int) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_RECONCILIATION_TIME] = timestampStr
            preferences[KEY_RECONCILIATION_DISCREPANCY_COUNT] = discrepancyCount
        }
    }

    val autoCloudBackupFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_AUTO_CLOUD_BACKUP] ?: true
    }

    val backupFrequencyFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_BACKUP_FREQUENCY] ?: "DAILY"
    }

    val lastBackupTimeFlow: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[KEY_LAST_BACKUP_TIME] ?: "Never"
    }

    suspend fun saveCloudBackupSettings(enabled: Boolean, frequency: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_AUTO_CLOUD_BACKUP] = enabled
            preferences[KEY_BACKUP_FREQUENCY] = frequency
        }
    }

    suspend fun updateLastBackupTime(timestampStr: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_LAST_BACKUP_TIME] = timestampStr
        }
    }

    val enableInventoryFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_ENABLE_INVENTORY] ?: true
    }

    val businessTypeFlow: Flow<BusinessType> = context.dataStore.data.map { preferences ->
        val typeStr = preferences[KEY_BUSINESS_TYPE] ?: BusinessType.TRADING.name
        try {
            BusinessType.valueOf(typeStr)
        } catch (e: Exception) {
            BusinessType.TRADING
        }
    }

    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data.map { preferences ->
        val modeStr = preferences[KEY_THEME_MODE] ?: ThemeMode.LIGHT.name
        try {
            ThemeMode.valueOf(modeStr)
        } catch (e: Exception) {
            ThemeMode.LIGHT
        }
    }

    val dynamicColorFlow: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[KEY_DYNAMIC_COLOR] ?: false
    }

    suspend fun saveInventoryMode(enableInventory: Boolean, businessType: BusinessType) {
        context.dataStore.edit { preferences ->
            preferences[KEY_ENABLE_INVENTORY] = enableInventory
            preferences[KEY_BUSINESS_TYPE] = businessType.name
        }
    }

    suspend fun saveBusinessName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[KEY_BUSINESS_NAME] = name
        }
    }

    suspend fun saveThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[KEY_THEME_MODE] = themeMode.name
        }
    }

    suspend fun saveDynamicColor(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[KEY_DYNAMIC_COLOR] = enabled
        }
    }
}
