package ac.jfx.openptv.uitesthiltmanifest

import androidx.activity.ComponentActivity
import dagger.hilt.android.AndroidEntryPoint

/**
 * Empty Hilt-aware `ComponentActivity` used by feature androidTests as the host for
 * `createAndroidComposeRule<HiltComponentActivity>()`. Without an `@AndroidEntryPoint`
 * activity declared in *some* manifest, Hilt can't inject anything into a Compose UI test;
 * production manifests declare `MainActivity`, but that drags the whole app into every test.
 * This module exists to give Hilt the smallest possible activity to attach to. Borrowed verbatim
 * from Now in Android.
 */
@AndroidEntryPoint
class HiltComponentActivity : ComponentActivity()
