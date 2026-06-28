package com.portal.assistant.conversation.tools

import android.service.notification.NotificationListenerService

/**
 * Exists purely so the app is an **enabled notification listener** — the credential
 * `MediaSessionManager.getActiveSessions` checks before it will hand a non-system app the active
 * [android.media.session.MediaController]s (used by [MediaControl] for the portal.media_control /
 * portal.now_playing tools).
 *
 * **Privacy:** we never read notifications. This service has no logic; we never call `activeNotifications`
 * or implement `onNotificationPosted`. Being bound is the only thing we need — it's the "media session
 * access is permitted" signal. Enabled by adding our component to the `enabled_notification_listeners`
 * secure setting (setup.sh for dev; Settings → notification access for a real user). Dormant until then.
 */
class PortalNotificationListener : NotificationListenerService()
