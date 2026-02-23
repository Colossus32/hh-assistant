package com.hhassistant.web

import com.hhassistant.service.exclusion.ExclusionKeywordService
import com.hhassistant.vacancy.service.VacancyService
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.mvc.support.RedirectAttributes
import org.springframework.web.servlet.view.RedirectView

/**
 * MVC контроллер для Thymeleaf UI: вакансии и бан-слова.
 */
@Controller
@RequestMapping("/ui")
class UiController(
    private val vacancyService: VacancyService,
    private val exclusionKeywordService: ExclusionKeywordService,
) {

    /**
     * Страница вакансий с пагинацией (новые первыми).
     */
    @GetMapping("/vacancies")
    fun vacancies(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        model: Model,
    ): String {
        val safePage = maxOf(0, page)
        val safeSize = size.coerceIn(1, 100)
        val paged = vacancyService.findAllVacanciesPaged(safePage, safeSize)
        model.addAttribute("vacancies", paged.content)
        model.addAttribute("currentPage", paged.number)
        model.addAttribute("totalPages", paged.totalPages)
        model.addAttribute("totalElements", paged.totalElements)
        model.addAttribute("pageSize", paged.size)
        return "ui/vacancies"
    }

    /**
     * Страница бан-слов.
     */
    @GetMapping("/exclusion")
    fun exclusion(model: Model): String {
        val keywords = exclusionKeywordService.getAllKeywords().toList().sorted()
        model.addAttribute("keywords", keywords)
        return "ui/exclusion"
    }

    /**
     * Добавить бан-слова (несколько через пробел).
     */
    @PostMapping("/exclusion/add")
    fun addKeyword(
        @RequestParam keywords: String,
        redirectAttributes: RedirectAttributes,
    ): RedirectView {
        val trimmed = keywords.trim()
        if (trimmed.isNotEmpty()) {
            exclusionKeywordService.addKeyword(trimmed, caseSensitive = false)
            redirectAttributes.addFlashAttribute("message", "Добавлено: $trimmed")
        }
        return RedirectView("/ui/exclusion")
    }

    /**
     * Удалить бан-слово.
     */
    @PostMapping("/exclusion/remove")
    fun removeKeyword(
        @RequestParam keyword: String,
        redirectAttributes: RedirectAttributes,
    ): RedirectView {
        val removed = exclusionKeywordService.removeKeyword(keyword)
        redirectAttributes.addFlashAttribute(
            "message",
            if (removed) "Слово «$keyword» удалено" else "Слово не найдено",
        )
        return RedirectView("/ui/exclusion")
    }
}
