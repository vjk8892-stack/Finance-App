package dev.kosha.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import dev.kosha.feature.ingest.sms.CaptureNotifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.kosha.app.navigation.KoshaAppScaffold
import dev.kosha.app.onboarding.OnboardingScreen
import dev.kosha.app.tour.FeatureTourScreen
import dev.kosha.core.database.settings.KoshaSettings
import dev.kosha.core.database.settings.SettingsRepository
import dev.kosha.core.designsystem.theme.KoshaTheme
import dev.kosha.core.designsystem.token.KoshaColors
import dev.kosha.core.designsystem.token.KoshaType
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

@HiltViewModel
class MainViewModel @Inject constructor(
    settingsRepository: SettingsRepository,
) : ViewModel() {
    val settings: StateFlow<KoshaSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Ring 0 lock state — locked until authenticated when the lock is on. */
    val locked = MutableStateFlow(false)

    private var backgroundedAt: Long = 0

    fun onEnterBackground() {
        backgroundedAt = System.currentTimeMillis()
    }

    fun onEnterForeground() {
        val s = settings.value ?: return
        if (!s.appLockEnabled) return
        val away = System.currentTimeMillis() - backgroundedAt
        if (backgroundedAt == 0L || away >= s.appLockTimeoutMillis) {
            locked.value = true
        }
    }

    fun markLockedOnLaunchIfNeeded() {
        if (settings.value?.appLockEnabled == true) locked.value = true
    }

    fun unlock() {
        locked.value = false
    }
}

@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            KoshaTheme {
                val settings by viewModel.settings.collectAsState()
                val locked by viewModel.locked.collectAsState()
                val current = settings

                when {
                    current == null -> Box(Modifier.fillMaxSize()) {}

                    locked -> LockScreen(onUnlock = { showBiometricPrompt() })

                    !current.onboardingDone -> OnboardingScreen(onDone = {})

                    // Shown exactly once, right after onboarding, on the
                    // very first install — never again once the flag is set.
                    !current.featureTourDone -> FeatureTourScreen(onDone = {})

                    else -> KoshaAppScaffold(
                        startAction = intent?.action,
                        startLedgerDay = intent
                            ?.getStringExtra(CaptureNotifier.EXTRA_TRANSACTION_DAY),
                    )
                }

                // First composition with lock enabled → gate render (spec B4)
                androidx.compose.runtime.LaunchedEffect(current?.appLockEnabled) {
                    if (current?.appLockEnabled == true && !locked) {
                        // only on fresh process start
                        if (savedInstanceState == null && !unlockedOnce) {
                            viewModel.markLockedOnLaunchIfNeeded()
                            showBiometricPrompt()
                        }
                    }
                }
            }
        }
    }

    private var unlockedOnce = false

    override fun onStart() {
        super.onStart()
        if (unlockedOnce) {
            viewModel.onEnterForeground()
            if (viewModel.locked.value) showBiometricPrompt()
        }
    }

    override fun onStop() {
        super.onStop()
        viewModel.onEnterBackground()
    }

    private fun showBiometricPrompt() {
        val executor = ContextCompat.getMainExecutor(this)
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    unlockedOnce = true
                    viewModel.unlock()
                }
            },
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.lock_prompt_title))
            .setSubtitle(getString(R.string.lock_prompt_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_WEAK or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(info)
    }
}

@androidx.compose.runtime.Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TextButton(onClick = onUnlock) {
            Text(
                text = stringResource(R.string.lock_prompt_title),
                style = KoshaType.InsightSerif,
                color = KoshaColors.OffWhiteMuted,
            )
        }
    }
}
