package com.dawnyew.app.capture

import org.junit.Assert.*
import org.junit.Test

class CapturePermissionsTest {
    @Test fun bothRuntimeAndGlobalNotificationsMustBeEnabled() {
        assertFalse(CapturePermissions.notificationsAvailable(false, true, 3, 2))
        assertFalse(CapturePermissions.notificationsAvailable(true, false, 3, 2))
        assertTrue(CapturePermissions.notificationsAvailable(true, true, 3, 2))
    }

    @Test fun blockingEitherResultOrStopNotificationChannelDisablesObservation() {
        assertFalse(CapturePermissions.notificationsAvailable(true, true, 0, 2))
        assertFalse(CapturePermissions.notificationsAvailable(true, true, 3, 0))
        assertFalse(CapturePermissions.notificationsAvailable(true, true, 0, 0))
        assertTrue(CapturePermissions.notificationsAvailable(true, true, 1, 1))
    }

    @Test fun channelsNotCreatedYetUseVisibleDefaults() {
        assertTrue(CapturePermissions.notificationsAvailable(true, true, null, null))
        assertFalse(CapturePermissions.notificationsAvailable(true, true, null, 0))
        assertFalse(CapturePermissions.notificationsAvailable(true, true, 0, null))
    }
}
