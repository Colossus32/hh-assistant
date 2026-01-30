package com.hhassistant.web

import com.hhassistant.client.telegram.dto.Update
import com.hhassistant.service.TelegramWebhookService
import mu.KotlinLogging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Контроллер для обработки webhook от Telegram
 */
@RestController
@RequestMapping("/api/telegram")
class TelegramWebhookController(
    private val webhookService: TelegramWebhookService,
) {
    private val log = KotlinLogging.logger {}

    /**
     * Обрабатывает входящие обновления от Telegram
     */
    @PostMapping("/webhook")
    fun handleWebhook(@RequestBody update: Update): ResponseEntity<String> {
        return try {
            log.info("📥 [Webhook] Received update ID: ${update.updateId}")
            log.debug("📥 [Webhook] Update details: hasMessage=${update.message != null}, hasCallbackQuery=${update.callbackQuery != null}")
            
            if (update.callbackQuery != null) {
                log.info("🔘 [Webhook] Callback query detected: id=${update.callbackQuery.id}, data=${update.callbackQuery.data}")
            }
            
            if (update.message != null) {
                log.debug("💬 [Webhook] Message detected: text=${update.message.text}, hasDocument=${update.message.document != null}")
            }
            
            webhookService.handleUpdate(update)
            ResponseEntity.ok("OK")
        } catch (e: Exception) {
            log.error("❌ [Webhook] Error processing update: ${e.message}", e)
            ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error: ${e.message}")
        }
    }
}
