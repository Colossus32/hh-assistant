package com.hhassistant.vacancy.repository

import com.hhassistant.domain.entity.ExclusionRule
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface ExclusionRuleRepository : JpaRepository<ExclusionRule, Long> {
    fun findByType(type: ExclusionRule.ExclusionRuleType): List<ExclusionRule>
    fun findByTextAndType(text: String, type: ExclusionRule.ExclusionRuleType): ExclusionRule?
    fun existsByTextAndType(text: String, type: ExclusionRule.ExclusionRuleType): Boolean
}
