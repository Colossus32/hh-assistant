package com.hhassistant.client.telegram.dto

import com.fasterxml.jackson.annotation.JsonProperty

data class SendMessageRequest(
    @JsonProperty("chat_id")
    val chatId: String,
    val text: String,
    @JsonProperty("parse_mode")
    val parseMode: String? = "HTML",
    @JsonProperty("disable_web_page_preview")
    val disableWebPagePreview: Boolean = false,
)

data class SendMessageResponse(
    val ok: Boolean,
    val result: Message? = null,
    @JsonProperty("error_code")
    val errorCode: Int? = null,
    val description: String? = null,
)

data class Message(
    @JsonProperty("message_id")
    val messageId: Long,
    val text: String? = null,
)


