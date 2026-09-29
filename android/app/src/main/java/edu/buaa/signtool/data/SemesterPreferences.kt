package edu.buaa.signtool.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

class SemesterPreferences(private val context: Context) {
    private val baselineKey = stringPreferencesKey("semester_baseline_date")

    val semesterBaseline: Flow<LocalDate> = context.settingsDataStore.data.map { values ->
        values[baselineKey]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: LocalDate.now()
    }

    suspend fun saveSemesterBaseline(date: LocalDate) {
        context.settingsDataStore.edit { values ->
            values[baselineKey] = date.toString()
        }
    }
}
