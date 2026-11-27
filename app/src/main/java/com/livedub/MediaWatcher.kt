package com.livedub

import android.service.notification.NotificationListenerService

/**
 * Empty on purpose. Enabling this listener ("Notification access") is what lets the app read
 * the playback position of the video that is playing (via MediaSessionManager).
 */
class MediaWatcher : NotificationListenerService()
