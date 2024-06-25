package teksturepako.pakku.api.actions.export

import teksturepako.pakku.api.actions.export.rules.ExportRule
import teksturepako.pakku.api.actions.export.rules.RuleContext.*
import teksturepako.pakku.api.overrides.OverrideType
import teksturepako.pakku.api.projects.ProjectSide

fun exportServerPack() = ExportRule {
    when (it)
    {
        is ExportingProject         ->
        {
            if (it.project.side !in listOf(ProjectSide.BOTH, ProjectSide.SERVER))
            {
                it.ignore()
            }
            else
            {
                it.exportAsOverride { bytesCallback, fileName, _ ->
                    it.createFile(bytesCallback, it.project.type.folderName, fileName)
                }
            }
        }
        is ExportingOverride        ->
        {
            if (it.type !in listOf(OverrideType.OVERRIDE, OverrideType.SERVER_OVERRIDE))
            {
                it.ignore()
            }
            else
            {
                it.export(overridesDir = null)
            }
        }
        is ExportingProjectOverride ->
        {
            if (it.projectOverride.type !in listOf(OverrideType.OVERRIDE, OverrideType.SERVER_OVERRIDE))
            {
                it.ignore()
            }
            else
            {
                it.export(overridesDir = null)
            }
        }
        else -> it.ignore()
    }
}