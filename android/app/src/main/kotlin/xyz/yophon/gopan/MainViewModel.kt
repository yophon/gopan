package xyz.yophon.gopan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import xyz.yophon.gopan.core.auth.AuthState
import xyz.yophon.gopan.core.auth.SessionStore
import xyz.yophon.gopan.core.prefs.ThemeMode
import xyz.yophon.gopan.core.prefs.UserPreferencesRepository
import javax.inject.Inject

/** 外观偏好:深浅主题 + 是否动态取色 */
data class Appearance(val theme: ThemeMode, val dynamicColor: Boolean)

/** Activity 级 ViewModel:只暴露登录态与主题,供 MainActivity 决定画什么 */
@HiltViewModel
class MainViewModel @Inject constructor(
    sessionStore: SessionStore,
    preferences: UserPreferencesRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = sessionStore.state

    /** null = 偏好还没读出来(冷启动最初几毫秒);Eagerly:Activity 重建时直接拿到上次的值,不再出现 null */
    val appearance: StateFlow<Appearance?> = preferences.preferences
        .map<_, Appearance?> { Appearance(it.theme, it.dynamicColor) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}
