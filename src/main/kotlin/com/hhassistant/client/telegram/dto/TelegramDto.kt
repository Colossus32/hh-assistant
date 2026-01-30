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
    val text: String? = null,
    val document: Document? = null,
    val from: User? = null,
    val chat: Chat? = null,
)

data class Document(
    @JsonProperty("file_id")
    val fileId: String,
    @JsonProperty("file_name")
    val fileName: String? = null,
    @JsonProperty("mime_type")
    val mimeType: String? = null,
    @JsonProperty("file_size")
    val fileSize: Long? = null,
)

data class User(
    val id: Long,
    @JsonProperty("first_name")
    val firstName: String? = null,
    @JsonProperty("last_name")
    val lastName: String? = null,
    val username: String? = null,
)

data class Chat(
    val id: Long,
    val type: String? = null,
)

data class Update(
    @JsonProperty("update_id")
    val updateId: Long,
    val message: Message? = null,
    @JsonProperty("callback_query")
    val callbackQuery: CallbackQuery? = null,
)

data class CallbackQuery(
    val id: String,
    val from: User? = null,
    val message: Message? = null,
    @JsonProperty("data")
    val data: String? = null,
)

data class AnswerCallbackQueryRequest(
    @JsonProperty("callback_query_id")
    val callbackQueryId: String,
    val text: String? = null,
    @JsonProperty("show_alert")
    val showAlert: Boolean = false,
)

data class AnswerCallbackQueryResponse(
    val ok: Boolean,
    @JsonProperty("error_code")
    val errorCode: Int? = null,
    val description: String? = null,
)

data class GetFileResponse(
    val ok: Boolean,
    val result: FileInfo? = null,
    @JsonProperty("error_code")
    val errorCode: Int? = null,
    val description: String? = null,
)

data class FileInfo(
    @JsonProperty("file_id")
    val fileId: String,
    @JsonProperty("file_unique_id")
    val fileUniqueId: String,
    @JsonProperty("file_size")
    val fileSize: Long? = null,
    @JsonProperty("file_path")
    val filePath: String? = null,
)
