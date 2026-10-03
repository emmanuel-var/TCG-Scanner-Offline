package com.tcgscanner.offline.ads

import android.app.Activity
import android.app.Application
import com.google.android.gms.ads.MobileAds
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Google User Messaging Platform (UMP) consent plus AdMob start-up.
 *
 * Order, as Google requires: ask for consent first (the form only appears where the law demands it, e.g. EEA/UK),
 * and only when [ConsentInformation.canRequestAds] is true initialise the Mobile Ads SDK and let the UI request a banner.
 * Consent state from a previous launch lets ads start immediately while the update runs.
 */
class AdsConsent(private val app: Application) {
    private val info: ConsentInformation = UserMessagingPlatform.getConsentInformation(app)
    private val sdkStarted = AtomicBoolean(false)

    private val _canRequestAds = MutableStateFlow(false)
    /** true once the user's consent state allows ad requests; the banner is only composed then. */
    val canRequestAds: StateFlow<Boolean> = _canRequestAds

    private val _privacyOptionsRequired = MutableStateFlow(false)
    /** true when the user must be able to reopen the consent choices (Settings shows the button). */
    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired

    /** Call from the Activity every launch. Safe to call again after a configuration change. */
    fun gather(activity: Activity) {
        val params = ConsentRequestParameters.Builder().build()
        info.requestConsentInfoUpdate(
            activity, params,
            {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { refresh() }
            },
            { refresh() } // offline or a failed request: keep whatever consent state is stored
        )
        refresh() // state stored by a previous session
    }

    /** Re-opens the privacy options form from Settings. */
    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) { refresh() }
    }

    private fun refresh() {
        _privacyOptionsRequired.value =
            info.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (info.canRequestAds()) {
            startSdk()
            _canRequestAds.value = true
        }
    }

    private fun startSdk() {
        if (!sdkStarted.compareAndSet(false, true)) return
        // Google recommends initialising off the main thread.
        Thread { MobileAds.initialize(app) {} }.start()
    }
}
