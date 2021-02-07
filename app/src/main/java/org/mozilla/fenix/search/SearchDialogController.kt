/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.search

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import androidx.annotation.VisibleForTesting
import androidx.appcompat.app.AlertDialog
import androidx.navigation.NavController
import mozilla.components.browser.session.Session
import mozilla.components.browser.session.SessionManager
import mozilla.components.browser.state.search.SearchEngine
import mozilla.components.browser.state.store.BrowserStore
import mozilla.components.support.ktx.kotlin.isUrl
import org.mozilla.fenix.BrowserDirection
import org.mozilla.fenix.HomeActivity
import org.mozilla.fenix.R
import org.mozilla.fenix.components.metrics.Event
import org.mozilla.fenix.components.metrics.MetricController
import org.mozilla.fenix.components.metrics.MetricsUtils
import org.mozilla.fenix.crashes.CrashListActivity
import org.mozilla.fenix.ext.navigateSafe
import org.mozilla.fenix.settings.SupportUtils
import org.mozilla.fenix.utils.Settings

/**
 * An interface that handles the view manipulation of the Search, triggered by the Interactor
 */
@Suppress("TooManyFunctions")
interface SearchController {
    fun handleUrlCommitted(url: String)
    fun handleEditingCancelled()
    fun handleTextChanged(text: String)
    fun handleUrlTapped(url: String)
    fun handleSearchTermsTapped(searchTerms: String)
    fun handleSearchShortcutEngineSelected(searchEngine: SearchEngine)
    fun handleClickSearchEngineSettings()
    fun handleExistingSessionSelected(session: Session)
    fun handleExistingSessionSelected(tabId: String)
    fun handleSearchShortcutsButtonClicked()
    fun handleCameraPermissionsNeeded()
}

@Suppress("TooManyFunctions", "LongParameterList")
class SearchDialogController(
    private val activity: HomeActivity,
    private val sessionManager: SessionManager,
    private val store: BrowserStore,
    private val fragmentStore: SearchFragmentStore,
    private val navController: NavController,
    private val settings: Settings,
    private val metrics: MetricController,
    private val dismissDialog: () -> Unit,
    private val clearToolbarFocus: () -> Unit,
    private val focusToolbar: () -> Unit
) : SearchController {

    override fun handleUrlCommitted(url: String) {
        when (url) {
            "about:crashes" -> {
                // The list of past crashes can be accessed via "settings > about", but desktop and
                // fennec users may be used to navigating to "about:crashes". So we intercept this here
                // and open the crash list activity instead.
                activity.startActivity(Intent(activity, CrashListActivity::class.java))
            }
            "about:addons" -> {
                val directions =
                    SearchDialogFragmentDirections.actionGlobalAddonsManagementFragment()
                navController.navigateSafe(R.id.searchDialogFragment, directions)
            }
            "moz://a" -> openSearchOrUrl(SupportUtils.getMozillaPageUrl(SupportUtils.MozillaPage.MANIFESTO))
            else -> if (url.isNotBlank()) {
                openSearchOrUrl(url)
            } else {
                dismissDialog()
            }
        }
    }

    private fun openSearchOrUrl(url: String) {
        clearToolbarFocus()

        val searchEngine = fragmentStore.state.searchEngineSource.searchEngine

        activity.openToBrowserAndLoad(
            searchTermOrURL = url,
            newTab = fragmentStore.state.tabId == null,
            from = BrowserDirection.FromSearchDialog,
            engine = searchEngine
        )

        val event = if (url.isUrl() || searchEngine == null) {
            Event.EnteredUrl(false)
        } else {
            val searchAccessPoint = when (fragmentStore.state.searchAccessPoint) {
                Event.PerformedSearch.SearchAccessPoint.NONE -> Event.PerformedSearch.SearchAccessPoint.ACTION
                else -> fragmentStore.state.searchAccessPoint
            }

            searchAccessPoint?.let { sap ->
                MetricsUtils.createSearchEvent(
                    searchEngine,
                    store,
                    sap
                )
            }
        }

        event?.let { metrics.track(it) }
    }

    override fun handleEditingCancelled() {
        clearToolbarFocus()
    }

    override fun handleTextChanged(text: String) {
        // Display the search shortcuts on each entry of the search fragment (see #5308)
        val textMatchesCurrentUrl = fragmentStore.state.url == text
        val textMatchesCurrentSearch = fragmentStore.state.searchTerms == text

        fragmentStore.dispatch(SearchFragmentAction.UpdateQuery(text))
        fragmentStore.dispatch(
            SearchFragmentAction.ShowSearchShortcutEnginePicker(
                (textMatchesCurrentUrl || textMatchesCurrentSearch || text.isEmpty()) &&
                        settings.shouldShowSearchShortcuts
            )
        )
        fragmentStore.dispatch(
            SearchFragmentAction.AllowSearchSuggestionsInPrivateModePrompt(
                text.isNotEmpty() &&
                        activity.browsingModeManager.mode.isPrivate &&
                        !settings.shouldShowSearchSuggestionsInPrivate &&
                        !settings.showSearchSuggestionsInPrivateOnboardingFinished
            )
        )
    }

    override fun handleUrlTapped(url: String) {
        clearToolbarFocus()

        activity.openToBrowserAndLoad(
            searchTermOrURL = url,
            newTab = fragmentStore.state.tabId == null,
            from = BrowserDirection.FromSearchDialog
        )

        metrics.track(Event.EnteredUrl(false))
    }

    override fun handleSearchTermsTapped(searchTerms: String) {
        clearToolbarFocus()

        val searchEngine = fragmentStore.state.searchEngineSource.searchEngine

        activity.openToBrowserAndLoad(
            searchTermOrURL = searchTerms,
            newTab = fragmentStore.state.tabId == null,
            from = BrowserDirection.FromSearchDialog,
            engine = searchEngine,
            forceSearch = true
        )

        val searchAccessPoint = when (fragmentStore.state.searchAccessPoint) {
            Event.PerformedSearch.SearchAccessPoint.NONE -> Event.PerformedSearch.SearchAccessPoint.SUGGESTION
            else -> fragmentStore.state.searchAccessPoint
        }

        if (searchAccessPoint != null && searchEngine != null) {
            MetricsUtils.createSearchEvent(
                searchEngine,
                store,
                searchAccessPoint
            )?.apply {
                metrics.track(this)
            }
        }
    }

    override fun handleSearchShortcutEngineSelected(searchEngine: SearchEngine) {
        focusToolbar()
        fragmentStore.dispatch(SearchFragmentAction.SearchShortcutEngineSelected(searchEngine))
        val isCustom = searchEngine.type == SearchEngine.Type.CUSTOM
        metrics.track(Event.SearchShortcutSelected(searchEngine, isCustom))
    }

    override fun handleSearchShortcutsButtonClicked() {
        val isOpen = fragmentStore.state.showSearchShortcuts
        fragmentStore.dispatch(SearchFragmentAction.ShowSearchShortcutEnginePicker(!isOpen))
    }

    override fun handleClickSearchEngineSettings() {
        clearToolbarFocus()
        val directions = SearchDialogFragmentDirections.actionGlobalSearchEngineFragment()
        navController.navigateSafe(R.id.searchDialogFragment, directions)
    }

    override fun handleExistingSessionSelected(session: Session) {
        clearToolbarFocus()
        sessionManager.select(session)
        activity.openToBrowser(
            from = BrowserDirection.FromSearchDialog
        )
    }

    override fun handleExistingSessionSelected(tabId: String) {
        val session = sessionManager.findSessionById(tabId)
        if (session != null) {
            handleExistingSessionSelected(session)
        }
    }

    /**
     * Creates and shows an [AlertDialog] when camera permissions are needed.
     *
     * In versions above M, [AlertDialog.BUTTON_POSITIVE] takes the user to the app settings. This
     * intent only exists in M and above. Below M, [AlertDialog.BUTTON_POSITIVE] routes to a SUMO
     * help page to find the app settings.
     *
     * [AlertDialog.BUTTON_NEGATIVE] dismisses the dialog.
     */
    override fun handleCameraPermissionsNeeded() {
        val dialog = buildDialog()
        dialog.show()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    fun buildDialog(): AlertDialog.Builder {
        return AlertDialog.Builder(activity).apply {
            val spannableText = SpannableString(
                activity.resources.getString(R.string.camera_permissions_needed_message)
            )
            setMessage(spannableText)
            setNegativeButton(R.string.camera_permissions_needed_negative_button_text) { _, _ ->
                dismissDialog()
            }
            setPositiveButton(R.string.camera_permissions_needed_positive_button_text) {
                    dialog: DialogInterface, _ ->
                val intent: Intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                } else {
                    SupportUtils.createCustomTabIntent(
                        activity,
                        SupportUtils.getSumoURLForTopic(
                            activity,
                            SupportUtils.SumoTopic.QR_CAMERA_ACCESS
                        )
                    )
                }
                val uri = Uri.fromParts("package", activity.packageName, null)
                intent.data = uri
                dialog.cancel()
                activity.startActivity(intent)
            }
            create()
        }
    }
}