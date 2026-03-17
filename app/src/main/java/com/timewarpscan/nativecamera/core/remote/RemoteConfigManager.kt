package com.timewarpscan.nativecamera.core.remote

import android.content.Context
import com.timewarpscan.nativecamera.model.Section
import com.timewarpscan.nativecamera.model.VideoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object RemoteConfigManager {

    private const val KEY_SECTIONS = "home_sections"

    /**
     * Fetch sections — tries remote JSON first, falls back to local data.
     */
    suspend fun getSections(context: Context): List<Section> = withContext(Dispatchers.IO) {
        try {
            val json = fetchRemoteJson()
            if (json != null) parseJson(json) else fallbackSections()
        } catch (_: Exception) {
            fallbackSections()
        }
    }

    /**
     * Placeholder for real Remote Config fetch.
     * Replace with Firebase RemoteConfig or your own backend.
     */
    private fun fetchRemoteJson(): String? {
        // TODO: Replace with actual Firebase Remote Config fetch
        // FirebaseRemoteConfig.getInstance().getString(KEY_SECTIONS)
        return null // triggers fallback
    }

    fun parseJson(json: String): List<Section> {
        val root = JSONObject(json)
        val sectionsArray = root.getJSONArray("sections")
        return (0 until sectionsArray.length()).map { i ->
            val sectionObj = sectionsArray.getJSONObject(i)
            val itemsArray = sectionObj.getJSONArray("items")
            val items = (0 until itemsArray.length()).map { j ->
                val item = itemsArray.getJSONObject(j)
                VideoItem(
                    id = item.getString("id"),
                    thumbnail = item.getString("thumbnail"),
                    effect = item.getString("effect")
                )
            }
            Section(
                title = sectionObj.getString("title"),
                items = items
            )
        }
    }

    fun fallbackSections(): List<Section> = listOf(
        Section(
            title = "Trending Videos 🔥",
            items = listOf(
                VideoItem("trend_1", "onboarding_1", "swirl"),
                VideoItem("trend_2", "onboarding_2", "mirror"),
                VideoItem("trend_3", "onboarding_3", "grid"),
                VideoItem("trend_4", "ic_effect_double", "double"),
                VideoItem("trend_5", "ic_effect_waterfall", "waterfall"),
                VideoItem("trend_6", "ic_effect_split", "split")
            )
        ),
        Section(
            title = "Halloween 🎃",
            items = listOf(
                VideoItem("hall_1", "onboarding_2", "swirl"),
                VideoItem("hall_2", "onboarding_1", "mirror"),
                VideoItem("hall_3", "onboarding_3", "double"),
                VideoItem("hall_4", "ic_effect_swirl", "swirl"),
                VideoItem("hall_5", "ic_effect_mirror", "mirror"),
                VideoItem("hall_6", "ic_effect_grid", "grid")
            )
        ),
        Section(
            title = "Christmas 🎄",
            items = listOf(
                VideoItem("xmas_1", "onboarding_3", "waterfall"),
                VideoItem("xmas_2", "onboarding_1", "split"),
                VideoItem("xmas_3", "onboarding_2", "single"),
                VideoItem("xmas_4", "ic_effect_single", "single"),
                VideoItem("xmas_5", "ic_effect_waterfall", "waterfall"),
                VideoItem("xmas_6", "ic_effect_split", "split")
            )
        )
    )
}
