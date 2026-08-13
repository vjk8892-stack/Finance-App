package dev.kosha.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.kosha.core.database.model.ConstitutionRuleEntity
import dev.kosha.core.database.model.OcrTemplateEntity
import dev.kosha.core.database.model.RuleViolationEntity
import dev.kosha.core.database.model.SavedQueryEntity
import dev.kosha.core.database.model.SmsPatternEntity
import dev.kosha.core.database.model.WarrantyItemEntity
import kotlinx.coroutines.flow.Flow

/** Pattern library, saved queries, constitution, warranties. */
@Dao
interface MetaDao {

    // SMS pattern library (versioned data, spec Part E)
    @Insert
    suspend fun insertSmsPatterns(patterns: List<SmsPatternEntity>)

    @Query("DELETE FROM sms_patterns")
    suspend fun clearSmsPatterns()

    @Query("SELECT * FROM sms_patterns WHERE isActive = 1")
    suspend fun activeSmsPatterns(): List<SmsPatternEntity>

    @Insert
    suspend fun insertOcrTemplates(templates: List<OcrTemplateEntity>)

    @Query("SELECT * FROM ocr_templates")
    suspend fun ocrTemplates(): List<OcrTemplateEntity>

    // Saved queries
    @Insert
    suspend fun insertSavedQuery(query: SavedQueryEntity): Long

    @Delete
    suspend fun deleteSavedQuery(query: SavedQueryEntity)

    @Query("SELECT * FROM saved_queries ORDER BY id")
    fun observeSavedQueries(): Flow<List<SavedQueryEntity>>

    // Constitution
    @Insert
    suspend fun insertConstitutionRule(rule: ConstitutionRuleEntity): Long

    @Update
    suspend fun updateConstitutionRule(rule: ConstitutionRuleEntity)

    @Query("SELECT * FROM constitution_rules WHERE isActive = 1")
    fun observeConstitutionRules(): Flow<List<ConstitutionRuleEntity>>

    @Insert
    suspend fun insertRuleViolation(violation: RuleViolationEntity): Long

    @Query("SELECT * FROM rule_violations WHERE timestampMillis >= :fromMillis")
    suspend fun violationsSince(fromMillis: Long): List<RuleViolationEntity>

    // Warranties
    @Insert
    suspend fun insertWarranty(item: WarrantyItemEntity): Long

    @Update
    suspend fun updateWarranty(item: WarrantyItemEntity)

    @Delete
    suspend fun deleteWarranty(item: WarrantyItemEntity)

    @Query("SELECT * FROM warranty_items ORDER BY expiryDateMillis")
    fun observeWarranties(): Flow<List<WarrantyItemEntity>>
}
