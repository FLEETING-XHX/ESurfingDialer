package com.rsplwe.esurfing

object RecoveryPolicy {
    fun shouldReauthenticate(portalCount: Int, heartbeatFailures: Int, loginAge: Long, cooldownAge: Long): Boolean {
        if (loginAge <= RuntimeConfig.postLoginPortalGraceSeconds && heartbeatFailures == 0) return false
        if (cooldownAge < RuntimeConfig.portalReauthCooldownSeconds) return false
        return portalCount >= RuntimeConfig.portalDetectionThreshold || heartbeatFailures > 0
    }
}
