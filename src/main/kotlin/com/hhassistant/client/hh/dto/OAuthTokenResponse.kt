package com.hhassistant.client.hh.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class OAuthTokenResponse(
    @JsonProperty("access_token")
    val accessToken: String,
    @JsonProperty("token_type")
    val tokenType: String,
    @JsonProperty("expires_in")
    val expiresIn: Int? = null,
    @JsonProperty("refresh_token")
    val refreshToken: String? = null,
    @JsonProperty("scope")
    val scope: String? = null,
)
