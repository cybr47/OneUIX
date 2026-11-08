package io.github.soclear.oneuix.ui

import android.app.Application
import android.app.BroadcastOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.soclear.oneuix.AudioEraserTileService
import io.github.soclear.oneuix.R
import io.github.soclear.oneuix.common.IgnoreUnknownKeysJson
import io.github.soclear.oneuix.common.FodConfig
import io.github.soclear.oneuix.common.Package
import io.github.soclear.oneuix.common.ONE_UI_VERSION
import io.github.soclear.oneuix.common.Preference
import io.github.soclear.oneuix.ui.category.Category
import io.github.soclear.oneuix.ui.category.CategoryAppInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.InputStream
import java.io.OutputStream

class SettingViewModel(private val application: Application) : ViewModel() {
    val categoryAppInfoList: StateFlow<List<CategoryAppInfo>> = flow {
        val packageManager = application.packageManager
        val fallbackIcon = application.applicationInfo
            .loadIcon(packageManager)
            .toBitmap()
            .asImageBitmap()
        val categoryAppInfoList = Category.entries.filter { it != Category.Other }.map { category ->
            val applicationInfo = try {
                packageManager.getApplicationInfo(category.packageName, 0)
            } catch (_: PackageManager.NameNotFoundException) {
                null
            }
            val label = if (category == Category.NFC) application.getString(R.string.nfc_service)
                else applicationInfo?.loadLabel(packageManager)?.toString()
                ?: category.packageName
            val icon = applicationInfo?.loadIcon(packageManager)?.toBitmap()?.asImageBitmap()
                ?: fallbackIcon
            (applicationInfo != null) to CategoryAppInfo(category, label, icon)
        }.partition { it.first }
            .let { (installed, missing) -> (installed + missing).map { it.second } }
        emit(categoryAppInfoList)
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val dataStore: DataStore<Preference> = application.dataStore

    val preference = dataStore.data.onEach { preference ->
        withContext(Dispatchers.IO) {
            AudioEraserTileService.setEnabled(
                application,
                ONE_UI_VERSION >= 90000 && preference.systemUI.qs.audioEraserQsTileEnabled
            )
        }
    }.stateIn(
        scope = viewModelScope, started = SharingStarted.Eagerly, initialValue = Preference()
    )

    fun updateData(nextPreference: (currentPreference: Preference) -> Preference) {
        viewModelScope.launch {
            dataStore.updateData {
                nextPreference(it)
            }
        }
    }

    fun updateFod(transform: (FodConfig) -> FodConfig) {
        viewModelScope.launch {
            dataStore.updateData { current ->
                current.copy(systemUI = current.systemUI.copy(fod = transform(current.systemUI.fod)))
            }
            notifyFodChanged()
        }
    }

    private fun notifyFodChanged() {
        // FOD is a One UI 9 feature. Do not fall back to anonymous broadcasts on
        // older platforms; the hook can still read settings at process startup.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val options = BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle()
        application.sendBroadcast(Intent(FodConfig.changeAction).setPackage(Package.SYSTEMUI), null, options)
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun backupTo(output: OutputStream) = withContext(Dispatchers.IO) {
        IgnoreUnknownKeysJson.encodeToStream(
            Preference.serializer(),
            dataStore.data.first(),
            output
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun restoreFrom(input: InputStream) = withContext(Dispatchers.IO) {
        val restored = IgnoreUnknownKeysJson.decodeFromStream<Preference>(input)
        dataStore.updateData { restored }
        notifyFodChanged()
    }
}
