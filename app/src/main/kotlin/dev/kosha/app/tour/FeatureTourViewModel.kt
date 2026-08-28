package dev.kosha.app.tour

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.core.database.settings.SettingsRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The one-time feature tour. Shown exactly once, right after onboarding
 * finishes, on the very first install — [SettingsRepository.setFeatureTourDone]
 * is the only thing that can make [dev.kosha.app.MainActivity] stop showing
 * it, and nothing else in the app ever clears that flag back.
 */
@HiltViewModel
class FeatureTourViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _page = MutableStateFlow(0)
    val page: StateFlow<Int> = _page.asStateFlow()

    fun next() {
        if (_page.value < TourPage.entries.lastIndex) _page.value += 1
    }

    fun back() {
        if (_page.value > 0) _page.value -= 1
    }

    /** Skip and "finish on the last page" both end the tour the same way. */
    fun finish(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.setFeatureTourDone()
            onDone()
        }
    }
}
