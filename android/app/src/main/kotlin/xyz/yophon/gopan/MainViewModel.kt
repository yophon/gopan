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

/** Activity 级 ViewModel:只暴露登录态与主题,供 MainActivity 决定画什么 */
@HiltViewModel
class MainViewModel @Inject constructor(
    sessionStore: SessionStore,
    preferences: UserPreferencesRepository,
) : ViewModel() {

    val authState: StateFlow<AuthState> = sessionStore.state

    val themeMode: StateFlow<ThemeMode> = preferences.preferences
        .map { it.theme }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)
}
