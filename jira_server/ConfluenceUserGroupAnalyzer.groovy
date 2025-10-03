package com.adaptavist

import com.atlassian.confluence.user.UserAccessor
import com.atlassian.confluence.user.GroupManager
import com.atlassian.confluence.user.ConfluenceUser
import com.atlassian.confluence.user.UserDetails
import com.atlassian.confluence.core.ConfluenceActionSupport
import com.atlassian.confluence.security.login.LoginManager
import com.atlassian.confluence.user.UserAccessor
import com.atlassian.confluence.user.GroupManager
import com.atlassian.confluence.user.ConfluenceUser
import com.atlassian.confluence.user.UserDetails
import com.atlassian.confluence.core.ConfluenceActionSupport
import com.atlassian.confluence.security.login.LoginManager
import groovy.xml.MarkupBuilder
import org.apache.log4j.Logger

// Logger for debugging
def log = Logger.getLogger("com.adaptavist.ConfluenceUserGroupAnalyzer")

// Helper function to get user details safely
def getUserDetails(userAccessor, user) {
    try {
        if (!user) return [displayName: "Unknown", email: "Unknown", username: "Unknown"]
        
        def userDetails = userAccessor.getUserDetails(user)
        if (!userDetails) return [displayName: "Unknown", email: "Unknown", username: "Unknown"]
        
        return [
            displayName: userDetails.getFullName() ?: userDetails.getDisplayName() ?: user.getUsername(),
            email: userDetails.getEmail() ?: "No email",
            username: user.getUsername()
        ]
    } catch (Exception e) {
        log.warn("Error getting user details for ${user?.getUsername()}: ${e.message}")
        return [displayName: "Error", email: "Error", username: user?.getUsername() ?: "Unknown"]
    }
}

// Function to get users from a group
def getUsersFromGroup(groupManager, userAccessor, groupName) {
    try {
        def group = groupManager.getGroup(groupName)
        if (!group) {
            log.warn("Group '${groupName}' not found")
            return []
        }
        
        def users = groupManager.getMemberNames(group)
        return users.collect { username ->
            try {
                def user = userAccessor.getUserByName(username)
                getUserDetails(userAccessor, user)
            } catch (Exception e) {
                log.warn("Error processing user ${username}: ${e.message}")
                [displayName: "Error", email: "Error", username: username]
            }
        }
    } catch (Exception e) {
        log.error("Error getting users from group '${groupName}': ${e.message}")
        return []
    }
}

// Function to compare two groups and find differences
def compareGroups(group1Users, group2Users, group1Name, group2Name) {
    def group1Usernames = group1Users.collect { it.username }.toSet()
    def group2Usernames = group2Users.collect { it.username }.toSet()
    
    def onlyInGroup1 = group1Users.findAll { !group2Usernames.contains(it.username) }
    def onlyInGroup2 = group2Users.findAll { !group1Usernames.contains(it.username) }
    def inBothGroups = group1Users.findAll { group2Usernames.contains(it.username) }
    
    return [
        onlyInGroup1: onlyInGroup1,
        onlyInGroup2: onlyInGroup2,
        inBothGroups: inBothGroups
    ]
}

// Main function to generate user group analysis
def generateUserGroupAnalysis() {
    try {
        // Get Confluence services
        def userAccessor = com.atlassian.confluence.core.ConfluenceActionSupport.getComponent(UserAccessor.class)
        def groupManager = com.atlassian.confluence.core.ConfluenceActionSupport.getComponent(GroupManager.class)
        
        if (!userAccessor || !groupManager) {
            return "Error: Could not access Confluence user services. This script must be run in Confluence."
        }
        
        // Define the groups to analyze - MODIFY THESE GROUP NAMES AS NEEDED
        def group1Name = "confluence-users"  // Change this to your first group
        def group2Name = "confluence-administrators"  // Change this to your second group
        
        log.info("Analyzing groups: ${group1Name} and ${group2Name}")
        
        // Get users from both groups
        def group1Users = getUsersFromGroup(groupManager, userAccessor, group1Name)
        def group2Users = getUsersFromGroup(groupManager, userAccessor, group2Name)
        
        // Compare groups
        def comparison = compareGroups(group1Users, group2Users, group1Name, group2Name)
        
        // Generate HTML output
        def writer = new StringWriter()
        def html = new MarkupBuilder(writer)
        
        // CSS styles
        def tableStyle = "border-collapse: collapse; width: 100%; margin: 10px 0;"
        def headerStyle = "background-color: #f2f2f2; font-weight: bold; padding: 8px; border: 1px solid #ddd;"
        def cellStyle = "padding: 8px; border: 1px solid #ddd;"
        def sectionStyle = "margin: 20px 0; padding: 15px; border: 1px solid #ccc; background-color: #f9f9f9;"
        
        html.div {
            h2("Confluence User Group Analysis")
            
            // Summary section
            div(style: sectionStyle) {
                h3("Summary")
                p("Group 1 (${group1Name}): ${group1Users.size()} users")
                p("Group 2 (${group2Name}): ${group2Users.size()} users")
                p("Users in both groups: ${comparison.inBothGroups.size()}")
                p("Users only in ${group1Name}: ${comparison.onlyInGroup1.size()}")
                p("Users only in ${group2Name}: ${comparison.onlyInGroup2.size()}")
            }
            
            // Group 1 users table
            div(style: sectionStyle) {
                h3("Users in ${group1Name}")
                table(style: tableStyle) {
                    tr(style: headerStyle) {
                        th("Username")
                        th("Full Name")
                        th("Email Address")
                    }
                    group1Users.each { user ->
                        tr {
                            td(style: cellStyle, user.username)
                            td(style: cellStyle, user.displayName)
                            td(style: cellStyle, user.email)
                        }
                    }
                }
            }
            
            // Group 2 users table
            div(style: sectionStyle) {
                h3("Users in ${group2Name}")
                table(style: tableStyle) {
                    tr(style: headerStyle) {
                        th("Username")
                        th("Full Name")
                        th("Email Address")
                    }
                    group2Users.each { user ->
                        tr {
                            td(style: cellStyle, user.username)
                            td(style: cellStyle, user.displayName)
                            td(style: cellStyle, user.email)
                        }
                    }
                }
            }
            
            // Users only in group 1
            if (comparison.onlyInGroup1.size() > 0) {
                div(style: sectionStyle) {
                    h3("Users only in ${group1Name} (not in ${group2Name})")
                    table(style: tableStyle) {
                        tr(style: headerStyle) {
                            th("Username")
                            th("Full Name")
                            th("Email Address")
                        }
                        comparison.onlyInGroup1.each { user ->
                            tr {
                                td(style: cellStyle, user.username)
                                td(style: cellStyle, user.displayName)
                                td(style: cellStyle, user.email)
                            }
                        }
                    }
                }
            }
            
            // Users only in group 2
            if (comparison.onlyInGroup2.size() > 0) {
                div(style: sectionStyle) {
                    h3("Users only in ${group2Name} (not in ${group1Name})")
                    table(style: tableStyle) {
                        tr(style: headerStyle) {
                            th("Username")
                            th("Full Name")
                            th("Email Address")
                        }
                        comparison.onlyInGroup2.each { user ->
                            tr {
                                td(style: cellStyle, user.username)
                                td(style: cellStyle, user.displayName)
                                td(style: cellStyle, user.email)
                            }
                        }
                    }
                }
            }
            
            // Users in both groups
            if (comparison.inBothGroups.size() > 0) {
                div(style: sectionStyle) {
                    h3("Users in both groups")
                    table(style: tableStyle) {
                        tr(style: headerStyle) {
                            th("Username")
                            th("Full Name")
                            th("Email Address")
                        }
                        comparison.inBothGroups.each { user ->
                            tr {
                                td(style: cellStyle, user.username)
                                td(style: cellStyle, user.displayName)
                                td(style: cellStyle, user.email)
                            }
                        }
                    }
                }
            }
        }
        
        log.info("User group analysis completed successfully")
        return writer.toString()
        
    } catch (Exception e) {
        log.error("Error in user group analysis: ${e.message}", e)
        return "Error: ${e.message}"
    }
}

// Execute the analysis
return generateUserGroupAnalysis()
