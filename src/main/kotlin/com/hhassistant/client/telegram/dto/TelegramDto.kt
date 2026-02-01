package com.hhassistant.client.telegram.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SendMessageRequest(
    @JsonProperty("chat_id")
    val chatId: String,
    val text: String,
    @JsonProperty("parse_mode")
    val parseMode: String? = "HTML",
    @JsonProperty("disable_web_page_preview")
    val disableWebPagePreview: Boolean = false,
    @JsonProperty("reply_markup")
    val replyMarkup: InlineKeyboardMarkup? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InlineKeyboardMarkup(
    @JsonProperty("inline_keyboard")
    val inlineKeyboard: List<List<InlineKeyboardButton>>,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class InlineKeyboardButton(
    val text: String,
    val url: String? = null,
    @JsonProperty("callback_data")
    val callbackData: String? = null,
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
    val from: User? = null,
    val chat: Chat? = null,
    val text: String? = null,
    val date: Long? = null,
)

data class User(
    val id: Long,
    @JsonProperty("is_bot")
    val isBot: Boolean = false,
    @JsonProperty("first_name")
    val firstName: String? = null,
    @JsonProperty("last_name")
    val lastName: String? = null,
    val username: String? = null,
)

data class Chat(
    val id: Long,
    val type: String? = null,
    val title: String? = null,
    val username: String? = null,
    @JsonProperty("first_name")
    val firstName: String? = null,
    @JsonProperty("last_name")
    val lastName: String? = null,
)

data class Update(
    @JsonProperty("update_id")
    val updateId: Long,
    val message: Message? = null,
)

data class GetUpdatesRequest(
    val offset: Long? = null,
    val limit: Int? = 100,
    val timeout: Int? = 0,
)

data class GetUpdatesResponse(
    val ok: Boolean,
    val result: List<Update>? = null,
    @JsonProperty("error_code")
    val errorCode: Int? = null,
    val description: String? = null,
)
