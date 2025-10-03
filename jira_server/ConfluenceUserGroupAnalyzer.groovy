import com.atlassian.confluence.user.UserAccessor
import com.atlassian.sal.api.component.ComponentLocator
import com.atlassian.user.GroupManager

/**
 * ScriptRunner script for Confluence 9 and 10 Data Center
 * Retrieves users from specified groups and displays their usernames and email addresses
 * 
 * Compatible with Confluence 9.x and 10.x Data Center
 */

// ============================================================================
// CONFIGURATION: Add or remove group names as needed
// ============================================================================
def groupsToAnalyze = [
    "confluence-users",
    "confluence-administrators"
    // Add more groups here as needed:
    // "developers",
    // "content-creators",
]
// ============================================================================

// Validate input
if (!groupsToAnalyze || groupsToAnalyze.isEmpty()) {
    return "<div style='color: red; padding: 20px; background-color: #FFEBE6; border: 2px solid #DE350B;'>" +
           "<h2>❌ Error: No Groups Specified</h2>" +
           "<p>Please add at least one group name to the 'groupsToAnalyze' list.</p></div>"
}

// Get required services using ComponentLocator (compatible with both v9 and v10)
def userAccessor = ComponentLocator.getComponent(UserAccessor)
def groupManager = ComponentLocator.getComponent(GroupManager)

def results = [:]

// Function to get users from a group
def getUsersFromGroup(String groupName, groupMgr, userAcc) {
    def users = []
    
    // Check if group exists
    def group = groupMgr.getGroup(groupName)
    if (!group) {
        return ["error": "Group '${groupName}' not found"]
    }
    
    // Get all users in the group
    def memberNames = groupMgr.getMemberNames(group)
    
    // Iterate through members and get their details
    memberNames.each { username ->
        def user = userAcc.getUserByName(username)
        if (user) {
            users.add([
                username: user.name,
                fullName: user.fullName,
                email: user.email ?: "No email set"
            ])
        }
    }
    
    return users
}

// Get users from all specified groups
groupsToAnalyze.each { groupName ->
    results[groupName] = getUsersFromGroup(groupName, groupManager, userAccessor)
}

// Format and display results in HTML
def output = new StringBuilder()

output.append("""
<html>
<head>
    <style>
        body { font-family: Arial, sans-serif; margin: 20px; }
        h1 { color: #0052CC; border-bottom: 3px solid #0052CC; padding-bottom: 10px; }
        h2 { color: #172B4D; margin-top: 30px; background-color: #F4F5F7; padding: 10px; border-left: 4px solid #0052CC; }
        table { border-collapse: collapse; width: 100%; margin-bottom: 30px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
        th { background-color: #0052CC; color: white; padding: 12px; text-align: left; font-weight: bold; }
        td { padding: 10px; border-bottom: 1px solid #ddd; }
        tr:hover { background-color: #F4F5F7; }
        .error { color: #DE350B; font-weight: bold; padding: 10px; background-color: #FFEBE6; border-left: 4px solid #DE350B; }
        .no-users { color: #6B778C; font-style: italic; padding: 10px; }
        .summary { background-color: #E3FCEF; padding: 15px; margin-top: 20px; border-left: 4px solid #00875A; font-size: 16px; font-weight: bold; }
        .count { color: #0052CC; font-weight: bold; margin-top: 10px; }
    </style>
</head>
<body>
    <h1>📊 Confluence Group User Analysis</h1>
""")

results.each { groupName, userData ->
    output.append("<h2>Group: ${groupName}</h2>\n")
    
    if (userData instanceof Map && userData.containsKey("error")) {
        output.append("<div class='error'>ERROR: ${userData.error}</div>\n")
    } else if (userData.isEmpty()) {
        output.append("<div class='no-users'>No users found in this group</div>\n")
    } else {
        output.append("""
        <table>
            <thead>
                <tr>
                    <th>Username</th>
                    <th>Full Name</th>
                    <th>Email</th>
                </tr>
            </thead>
            <tbody>
        """)
        
        userData.each { user ->
            output.append("""
                <tr>
                    <td>${user.username}</td>
                    <td>${user.fullName}</td>
                    <td>${user.email}</td>
                </tr>
            """)
        }
        
        output.append("""
            </tbody>
        </table>
        <div class='count'>Total users in group: ${userData.size()}</div>
        """)
    }
}

// Summary
def totalUsers = results.values().findAll { it instanceof List }.sum { it.size() } ?: 0
def groupCount = groupsToAnalyze.size()
output.append("""
    <div class='summary'>
        ✅ TOTAL USERS ACROSS ${groupCount} GROUP${groupCount > 1 ? 'S' : ''}: ${totalUsers}
    </div>
</body>
</html>
""")

// Return the formatted HTML output
return output.toString()

