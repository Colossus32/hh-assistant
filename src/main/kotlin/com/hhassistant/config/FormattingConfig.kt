package com.hhassistant.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration

@Configuration
class FormattingConfig(
    @Value("\${hh.currency.default}") val defaultCurrency: String,
    @Value("\${hh.vacancy.area-not-specified}") val areaNotSpecified: String,
)
