package com.athar.core.data.backup

/** Structural completeness/references only; not financial admission authorization. */
internal object BackupAuxiliaryValidation {
    fun validate(section: BackupAuxiliarySection, snapshot: BackupSnapshot) {
        val identities = listOf(
            "activity log" to section.activityLog.map { it.id },
            "user template" to section.userTemplates.map { it.id },
            "recurring rule" to section.recurringRules.map { it.id },
            "saved plan" to section.savedPlans.map { it.id },
        )
        identities.forEach { (table, ids) ->
            if (ids.size != ids.toSet().size) {
                throw BackupValidationException(
                    BackupValidationCode.DUPLICATE_BACKUP_KEY,
                    "Duplicate $table id in backup",
                )
            }
        }
        val accounts = snapshot.accounts.map { it.id }.toSet()
        val categories = snapshot.categories.map { it.id }.toSet()
        section.recurringRules.forEach { rule ->
            if (rule.accountId !in accounts || (rule.categoryId != null && rule.categoryId !in categories)) {
                throw BackupValidationException(
                    BackupValidationCode.LEGACY_REFERENCE_MISSING,
                    "Recurring rule ${rule.id} references an absent account or category",
                )
            }
        }
    }
}
