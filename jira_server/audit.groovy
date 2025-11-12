import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.security.plugin.ProjectPermissionKey
import com.atlassian.jira.permission.ProjectPermissions

// ==================== CONFIGURATION ====================
def MIN_PROJECTS_TO_HIGHLIGHT = 500   // Groups with access to this many+ projects will be highlighted in red
def INCLUDE_INACTIVE_GROUPS = false   // Set true to include groups with 0 access
def OUTPUT_FORMAT = "HTML"            // "HTML" or "CSV"
// ======================================================

def start = System.currentTimeMillis()

def groupManager = ComponentAccessor.groupManager
def permissionManager = ComponentAccessor.permissionManager
def projectManager = ComponentAccessor.projectManager
def projectRoleManager = ComponentAccessor.projectRoleManager
def issueSecurityLevelManager = ComponentAccessor.getOSGiComponentInstanceOfType(
    com.atlassian.jira.issue.security.IssueSecurityLevelManager
)
def issueSecuritySchemeManager = ComponentAccessor.getOSGiComponentInstanceOfType(
    com.atlassian.jira.issue.security.IssueSecuritySchemeManager
)

def browsePerm = ProjectPermissions.BROWSE_PROJECTS_PERMISSION
def allProjects = projectManager.projectObjects
def totalProjects = allProjects.size()

// Pre-load all groups
def allGroups = groupManager.allGroups.collect { it.name }.sort()

// Cache maps
def groupToBrowseProjects = [:] as Map<String, Set<String>>
def groupToRoleProjects = [:] as Map<String, Set<String>>
def groupToSecurityLevels = [:] as Map<String, List<String>>

log.warn("Starting group audit across ${totalProjects} projects and ${allGroups.size()} groups...")

// 1. Browse permissions
allProjects.each { proj ->
    def permScheme = proj.permissionScheme
    if (!permScheme) return
    permScheme.getPermissionMappings().each { mapping ->
        if (mapping.permissionKey == browsePerm) {
            mapping.grantees.each { grantee ->
                if (grantee.type == "group" && grantee.parameter) {
                    def g = grantee.parameter
                    groupToBrowseProjects.computeIfAbsent(g, { [] as Set }) << proj.key
                }
            }
        }
    }
}

// 2. Project roles
allProjects.each { proj ->
    projectRoleManager.getProjectRoles().each { role ->
        def actors = projectRoleManager.getActors(role, proj)
        actors?.groups?.each { g ->
            if (g.name) {
                groupToRoleProjects.computeIfAbsent(g.name, { [] as Set }) << proj.key
            }
        }
    }
}

// 3. Issue security
issueSecuritySchemeManager.getSecuritySchemes().each { scheme ->
    issueSecurityLevelManager.getSecurityLevelsForScheme(scheme).each { level ->
        def groupEntities = issueSecurityLevelManager.getEntities(level, com.atlassian.jira.issue.security.IssueSecurityLevel.TYPE_GROUP)
        groupEntities.each { entity ->
            def g = entity.name
            groupToSecurityLevels.computeIfAbsent(g, { [] }) << "${scheme.name}:${level.name}"
        }
    }
}

// Build report
def report = allGroups.collect { groupName ->
    def browseCount = groupToBrowseProjects[groupName]?.size() ?: 0
    def roleCount = groupToRoleProjects[groupName]?.size() ?: 0
    def securityCount = groupToSecurityLevels[groupName]?.size() ?: 0
    def totalUniqueProjects = (groupToBrowseProjects[groupName] ?: [] as Set) + (groupToRoleProjects[groupName] ?: [] as Set)
    def hasAccess = browseCount > 0 || roleCount > 0 || securityCount > 0
    if (!INCLUDE_INACTIVE_GROUPS && !hasAccess) return null

    [
        group: groupName,
        browse: browseCount,
        roles: roleCount,
        security: securityCount,
        totalProjects: totalUniqueProjects.size(),
        projectList: totalUniqueProjects.sort().join(", "),
        securityList: (groupToSecurityLevels[groupName] ?: []).join(" | ")
    ]
}.findAll { it != null }.sort { it.group }

// =============== OUTPUT ===============
if (OUTPUT_FORMAT == "CSV") {
    def csv = "Group,Browse Projects,Roles Projects,Security Levels,Total Unique Projects,Projects List,Security Details\n"
    report.each {
        csv << "\"${it.group}\",${it.browse},${it.roles},${it.security},${it.totalProjects},\"${it.projectList}\",\"${it.securityList}\"\n"
    }
    return "<pre>${csv}</pre><p>Copy and paste into .csv file</p>"
}

// HTML Table
def sb = new StringBuilder()
sb << """
<style>
  table { border-collapse: collapse; width: 100%; font-family: Arial; font-size: 13px; }
  th, td { border: 1px solid #ccc; padding: 8px; text-align: left; }
  th { background: #f0f0f0; position: sticky; top: 0; }
  tr:hover { background: #f8f8f8; }
  .high { background: #ffcccc !important; }
  .med { background: #fff3cd; }
  .low { background: #d4edda; }
  details { margin: 4px 0; }
</style>
<h2>Jira Group Access Heatmap</h2>
<p><b>${report.size()}</b> groups with access | <b>${totalProjects}</b> total projects | Generated in ${(System.currentTimeMillis() - start)/1000}s</p>
<table>
  <tr>
    <th>Group</th>
    <th>Browse Projects</th>
    <th>Roles