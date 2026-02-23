package com.hhassistant.web

import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.servlet.view.RedirectView

/**
 * Редирект корня и /ui на страницу вакансий.
 */
@Controller
class WebUiRedirectController {

    @GetMapping("/")
    fun root(): RedirectView = RedirectView("/ui/vacancies")

    @GetMapping("/ui")
    fun ui(): RedirectView = RedirectView("/ui/vacancies")
}
