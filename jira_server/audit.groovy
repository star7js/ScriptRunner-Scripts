import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.permission.ProjectPermissions

// ==================== CONFIGURATION ====================
def MIN_PROJECTS_TO_HIGHLIGHT = 500   // Highlight groups with this many+ projects
def INCLUDE_INACTIVE_GROUPS = false   // Set true to show groups with zero access
def OUTPUT_FORMAT = "HTML"            // "HTML" or "CSV"
// ======================================================

def start = System.currentTimeMillis()

def groupManager           = ComponentAccessor.groupManager
def permissionManager      = ComponentAccessor.permissionManager
def projectManager         = ComponentAccessor.projectManager
def projectRoleManager     = ComponentAccessor.projectRoleManager
def issueSecurityLevelMgr  = ComponentAccessor.getOSGiComponentInstanceOfType(
        com.atlassian.jira.issue.security.IssueSecurityLevelManager)
def issueSecuritySchemeMgr = ComponentAccessor.getOSGiComponentInstanceOfType(
        com.atlassian.jira.issue.security.IssueSecuritySchemeManager)

def browsePerm    = ProjectPermissions.BROWSE_PROJECTS_PERMISSION
def allProjects   = projectManager.projectObjects
def totalProjects = allProjects.size()

def allGroups = groupManager.allGroups.collect { it.name }.sort()

def groupToBrowseProjects = [:] as Map<String, Set<String>>
def groupToRoleProjects   = [:] as Map<String, Set<String>>
def groupToSecurityLevels = [:] as Map<String, List<String>>

log.warn("Starting audit: ${totalProjects} projects, ${allGroups.size()} groups...")

// 1. Browse Projects permission
allProjects.each { proj ->
    def scheme = proj.permissionScheme
    if (!scheme) return
    scheme.getPermissionMappings().each { mapping ->
        if (mapping.permissionKey == browsePerm) {
            mapping.grantees.each { grantee ->
                if (grantee.type == "group" && grantee.parameter) {
                    groupToBrowseProjects
                        .computeIfAbsent(grantee.parameter) { [] as Set }
                        .add(proj.key)
                }
            }
        }
    }
}

// 2. Project Roles
allProjects.each { proj ->
    projectRoleManager.getProjectRoles().each { role ->
        def actors = projectRoleManager.getActors(role, proj)
        actors?.groups?.each { g ->
            groupToRoleProjects
                .computeIfAbsent(g.name) { [] as Set }
                .add(proj.key)
        }
    }
}

// 3. Issue Security Levels
issueSecuritySchemeMgr.getSecuritySchemes().each { scheme ->
    issueSecurityLevelMgr.getSecurityLevelsForScheme(scheme).each { level ->
        def entities = issueSecurityLevelMgr.getEntities(level,
                com.atlassian.jira.issue.security.IssueSecurityLevel.TYPE_GROUP)
        entities.each { ent ->
            groupToSecurityLevels
                .computeIfAbsent(ent.name) { [] }
                .add("${scheme.name}:${level.name}")
        }
    }
}

// Build report rows
def report = allGroups.collect { groupName ->
    def browse = groupToBrowseProjects[groupName]?.size() ?: 0
    def roles  = groupToRoleProjects[groupName]?.size()   ?: 0
    def sec    = groupToSecurityLevels[groupName]?.size() ?: 0

    def uniqueProjects = (groupToBrowseProjects[groupName] ?: [] as Set) +
                         (groupToRoleProjects[groupName]   ?: [] as Set)

    def hasAccess = browse > 0 || roles > 0 || sec > 0
    if (!INCLUDE_INACTIVE_GROUPS && !hasAccess) return null

    [
        group         : groupName,
        browse        : browse,
        roles         : roles,
        security      : sec,
        totalProjects : uniqueProjects.size(),
        projectList   : uniqueProjects.sort().join(', '),
        securityList  : (groupToSecurityLevels[groupName] ?: []).join(' | ')
    ]
}.findAll { it }.sort { it.group }

// =============== OUTPUT ===============
if (OUTPUT_FORMAT == "CSV") {
    def csv = "Group,Browse Projects,Roles Projects,Security Levels,Total Unique Projects,Project List,Security Details\n"
    report.each {
        csv << "\"${it.group}\",${it.browse},${it.roles},${it.security},${it.totalProjects},\"${it.projectList}\",\"${it.securityList}\"\n"
    }
    return "<pre>${csv}</pre><p>Copy → paste into .csv</p>"
}

// HTML output (safe slashy strings)
def html = /
<style>
  table {border-collapse:collapse;width:100%;font-family:Arial;font-size:13px;}
  th,td {border:1px solid #ccc;padding:8px;text-align:left;}
  th {background:#f0f0f0;position:sticky;top:0;}
  tr:hover {background:#f8f8f8;}
  .high {background:#ffcccc !important;}
  .med  {background:#fff3cd;}
  .low  {background:#d4edda;}
  details {margin:4px 0;}
</style>
<h2>Jira Group Access Heatmap</h2>
<p><b>${report.size()}</b> groups with access • <b>${totalProjects}</b> total projects • 
   Generated in ${(System.currentTimeMillis() - start)/1000.0}s</p>
<table>
  <tr>
    <th>Group</th><th>Browse</th><th>Roles</th><th>Security</th><th>Total Projects</th><th>Details</th>
  </tr>
/ 

report.each { row ->
    def highlight = row.totalProjects >= MIN_PROJECTS_TO_HIGHLIGHT ? 'high' :
                    row.totalProjects >= totalProjects * 0.5      ? 'med'  : 'low'
    def adminLink = "<a href='/secure/admin/user/GroupBrowser.jspa?name=${row.group}' target='_blank'>${row.group}</a>"

    html += "<tr class='${highlight}'>"
    html += "<td><b>${adminLink}</b></td>"
    html += "<td>${row.browse}</td>"
    html += "<td>${row.roles}</td>"
    html += "<td>${row.security}</td>"
    html += "<td>${row.totalProjects}</td>"
    html += "<td>"
    if (row.totalProjects > 0)
        html += "<details><summary>${row.totalProjects} projects</summary><small>${row.projectList}</small></details>"
    if (row.security > 0)
        html += "<br><details><summary>Security levels</summary><small>${row.securityList}</small></details>"
    html += "</td></tr>"
}

html += /
</table>
<p><b>Color code:</b> 
   <span class='high'>High (≥${MIN_PROJECTS_TO_HIGHLIGHT})</span> • 
   <span class='med'>Medium</span> • 
   <span class='low'>Low</span>
</p>
/

return html
