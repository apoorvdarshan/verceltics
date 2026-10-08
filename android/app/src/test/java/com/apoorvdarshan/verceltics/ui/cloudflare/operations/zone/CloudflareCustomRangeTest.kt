package com.apoorvdarshan.verceltics.ui.cloudflare.operations.zone

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudflareCustomRangeTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-09T12:34:56Z") // 18:04:56 in Kolkata

    @Test
    fun rangesResolveToTheMinuteInTheDeviceZone() {
        val (from, to) = CloudflareCustomRange.resolve(
            from = CloudflareRangeEndpoint(LocalDate.of(2026, 10, 9), 9, 15),
            to = CloudflareRangeEndpoint(LocalDate.of(2026, 10, 9), 9, 47),
            zone = zone,
            now = now,
        ).getOrThrow()

        assertEquals(Instant.parse("2026-10-09T03:45:00Z"), from)
        assertEquals(Instant.parse("2026-10-09T04:17:00Z"), to)
        // A 32-minute window: whole-day pickers could never express this.
        assertEquals(32L * 60, to.epochSecond - from.epochSecond)
    }

    @Test
    fun aFutureEndIsClampedToNowLikeTheIosPickerBound() {
        val (_, to) = CloudflareCustomRange.resolve(
            from = CloudflareRangeEndpoint(LocalDate.of(2026, 10, 9), 17, 0),
            to = CloudflareRangeEndpoint(LocalDate.of(2026, 10, 9), 23, 59),
            zone = zone,
            now = now,
        ).getOrThrow()
        assertEquals(now, to)
    }

    @Test
    fun invalidWindowsAreRejectedWithIosCopy() {
        val sameMinute = CloudflareRangeEndpoint(LocalDate.of(2026, 10, 1), 10, 30)
        assertEquals(
            CloudflareCustomRange.START_AFTER_END_MESSAGE,
            CloudflareCustomRange.resolve(sameMinute, sameMinute, zone, now).exceptionOrNull()?.message,
        )
        assertEquals(
            CloudflareCustomRange.START_AFTER_END_MESSAGE,
            CloudflareCustomRange.resolve(
                CloudflareRangeEndpoint(LocalDate.of(2026, 10, 2), 0, 0),
                CloudflareRangeEndpoint(LocalDate.of(2026, 10, 1), 23, 59),
                zone,
                now,
            ).exceptionOrNull()?.message,
        )
        assertEquals(
            CloudflareCustomRange.FUTURE_START_MESSAGE,
            CloudflareCustomRange.resolve(
                CloudflareRangeEndpoint(LocalDate.of(2026, 10, 10), 0, 0),
                CloudflareRangeEndpoint(LocalDate.of(2026, 10, 11), 0, 0),
                zone,
                now,
            ).exceptionOrNull()?.message,
        )
    }

    @Test
    fun endpointsRoundTripInstantsToTheMinute() {
        val endpoint = CloudflareRangeEndpoint.of(Instant.parse("2026-10-09T03:45:59Z"), zone)
        assertEquals(CloudflareRangeEndpoint(LocalDate.of(2026, 10, 9), 9, 15), endpoint)
        assertEquals(Instant.parse("2026-10-09T03:45:00Z"), endpoint.toInstant(zone))
        assertEquals("09:05", CloudflareCustomRange.formatTime(9, 5))
        assertTrue(runCatching { CloudflareRangeEndpoint(LocalDate.of(2026, 1, 1), 24, 0) }.isFailure)
        assertTrue(runCatching { CloudflareRangeEndpoint(LocalDate.of(2026, 1, 1), 0, 60) }.isFailure)
    }
}
