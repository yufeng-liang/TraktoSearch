package com.tracktosearch.data.auth

/** 网关令牌有效或处于离线宽限时，允许进入 App 主功能。 */
fun AuthState.hasGatewayAccess(): Boolean =
    this == AuthState.AUTHORIZED || this == AuthState.OFFLINE
