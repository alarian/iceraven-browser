/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.fenix.settings

import androidx.core.content.edit
import androidx.preference.Preference
import org.mozilla.fenix.R
import org.mozilla.fenix.ext.components
import org.mozilla.fenix.ext.settings

/**
 * Updates the corresponding [android.content.SharedPreferences] when the boolean [Preference] is changed.
 * The preference key is used as the shared preference key.
 */
open class SharedPreferenceUpdater : Preference.OnPreferenceChangeListener {

    override fun onPreferenceChange(preference: Preference, newValue: Any?): Boolean {
        if (newValue is Boolean) {
            preference.context.settings().preferences.edit {
                putBoolean(preference.key, newValue)
            }
        } else if (newValue is String) {
            preference.context.settings().preferences.edit {
                putString(preference.key, newValue)
            }

            if (preference.key == preference.context.getString(R.string.pref_key_addons_custom_account) ||
                preference.key == preference.context.getString(R.string.pref_key_addons_custom_collection)) {
                preference.context.components.updateAddonManager()
            }
        }

        return true
    }
}
