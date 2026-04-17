package com.om.offlineai.data.db.dao

import androidx.room.*
import com.om.offlineai.data.db.entities.AppSettings
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 1")
    fun observe(): Flow<AppSettings?>

    @Query("SELECT * FROM app_settings WHERE id = 1")
    suspend fun get(): AppSettings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(settings: AppSettings)

    @Query("UPDATE app_settings SET modelPath = :path, modelName = :name WHERE id = 1")
    suspend fun updateModel(path: String, name: String)
}
