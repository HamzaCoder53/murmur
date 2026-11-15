package com.bitchat.watch.internet

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bitchat.android.geohash.GeohashChannelLevel
import com.bitchat.watch.ui.areaLabelRes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationChannelsTest {

    private fun channels(): WatchLocationChannels {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val internet = WatchNostrClient(context, scope, { "a".repeat(16) }, { "me" }, { }, { null }, { null })
        return WatchLocationChannels(context, scope, internet) { "me" }
    }

    @Test
    fun `a precise location offers block to region with the right geohashes`() {
        // Synthetic point (a well-known public landmark), not anyone's location.
        val found = channels().channelsAt(37.4220, -122.0841, precise = true)

        assertEquals(
            listOf(
                GeohashChannelLevel.BLOCK, GeohashChannelLevel.NEIGHBORHOOD, GeohashChannelLevel.CITY,
                GeohashChannelLevel.PROVINCE, GeohashChannelLevel.REGION
            ),
            found.map { it.level }
        )
        assertEquals("9q9hvum", found.first().geohash)
        assertEquals("9q", found.last().geohash)
    }

    @Test
    fun `an approximate location only offers city and wider`() {
        val found = channels().channelsAt(51.5007, -0.1246, precise = false)

        assertEquals(listOf("gcpuv", "gcpu", "gc"), found.map { it.geohash })
    }

    @Test
    fun `areas are named by geohash length`() {
        assertEquals("Block", com.bitchat.watch.testing.ResourceStrings.get(areaLabelRes("9q9hvum")))
        assertEquals("Neighborhood", com.bitchat.watch.testing.ResourceStrings.get(areaLabelRes("9q9hvu")))
        assertEquals("City", com.bitchat.watch.testing.ResourceStrings.get(areaLabelRes("9q9hv")))
        assertEquals("Province", com.bitchat.watch.testing.ResourceStrings.get(areaLabelRes("9q9h")))
        assertEquals("Region", com.bitchat.watch.testing.ResourceStrings.get(areaLabelRes("9q")))
    }
}
