import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.security.IssueSecuritySchemeManager
import com.atlassian.jira.permission.ProjectPermissions
import com.atlassian.jira.security.roles.ProjectRoleManager
import com.atlassian.jira.security.plugin.ProjectPermissionKey
import groovy.xml.MarkupBuilder
import com.atlassian.mail.server.MailServerManager
import com.atlassian.jira.service.services.mail.MailFetcherService
import com.atlassian.jira.service.JiraServiceContainer

// Helper Functions
def getLeadName(project, noneValue) {
    project.getProjectLead()?.getDisplayName() ?: noneValue
}

def getCategoryName(project, noneValue) {
    project.getProjectCategory()?.getName() ?: noneValue
}

def getIssueSecurityStatus(project, issueSecuritySchemeManager) {
    issueSecuritySchemeManager.getSchemeFor(project) ? "Yes" : "No"
}

def getAdminEntities(scheme, permissionKey, permissionSchemeManager, userManager, groupType, userType, noneValue, errorValue) {
    if (!scheme) return [groups: noneValue, users: noneValue]
    
    try {
        def adminEntities = permissionSchemeManager.getPermissionSchemeEntries(scheme, permissionKey)
        def groups = adminEntities.findAll { it.getType() == groupType }.collect { it.getParameter() }.join(", ") ?: noneValue
        def users = adminEntities.findAll { it.getType() == userType }.collect {
            userManager.getUserByKey(it.getParameter())?.getDisplayName() ?: "Unknown"
        }.join(", ") ?: noneValue
        [groups: groups, users: users]
    } catch (Exception e) {
        log.warn("Error getting admin entities for scheme ${scheme.getName()}: ${e.message}")
        [groups: errorValue, users: errorValue]
    }
}

def getProjectRoleAdmins(project, projectRoleManager, adminRoleName, noneValue, errorValue) {
    try {
        def adminRole = projectRoleManager.getProjectRole(adminRoleName)
        if (!adminRole) return [groups: noneValue, users: noneValue]
        
        def actors = projectRoleManager.getProjectRoleActors(adminRole, project)
        def groups = actors.getRoleActors().findAll { 
            it.type == com.atlassian.jira.security.roles.ProjectRoleActor.GROUP_ROLE_ACTOR_TYPE 
        }.collect { it.parameter }.join(", ") ?: noneValue
        
        def users = actors.getUsers().collect { it.displayName }.join(", ") ?: noneValue
        
        [groups: groups, users: users]
    } catch (Exception e) {
        log.warn("Error getting project role admins for ${project.key}: ${e.message}")
        [groups: errorValue, users: errorValue]
    }
}

def parseAndDeduplicateEntities(entityString, noneValue, errorValue) {
    if (!entityString || entityString == noneValue || entityString == errorValue) {
        return []
    }
    return entityString.split(", ").findAll { it.trim() }.unique()
}

def getCombinedAdminEntities(project, scheme, permissionKey, permissionSchemeManager, userManager, projectRoleManager, groupType, userType, adminRoleName, noneValue, errorValue) {
    // Get direct permission scheme entries
    def directAdmins = getAdminEntities(scheme, permissionKey, permissionSchemeManager, userManager, groupType, userType, noneValue, errorValue)
    
    // Get project role administrators
    def roleAdmins = getProjectRoleAdmins(project, projectRoleManager, adminRoleName, noneValue, errorValue)
    
    // Parse and combine entities
    def allGroups = []
    def allUsers = []
    
    allGroups.addAll(parseAndDeduplicateEntities(directAdmins.groups, noneValue, errorValue))
    allGroups.addAll(parseAndDeduplicateEntities(roleAdmins.groups, noneValue, errorValue))
    allUsers.addAll(parseAndDeduplicateEntities(directAdmins.users, noneValue, errorValue))
    allUsers.addAll(parseAndDeduplicateEntities(roleAdmins.users, noneValue, errorValue))
    
    // Remove duplicates and join
    def uniqueGroups = allGroups.unique().join(", ") ?: noneValue
    def uniqueUsers = allUsers.unique().join(", ") ?: noneValue
    
    [groups: uniqueGroups, users: uniqueUsers]
}

def getAssociatedEmails(project, mailServices, mailServerManager, noneValue) {
    def emails = []
    try {
        mailServices.each { JiraServiceContainer service ->
            try {
                def params = service.getParams()
                def projectParam = params['handler.params.project'] ?: params['project'] ?: params['projectkey']
                if (projectParam) {
                    boolean isMatch = false
                    try {
                        isMatch = projectParam.toLong() == project.id
                    } catch (NumberFormatException e) {
                        isMatch = projectParam == project.key
                    }
                    
                    if (isMatch) {
                        def serverId = params['serverId'] ?: params['mailserver'] ?: params['mailConnectionId']
                        if (serverId) {
                            try {
                                def mailServer = mailServerManager.getMailServer(serverId.toLong())
                                if (mailServer?.username) emails << mailServer.username
                            } catch (Exception e) {
                                log.warn("Error processing mail server for ${project.key}: ${e.message}")
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Error processing mail service for ${project.key}: ${e.message}")
            }
        }
    } catch (Exception e) {
        log.warn("Error processing mail services: ${e.message}")
    }
    emails.unique().join(", ") ?: noneValue
}

// Main Logic
def generateProjectTable() {
    // Constants
    final String ADMIN_ROLE_NAME = "Administrators"
    final String GROUP_TYPE = "group"
    final String USER_TYPE = "user"
    final String NONE_VALUE = "None"
    final String ERROR_VALUE = "Error"
    
    // Component Accessors - moved inside function to avoid scope issues
    def projectManager = ComponentAccessor.getProjectManager()
    def permissionSchemeManager = ComponentAccessor.getPermissionSchemeManager()
    def issueSecuritySchemeManager = ComponentAccessor.getComponent(IssueSecuritySchemeManager.class)
    def userManager = ComponentAccessor.getUserManager()
    def projectRoleManager = ComponentAccessor.getComponent(ProjectRoleManager.class)
    def mailServerManager = ComponentAccessor.getMailServerManager()
    
    // Cache mail services to avoid repeated calls
    def mailServices = []
    try {
        mailServices = ComponentAccessor.getOSGiComponentInstanceOfType(MailFetcherService.class).findAll()
    } catch (Exception e) {
        log.warn("Error getting mail services: ${e.message}")
    }
    
    // Define constants inside the function to avoid scope issues
    final String TABLE_STYLE = "border-collapse: collapse; width: 100%;"
    final String HEADER_STYLE = "background-color: #f2f2f2; font-weight: bold;"
    final String CELL_STYLE = "padding: 8px; border: 1px solid black;"
    final ProjectPermissionKey ADMIN_PERMISSION = ProjectPermissions.ADMINISTER_PROJECTS

    def writer = new StringWriter()
    def html = new MarkupBuilder(writer)

    html.table(border: "1", style: TABLE_STYLE) {
        tr(style: HEADER_STYLE) {
            ["Project Title", "Project Key", "Project Lead", "Project Category",
             "Admin Groups", "Individual Administrators", "Uses Issue Security", "Associated Emails"].each { header ->
                th(style: CELL_STYLE, header)
            }
        }
        projectManager.getProjectObjects().each { project ->
            try {
                def scheme = permissionSchemeManager.getSchemeObject(project.getId())
                def adminEntities = getCombinedAdminEntities(project, scheme, ADMIN_PERMISSION, permissionSchemeManager, userManager, projectRoleManager, GROUP_TYPE, USER_TYPE, ADMIN_ROLE_NAME, NONE_VALUE, ERROR_VALUE)
                tr {
                    td(style: CELL_STYLE, project.getName())
                    td(style: CELL_STYLE, project.key)
                    td(style: CELL_STYLE, getLeadName(project, NONE_VALUE))
                    td(style: CELL_STYLE, getCategoryName(project, NONE_VALUE))
                    td(style: CELL_STYLE, adminEntities.groups)
                    td(style: CELL_STYLE, adminEntities.users)
                    td(style: CELL_STYLE, getIssueSecurityStatus(project, issueSecuritySchemeManager))
                    td(style: CELL_STYLE, getAssociatedEmails(project, mailServices, mailServerManager, NONE_VALUE))
                }
            } catch (Exception e) {
                log.warn("Error processing project ${project.key}: ${e.message}")
                // Add error row
                tr {
                    td(style: CELL_STYLE, project.getName() ?: "Unknown")
                    td(style: CELL_STYLE, project.key ?: "Unknown")
                    td(style: CELL_STYLE, ERROR_VALUE)
                    td(style: CELL_STYLE, ERROR_VALUE)
                    td(style: CELL_STYLE, ERROR_VALUE)
                    td(style: CELL_STYLE, ERROR_VALUE)
                    td(style: CELL_STYLE, ERROR_VALUE)
                    td(style: CELL_STYLE, ERROR_VALUE)
                }
            }
        }
    }

    log.debug("Generated HTML: ${writer.toString()}")
    writer.toString()
}

return generateProjectTable()
